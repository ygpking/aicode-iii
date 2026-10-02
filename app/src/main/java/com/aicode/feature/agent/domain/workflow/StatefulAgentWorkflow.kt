package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.core.util.runCatchingCancellable
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.container.CommandSleepGuard
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import androidx.annotation.VisibleForTesting
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderFailureKind
import com.aicode.feature.agent.domain.provider.ProviderFailureTaxonomy
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ArgNormalizer
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolArgValidator
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolSpec
import com.aicode.feature.agent.domain.tool.ValidationResult
import com.aicode.feature.agent.domain.tool.partitionByRoundLimit
import com.aicode.feature.agent.domain.tool.unknownToolGuidance
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolRunBudget
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryRecall
import com.aicode.feature.agent.domain.memory.TemporalDecay
import com.aicode.feature.agent.domain.memory.RecallDoc
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider,
    private val memoryRepository: MemoryRepository
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"

        /**
         * 单个工具执行的总时限（兜底）。必须**大于**工具自身上限——命令类工具允许
         * 1800 秒（CommandEngine.MAX_TIMEOUT_MS），取值更小会误杀正跑着的长构建。
         * 这里只防「卡死不返回」：实测虚拟屏曾因内部无界重试卡住整夜，
         * 而当时整条链路上没有任何一道超时拦得住。
         */
        const val TOOL_GUARD_TIMEOUT_MS = 1_900_000L
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_AUTO_FILE = "agent/auto-mode.md"
        /**
         * 分段轮次预算：每段 LLM 轮数。段尾注入一次收束提示（软收敛），不立即停，
         * 避免旧实现「一声不响跑到硬上限才停」；模型持续截断/工具反复报错时主循环仍无界，
         * 靠 [TurnGovernor] 兜底。
         */
        const val TURN_ROUNDS_PER_SEGMENT = 10

        /**
         * 召回时间衰减半衰期：新记忆在同等相关度下胜出（如「上周的结论」 vs 「刚更新的同题结论」）。
         * 月级较温和，避免把仍然有效的长期记忆挤掉。
         */
        val RECALL_TEMPORAL_DECAY = TemporalDecay.MONTH

        /**
         * MMR 相关/多样权衡系数：0.8 表示「以相关性为主，仅在得分接近时用多样性去重」。
         * 用于避免一次召回里挤满几条内容几乎相同的记忆。
         */
        const val RECALL_MMR_LAMBDA = 0.8

        /** 召回索引块尾部提示：命中只给摘要，正文需按需 read。与 renderIndexBlock 配套。 */
        const val RECALL_READ_HINT =
            "\n需要其中某条的完整正文时，先用 memory(action=read, name=..., scope=...) 读取；不要凭摘要猜测细节。"
        /**
         * 由总轮次推导续跑段数：段数 = ceil(总轮次 / 每段轮数) - 1。
         * 保证总预算恰好不减（向上取整的那一段由 [TurnGovernor] 的剩余轮数保护自然浪费）。
         */
        fun continuationsFor(totalRounds: Int): Int =
            ((totalRounds + TURN_ROUNDS_PER_SEGMENT - 1) / TURN_ROUNDS_PER_SEGMENT - 1).coerceAtLeast(0)
        /** 段尾软收敛提示：提示模型先总结中间结论再进入下一段；顺带提醒处理已全部完成却仍挂着的 todo。 */
        const val TURN_WRAP_UP_NOTICE =
            "轮次预算已用去一段。请先收束：用一小段总结当前已完成的工作、仍待解决的事项与下一步计划，然后继续推进；不要开启与本任务无关的新工作。若任务已全部完成，请用 todo 工具清理或归档已完成的条目，保持清单与实际进度一致。"
        /** 单轮工具调用数上限：超出部分当轮不执行、回写说明让模型下一轮再调，避免一次梭哈。 */
        const val MAX_TOOLS_PER_ROUND = 12

        /**
         * run 级工具输出字符预算（本次请求内累计喂进上下文的正文总量）。
         *
         * 取值权衡：单个大输出最大内联 40000 字符，一条 Bash 吐 20 万字符很常见。
         * 阈值设得偏保守（≈100k token 量级），**只在病态 run 才触发**，避免影响正常会话；
         * 触发后的效果是「后续输出落盘、内联只留预览」，模型可自行用 retrieveToolResult 回取。
         * 设为 [ToolRunBudget.DISABLED] 即完全关闭。
         */
        const val RUN_OUTPUT_CHAR_BUDGET: Long = 400_000L
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /**
     * 每会话「上次已注入模式提醒的模式」。模式提醒只在变化时注入，避免每轮把同一段文本
     * 重复拼到最新用户消息上——那样下一轮重建历史时该位置（Anthropic 缓存断点）内容不一致，
     * 会白白丢掉一段前缀缓存。进程内缓存，重启后首轮再注入一次（无害）。
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** 工具批调度器：只读并行 / 变更工具按工作区串行（无状态依赖，进程内单例语义即可）。 */
    private val toolBatchScheduler = ToolBatchScheduler()

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse) : AgentAction
        data class LlmError(val error: String) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED"
    )

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) {
            FileLogger.i(TAG, "手动压缩跳过：历史消息仅 ${history.size} 条，无可压缩内容")
            return false
        }
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        val compacted = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, onEvent = onEvent)
        return compacted.size != history.size
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg
                )

                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isPaused) {
                        // 服务端暂停轮次（pause_turn）：本轮未完，原样续流（不插文案，避免污染历史）。
                        effects.add(AgentSideEffect.CallLlm)
                    } else if (action.response.isTruncated) {
                        newState = newState.copy(
                            messages = newState.messages + AgentMessage.UserMessage(content = "你的回复因长度限制被截断了，请从截断处继续。")
                        )
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // 循环治理三闸：轮次预算（跑多久）/ 连续失败熔断（一直失败就别再烧）/ 死循环哨兵（重复模式）。
        // 三者均为「本次请求内」有状态对象，随 run 新建。
        // 轮次总预算取自偏好设置（默认 50），由设置页可调。
        val totalLlmRounds = generalSettingsRepository.turnTotalLlmRoundsFlow.first()
        val turnGovernor = TurnGovernor(
            roundsPerSegment = TURN_ROUNDS_PER_SEGMENT,
            maxContinuations = continuationsFor(totalLlmRounds)
        )
        val circuitBreaker = FailureCircuitBreaker()
        val loopSentinel = ToolLoopSentinel()
        // run 级累积预算：限制本次请求内工具输出喂进上下文的字符总量。默认关闭（DISABLED），
        // 启用后超限的后续输出一律落盘、内联只留紧凑预览，需回取时用 retrieveToolResult。
        // 随 run 新建，与轮次预算/熔断/哨兵同一生命周期。
        val runBudget = ToolRunBudget(RUN_OUTPUT_CHAR_BUDGET)
        // 模式提醒仅在模式变化时随最新用户消息注入一次（不进 system，避免切换时 system 前缀变化打断缓存）。
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        val recallBlock = buildMemoryRecallBlock(userRequest, currentContext.projectRoot)
        val userContent = buildString {
            append(userRequest)
            modeReminder?.let { append("\n\n").append(it) }
            recallBlock?.let { append("\n\n").append(it) }
        }
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    content = userContent,
                    images = currentContext.inputImages
                )
            )
        )

        val systemPrompt = promptProvider.build(currentContext)
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        // 压缩失败后本轮（本次用户请求内）不再重复尝试压缩，避免每次 LLM 调用都白试一次。
        var compactionAttemptFailed = false
        // 上下文超限自愈：每次用户请求内只对每个方向尝试一次，避免反复白烧。
        var overflowHealAttempted = false
        // 输出预算非法自愈：命中时把输出上限减半重试一次（模型上限比元数据低时会 400）。
        var budgetHalved = false
        var budgetHalvedApplied = false
        // 模型不支持图片自愈：命中时强制剥图重试一次（元数据误判时兜底）。
        var visionStripped = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.CallLlm -> {
                        // 轮次预算治理：段尾先注入收束提示（软收敛），预算耗尽才硬停。
                        when (val turnVerdict = turnGovernor.beginTurn()) {
                            is TurnVerdict.HardStop -> {
                                FileLogger.w(TAG, "轮次预算耗尽（共 $totalLlmRounds 轮），硬停本轮请求")
                                state = state.copy(
                                    isFinished = true,
                                    error = "本轮达到最大迭代上限（共 $totalLlmRounds 轮），已自动停止以避免无限循环。"
                                )
                                continue
                            }
                            TurnVerdict.InjectWrapUpNotice -> {
                                // 段尾软收敛：跨段边界低频提醒一次，提示模型先收束再续跑。
                                FileLogger.i(TAG, "轮次预算段尾，注入收束提示（软收敛）")
                                state = state.copy(
                                    messages = state.messages + AgentMessage.UserMessage(content = TURN_WRAP_UP_NOTICE)
                                )
                            }
                            TurnVerdict.Continue -> Unit
                        }
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val sessionLastInputTokens = currentContext.sessionId?.let { sessionUseCase.getSessionById(it)?.lastInputTokens } ?: 0
                            compactedMessages = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId, lastInputTokens = sessionLastInputTokens, windowProvider = aiProvider) { event ->
                                // 仅确定性失败关停本轮后续压缩；临时性失败（网关 503 等）下轮 LLM 调用前还可再试。
                                if (event is AgentEvent.CompactionFailed && !event.transient) {
                                    if (!compactionAttemptFailed) {
                                        FileLogger.w(TAG, "压缩确定性失败（${event.reason}），本次请求内不再自动压缩")
                                    }
                                    compactionAttemptFailed = true
                                }
                                send(event)
                            }
                            if (compactedMessages !== state.messages) {
                                state = state.copy(messages = compactedMessages)
                            }
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var ttfbElapsed: Long? = null
                        var callError: String? = null
                        var callCompleted = false

                        // 流式 delta 节流：上游每个 chunk 都携带完整累积文本，逐条 send 会让
                        // ViewModel 端每秒重建几十次状态、UI 端反复重启打字机协程。按时间窗口合并：
                        // 窗口内只保留最新累积文本，到窗口边界才发送，把下游事件频率压到 ~16/s。
                        // 打字机渲染本身有 100ms 节流，少发中间态无感知；collect 结束补发最后 pending。
                        val DELTA_THROTTLE_MS = 60L
                        var lastTextDeltaSentAt = 0L
                        var lastReasoningDeltaSentAt = 0L
                        var pendingTextDelta: String? = null
                        var pendingReasoningDelta: String? = null
                        var retryAttempts = 0
                        suspend fun flushPendingTextDelta() {
                            val text = pendingTextDelta ?: return
                            pendingTextDelta = null
                            send(AgentEvent.AssistantDelta(text))
                        }
                        suspend fun flushPendingReasoningDelta() {
                            val text = pendingReasoningDelta ?: return
                            pendingReasoningDelta = null
                            send(AgentEvent.ReasoningDelta(text))
                        }

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            // visionStripped：上次因「模型不支持图片」失败，本次强制剥图。
                            val supportsVision = !visionStripped && activeModelSupportsVision(currentContext.sessionId)
                            // budgetHalved：上次因「输出预算非法」失败，本次把输出上限减半。
                            // 只减一次（budgetHalved 置位后不再复位，而本分支每轮都跑）：
                            // 若每轮都再减半，N 轮后上限变 1/2^N 很快钳到 1，后续回复全被截断。
                            if (budgetHalved && !budgetHalvedApplied) {
                                providerInUse.maxOutputTokens = providerInUse.maxOutputTokens?.let { maxOf(1, it / 2) }
                                budgetHalvedApplied = true
                            }
                            val sanitized = sanitizeImagesForModel(compactedMessages, supportsVision)
                            // D5/D3/D4：巨型单条截断 + 首条 user 桥接 + 请求体字节预算（只作用于本次发送副本）。
                            val messagesToSend = RequestPayloadGuard.prepareForSend(
                                sanitized,
                                budgetTokens = requestBudgetTokens(currentContext.sessionId)
                            )
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        acc.append(chunk.text)
                                        pendingTextDelta = acc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastTextDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingTextDelta()
                                            lastTextDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        // 思考内容也算首字（推理模型先吐思考再吐正文）
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        reasoningAcc.append(chunk.text)
                                        pendingReasoningDelta = reasoningAcc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastReasoningDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingReasoningDelta()
                                            lastReasoningDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        retryAttempts = maxOf(retryAttempts, chunk.attempt)
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                                    }
                                    is AIStreamChunk.KeySwitched -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                                    }
                                    is AIStreamChunk.Final -> {
                                        // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        finalResponse = chunk.response
                                    }
                                    is AIStreamChunk.ToolCallDeclared -> {
                                        // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                                        // 免得长参数流式期间一直停在「正在思考」。
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        send(AgentEvent.ToolCallPreparing(chunk.name))
                                    }
                                }
                            }
                            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
                            flushPendingTextDelta()
                            flushPendingReasoningDelta()
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            // 模型直出图片（Gemini 图像模型）不随流式增量到达，整块在 Final 里：
                            // 先把 base64 落盘到 ~/.aicode/generated-images/ 并构造 UI 附件（只存路径不存 base64），
                            // 再随 LlmResponse 把带 path 的 images 交 reduce 挂上 AssistantMessage 供下一回放。
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        reasoningAcc.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments
                                    )
                                )
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // 自愈闭环：上下文超限 → 强制机械压缩后重试一次（原错误不再上报）。
                            // 仅当本轮尚未尝试过自愈、且压缩未失败时才重试，防止连环失败。
                            val heal = ProviderFailureTaxonomy.classify(e)
                            if (heal == ProviderFailureKind.CONTEXT_OVERFLOW && !overflowHealAttempted && !compactionAttemptFailed) {
                                overflowHealAttempted = true
                                FileLogger.w(TAG, "上下文超限，触发强制压缩后重试一次")
                                val compacted = contextCompactor.compactIfNeeded(
                                    state.messages, providerInUse, context.sessionId,
                                    lastInputTokens = 0, windowProvider = aiProvider, force = true
                                ) { event ->
                                    // 同上：临时性失败不关停，给后续轮次的自动压缩留机会。
                                    if (event is AgentEvent.CompactionFailed && !event.transient) {
                                        if (!compactionAttemptFailed) {
                                            FileLogger.w(TAG, "压缩确定性失败（${event.reason}），本次请求内不再自动压缩")
                                        }
                                        compactionAttemptFailed = true
                                    }
                                    send(event)
                                }
                                if (!compactionAttemptFailed) {
                                    state = state.copy(messages = compacted)
                                    // 重新走一次 InitRequest：以压缩后的消息重发，等效于「压缩后的新轮」。
                                    actionQueue.addFirst(AgentAction.InitRequest(compacted))
                                    callError = "上下文超限：已自动压缩后重试"
                                } else {
                                    actionQueue.addLast(AgentAction.LlmError("LLM 调用失败: ${e.message}"))
                                    callError = e.message ?: e.javaClass.simpleName
                                }
                            } else if (heal == ProviderFailureKind.INVALID_OUTPUT_BUDGET && !budgetHalved) {
                                // 输出预算非法：把输出上限减半重发一次（适配器下次会重新取 maxOutputTokens）。
                                budgetHalved = true
                                FileLogger.w(TAG, "输出预算非法，减半后重试一次")
                                actionQueue.addFirst(AgentAction.InitRequest(state.messages))
                                callError = "输出预算非法：已减半后重试"
                            } else if (heal == ProviderFailureKind.UNSUPPORTED_VISION && !visionStripped) {
                                // 模型不支持图片（元数据误判）：强制剥图重发一次。
                                visionStripped = true
                                FileLogger.w(TAG, "模型不支持图片，剥图后重试一次")
                                actionQueue.addFirst(AgentAction.InitRequest(state.messages))
                                callError = "模型不支持图片：已剥图后重试"
                            } else {
                                // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
                                // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
                                actionQueue.addLast(AgentAction.LlmError("LLM 调用失败: ${e.message}"))
                                callError = e.message ?: e.javaClass.simpleName
                            }
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = finalResponse
                            runCatchingCancellable {
                                llmCallRecordDao.insert(
                                    LlmCallRecordEntity(
                                        sessionId = currentContext.sessionId,
                                        providerId = providerInUse.providerId.ifBlank { null },
                                        model = providerInUse.model,
                                        reasoningEffort = currentContext.reasoningEffort,
                                        kind = callKind,
                                        inputTokens = usage?.inputTokens ?: 0,
                                        outputTokens = usage?.outputTokens ?: 0,
                                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                        ttfbMillis = ttfbElapsed?.toInt(),
                                        durationMillis = durationMillis,
                                        status = when {
                                            callCompleted -> "success"
                                            callError != null -> "error"
                                            else -> "cancelled"
                                        },
                                        errorMessage = callError,
                                        stopReason = usage?.stopReason,
                                        retryCount = retryAttempts,
                                        createdAt = callStartWall
                                    )
                                )
                            }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        // sleep 守卫前置：弹窗之前就拦下含独立长 sleep 的命令，避免用户白点一次允许。
                        // 拦截仍走 PermissionEvaluated(false)（非 USER_REJECTED）让 batch 状态机正常推进：
                        // 该 call 以拒绝结果回放给模型；直接跳过会把 pendingPermissionCalls 卡死。
                        // terminal 的 start/send 同样承载 shell 命令，纳入同一道前置守卫。
                        val guardArgs = effect.toolCall.arguments
                        val guardAction = (guardArgs["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                        val shellPayload = when {
                            effect.toolCall.name == "Bash" -> (guardArgs["command"] as? JsonPrimitive)?.contentOrNull
                            effect.toolCall.name == "terminal" && guardAction == "start" -> (guardArgs["command"] as? JsonPrimitive)?.contentOrNull
                            effect.toolCall.name == "terminal" && guardAction == "send" -> (guardArgs["input"] as? JsonPrimitive)?.contentOrNull
                            else -> null
                        }
                        val sleepBlock = shellPayload?.let { CommandSleepGuard.blockReason(it) }
                        if (sleepBlock != null) {
                            FileLogger.i(TAG, "命令被 sleep 守卫前置拦截: $shellPayload")
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, ToolResult.Error(sleepBlock, "SLEEP_BLOCKED").toTransportString(), true, argsPreview))
                            actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, false, argsPreview, sleepBlock, "SYSTEM_DENIED"))
                        } else {
                            val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode, currentContext.sessionId)

                            if (!checkResult.approved) {
                                val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                                send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                            } else {
                                send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                            }
                            actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                        }
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 批 5：单轮工具数上限——超出部分当轮不执行、回写说明让模型下一轮再调；
                        // 同时对「缺必填 / 幻觉工具名」做前置校验，把难懂的执行期失败变成显式自纠。
                        val toolCalls = effect.toolCalls
                        val (executableCalls, skippedCalls) = partitionByRoundLimit(toolCalls, MAX_TOOLS_PER_ROUND)
                        val preflight = HashMap<String, ToolRunResult>()
                        val toRun = mutableListOf<ToolCall>()
                        for (toolCall in executableCalls) {
                            val tool = toolRegistry.getTool(toolCall.name)
                            val problem = validateToolCall(toolCall, tool)
                            if (problem != null) preflight[toolCall.id] = problem
                            else toRun += normalizeToolCall(toolCall, tool)
                        }
                        for (toolCall in skippedCalls) {
                            preflight[toolCall.id] = ToolRunResult(
                                ToolResult.Error(
                                    "单轮工具调用过多（上限 $MAX_TOOLS_PER_ROUND），本调用本轮未执行；请下一轮再调用。",
                                    "ROUND_TOOL_LIMIT"
                                ).toTransportString(),
                                true
                            )
                        }

                        // 统一记录 checkpoint（editFile/writeFile 修改前快照），再并行执行；
                        // mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
                        toRun.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val executed = if (toRun.isEmpty()) {
                            emptyList()
                        } else {
                            // 收敛：只读工具并行、变更工具在**同一工作区内串行**，避免同批写工具并发覆盖文件。
                            toolBatchScheduler.dispatch(
                                calls = toRun,
                                workspaceKey = currentContext.projectRoot,
                                toolNameOf = { it.name },
                            ) { toolCall ->
                                val tool = toolRegistry.getTool(toolCall.name)
                                if (tool is StreamingAgentTool) {
                                    runToolStream(tool, toolCall, currentContext, runBudget) { send(it) }
                                } else {
                                    runToolSync(tool, toolCall, currentContext, runBudget)
                                }
                            }
                        }
                        // 按原始 toolCalls 顺序对齐结果：前置失败/超限取 preflight，其余取执行结果。
                        val executedById = HashMap<String, ToolRunResult>(toRun.size)
                        toRun.forEachIndexed { i, call -> executedById[call.id] = executed.getOrElse(i) {
                            ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                        } }
                        val runResults = toolCalls.map { call ->
                            preflight[call.id]
                                ?: executedById[call.id]
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // 退出 PLAN 时挂起 workflow，等待用户在计划审查面板批准后才继续
                                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system 与 mode 已解耦（SystemPromptProvider 不再注入模式提示词），
                                        // 切换不重建 systemPrompt，避免 system 前缀变化打断缓存；模式状态通过工具结果与下轮消息提醒告知。
                                        rawResult += buildModeSwitchNotice(newCtx.mode)
                                    } else {
                                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消并等待用户反馈
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error(
                                            "用户希望补充说明或调整方案，当前保持在 PLAN 模式。请等待用户输入具体的补充或修改意见，不要自行臆测修改，待用户明确反馈后再继续。",
                                            "MODE_SWITCH_REJECTED"
                                        ).toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult += buildModeSwitchNotice(newCtx.mode)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // 本轮内到达的后台任务/子代理完成通知：搭在本批最后一条工具结果上立即送达，
                        // AI 当轮即可感知，不必等本轮结束再起新一轮。ack 放到完成事件发出（结果已落库）之后：
                        // 中途被取消时通知仍留在队列里，由后续批次或本轮结束的兜底路径送达。
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // 用户在工作期间切换模式：本轮后续批次的权限判定立即改用新模式。
                                // 与 SessionUseCase.updateMode 保持同一套语义：进入 PLAN 记下进入前的模式，其余情况清空。
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                            }
                            val last = batchResults.last()
                            var injected = eventInjector.inject(last.result, notifications)
                            if (newMode != null) {
                                // 模式约束提示随工具结果落库，留在历史里供后续轮沿用。
                                injected += buildExternalModeSwitchNotice(newMode)
                            }
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // 循环治理：任一闸触发软收敛则把纠偏提示混入本批结果；触发硬停则不再进入下一轮。
                        val governanceStop = applyLoopGovernance(toolCalls, batchResults, loopSentinel, circuitBreaker)

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError, attachments = br.attachments))
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        if (governanceStop != null) {
                            state = state.copy(isFinished = true, error = governanceStop)
                        } else {
                            actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                        }
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
        send(AgentEvent.Completed)
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext, runBudget: ToolRunBudget? = null): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            val guidance = unknownToolGuidance(name, toolRegistry.getToolNames())
            return ToolRunResult(ToolResult.Error(guidance, "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            // 兜底守卫：单个工具不得无限期占住本轮。取值必须**大于**工具自身上限
            // （命令类工具允许 1800 秒，见 CommandEngine.MAX_TIMEOUT_MS），否则会误杀
            // 正跑着的长构建。此处只防「卡死不返回」——实测虚拟屏曾因内部逻辑无界重试
            // 卡住整夜，而当时链路上没有任何一道超时能拦住它。
            val result = withTimeoutOrNull(TOOL_GUARD_TIMEOUT_MS) {
                tool.executeWithContext(toolCall.arguments, context)
            } ?: return ToolRunResult(
                ToolResult.Error(
                    "工具 $name 执行超时（超过 ${TOOL_GUARD_TIMEOUT_MS / 60_000} 分钟仍未返回），已中止。" +
                        "${timeoutGuidance(name)}",
                    "TOOL_TIMEOUT"
                ).toTransportString(),
                true
            )
            val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
            val images = if (result is ToolResult.Success) result.images else emptyList()
            val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
            val processed = toolOutputStore.process(name, toolCall.id, transportResult, runBudget)
            ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}。请勿用完全相同的参数重试；先检查参数或换个思路。", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 工具超时后的下一步指引。
     *
     * 光说「超时了」模型只能干猜（实测它会直接拿原参数重试，越卡越死）。按已知成因给出
     * 可执行动作：不同工具卡住的原因与其可用替代路径是确定的，能写明的都写进消息里。
     */
    private fun timeoutGuidance(name: String): String = when (name) {
        "virtualScreen" -> "虚拟屏需要经 Shizuku 与独立进程通信，卡住通常是 Shizuku 授权被回收或" +
            "后台进程未退出。请先执行 action=status 看 daemon 与无障碍状态，必要时用 action=close 收尾；" +
            "不要用完全相同的参数重试。"
        "Bash", "terminal" -> "命令可能仍在后台跑。先用 terminal action=read 读已有标签的输出，" +
            "确认是否真的还没结束；若是，用 ctrl+c 中断，或改用 notify=true 的后台方式重新发起。"
        "task" -> "子代理可能仍在运行。先用 task action=list 看状态，需要时 read 取回；" +
            "不要重复创建同一任务。"
        "webfetch", "websearch", "browser" -> "网络请求可能卡在无响应的对端。换个来源或缩短范围后重试。"
        else -> "先确认该工具是否已产生副作用（用只读工具核对现场），再决定是否重试；" +
            "不要用完全相同的参数原样重试。"
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /** 当前生效模型的上下文窗口（token）；取不到时回退默认窗口。供请求体治理 D5 的估算。 */
    private suspend fun requestBudgetTokens(sessionId: String?): Int {
        val config = resolveProviderConfig(sessionId) ?: return ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.contextTokens.takeIf { it > 0 } ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatchingCancellable {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            // 记录 LLM 调用（挂起 DAO）：用 runCatchingCancellable，取消照常传播到外层。
            runCatchingCancellable {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "title",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatchingCancellable {
        if (diff.isBlank()) return@runCatchingCancellable null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        runBudget: ToolRunBudget? = null,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        try {
            // 与非流式路径同款兜底：底层 flow 若不终止（无界重试/卡死），collect 会一直占住本轮，
            // 链路上没有任何东西能拦住它。这里用 withTimeoutOrNull 判定超时——不能用
            // catch (TimeoutCancellationException)，它会被下方 catch (CancellationException) 重新抛出。
            val completed = collectStreamWithin(
                toolCall = toolCall,
                context = context,
                tool = tool,
                timeoutMs = TOOL_GUARD_TIMEOUT_MS,
                onProgress = { text ->
                    onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, text))
                },
                onCompleted = { finalResult = it }
            )
            if (!completed) {
                return ToolRunResult(
                    ToolResult.Error(
                        "工具 ${toolCall.name} 执行超时（超过 ${TOOL_GUARD_TIMEOUT_MS / 60_000} 分钟仍未返回），已中止。" +
                            timeoutGuidance(toolCall.name),
                        "TOOL_TIMEOUT"
                    ).toTransportString(),
                    true
                )
            }
            val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
            val processed = toolOutputStore.process(toolCall.name, toolCall.id, result, runBudget)
            val images = if (result is ToolResult.Success) result.images else emptyList()
            return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 带兜底超时地收集流式工具的输出，直到 flow 结束（返回 true）或超时（返回 false）。
     *
     * 抽成独立函数是为了让超时可被单测注入——[TOOL_GUARD_TIMEOUT_MS] 是 const，测试无法调小它。
     * 进度事件沿用节流逻辑，超时判定只看 `withTimeoutOrNull` 的返回值，不依赖异常。
     */
    @VisibleForTesting
    internal suspend fun collectStreamWithin(
        toolCall: ToolCall,
        context: AgentContext,
        tool: StreamingAgentTool,
        timeoutMs: Long,
        onProgress: suspend (String) -> Unit,
        onCompleted: (ToolResult) -> Unit
    ): Boolean {
        val live = StringBuilder()
        var lastEmitMs = 0L
        val completed = withTimeoutOrNull(timeoutMs) {
            tool.executeStream(toolCall.arguments, context).collect { ev ->
                when (ev) {
                    is ToolStreamEvent.Progress -> {
                        live.append(ev.chunk).append('\n')
                        if (live.length > LIVE_TAIL_CHARS) {
                            live.delete(0, live.length - LIVE_TAIL_CHARS)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                            lastEmitMs = now
                            onProgress(live.toString())
                        }
                    }
                    is ToolStreamEvent.Completed -> onCompleted(ev.result)
                }
            }
            true
        }
        return completed == true
    }
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // 进入 PLAN 时记住当前模式；退出时恢复到它（AUTO→PLAN 的规划往返结束后回到 AUTO 而非 BUILD）。
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.AUTO -> promptProvider.resolvePrompt(MODE_REMINDER_AUTO_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /**
     * 把待送通知注入工具结果：优先作为 transport JSON 顶层的 `notifications` 字段，结果仍是合法 JSON，
     * UI 的 formatToolResult（只读 data/message）与各类结构化解析不受影响。
     * raw 已被模式切换提示等纯文本追加过、不再是合法 JSON 时，退化为文本追加。
     */
    /**
     * 取本次应注入的模式提醒：仅当模式与上次注入时不同（含首次）才返回文本并记录，否则为 null。
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** 工具调用成功后拼进 planMode 工具结果的模式状态通知（当轮即可见，无需等下一条用户消息）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】计划已获用户批准，你已切换到 BUILD（构建）模式，可以开始执行计划。"
        AgentMode.AUTO -> "\n\n【模式切换】计划已获用户批准，你已恢复到 AUTO（自动）模式，可以开始执行计划（工具调用将自动放行）。"
    }

    /** 用户在外部（界面）手动切换模式时拼进工具结果的提示；与 AI 自切的 [buildModeSwitchNotice] 区分，避免误称「计划已获批准」。 */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】用户已将模式切换为 BUILD（构建）模式，可以正常执行写操作。"
        AgentMode.AUTO -> "\n\n【模式切换】用户已将模式切换为 AUTO（自动）模式。"
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    /**
     * 循环治理：逐调用喂死循环哨兵、按轮喂连续失败熔断。
     *
     * 触发软收敛时把纠偏提示追加到本批最后一条工具结果（随下轮回放给模型）；触发硬停时
     * 返回停止原因，由调用方以失败收尾。返回 null 表示可继续下一轮。
     */
    private fun applyLoopGovernance(
        toolCalls: List<ToolCall>,
        batchResults: MutableList<ToolBatchResult>,
        sentinel: ToolLoopSentinel,
        breaker: FailureCircuitBreaker,
    ): String? {
        if (batchResults.isEmpty()) return null

        fun appendNotice(text: String) {
            val last = batchResults[batchResults.lastIndex]
            batchResults[batchResults.lastIndex] = last.copy(result = last.result + "\n\n[系统提示] " + text)
        }

        var stopReason: String? = null
        toolCalls.forEachIndexed { index, toolCall ->
            val br = batchResults.getOrNull(index) ?: return@forEachIndexed
            when (val verdict = sentinel.observe(toolCall.name, toolCallFingerprint(toolCall), br.isError, br.result.hashCode())) {
                is LoopVerdict.Blocked -> if (stopReason == null) {
                    stopReason = "检测到工具调用进入死循环（${verdict.reason}），已自动停止。"
                    FileLogger.w(TAG, "循环治理硬停：工具调用死循环（${verdict.reason}）")
                }
                is LoopVerdict.SuspectedLoop -> {
                    FileLogger.i(TAG, "循环治理软收敛：疑似重复调用（${verdict.reason}），已注入纠偏提示")
                    appendNotice(
                        "检测到重复调用（${verdict.reason}），请换用不同思路或参数，不要用相同参数重试。"
                    )
                }
                LoopVerdict.Ok -> Unit
            }
        }
        when (val state = breaker.record(batchResults.all { it.isError })) {
            is BreakerState.Tripped -> if (stopReason == null) {
                stopReason = "连续 ${state.consecutiveFailures} 轮工具调用全部失败，已熔断停止以避免持续失败与费用浪费。"
                FileLogger.w(TAG, "循环治理硬停：连续 ${state.consecutiveFailures} 轮工具调用全部失败，熔断")
            }
            BreakerState.WarnedOnce -> {
                FileLogger.w(TAG, "循环治理软收敛：连续 ${breaker.consecutiveFailures} 轮工具调用全部失败，注入纠偏提示")
                appendNotice("已连续多轮工具调用失败，请先核对失败原因与参数，再决定是否继续。")
            }
            BreakerState.Closed -> Unit
        }
        return stopReason
    }

    /** 工具调用参数指纹：键排序后拼接，保证「同集合不同顺序」得到同一指纹。 */
    private fun toolCallFingerprint(call: ToolCall): String =
        call.arguments.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }

    /**
     * 按本轮用户请求召回相关记忆，渲染成可附到 user 消息后缀的块。
     *
     * 召回块只挂在「本轮发往模型的消息」上（in-memory），不落库：下一轮历史重建时自然消失，
     * 避免把动态内容写进历史造成前缀字节漂移。选择为纯函数且结果确定（同 query+docs 同结果）。
     * 读盘/解析失败时不召回（不影响主流程）。
     */
    private fun buildMemoryRecallBlock(query: String, projectRoot: String?): String? {
        val memories = runCatching { memoryRepository.listMemories(projectRoot) }.getOrNull()
            ?: return null
        if (memories.isEmpty()) return null
        val docs = memories.map { m ->
            RecallDoc(
                id = m.name,
                scope = m.scope,
                text = if (m.description.isBlank()) m.content else "${m.description}\n${m.content}",
                pinned = m.pinned,
                updatedAtMs = m.effectiveUpdatedAtMs,
                triggers = m.triggers,
            )
        }
        val hits = MemoryRecall.select(
            query,
            docs,
            temporalDecay = RECALL_TEMPORAL_DECAY,
            mmrLambda = RECALL_MMR_LAMBDA,
        )
        if (hits.isEmpty()) return null
        // 每轮一次的低频日志：记录本次召回注入条数，供排查「记忆该生效却没生效 / 注入过多撑大上下文」。
        FileLogger.i(TAG, "本轮注入 ${hits.size} 条记忆召回块")
        // 只给「名 + 摘要」，不内联正文：召回块会成为上下文固定前缀的一部分，内联数千字符正文
        // 而多数命中只需知道「有这么一条」。需要细节时用 memory(action=read, ...) 按需拉取。
        // select() 的挑选结果未变，只是渲染宽度收紧（见 MemoryRecall.renderIndexBlock）。
        return MemoryRecall.renderIndexBlock(hits) + RECALL_READ_HINT
    }

    /** 从工具定义派生参数规格；工具不存在时返回 null。 */
    private fun toolSpecOf(tool: AgentTool?): ToolSpec? = tool?.let {
        ToolSpec(
            name = it.name,
            required = it.parameters.filterValues { p -> p.required }.keys,
            properties = it.parameters.keys,
        )
    }

    /** 前置校验：缺必填 → 返回错误结果让模型自纠；通过则返回 null。 */
    private fun validateToolCall(call: ToolCall, tool: AgentTool?): ToolRunResult? {
        val spec = toolSpecOf(tool) ?: return null
        return when (val result = ToolArgValidator.validate(spec, call.arguments)) {
            is ValidationResult.Ok -> null
            is ValidationResult.Problems -> ToolRunResult(
                ToolResult.Error(
                    "参数校验未通过：${result.messages.joinToString("；")}。请补齐后重试。",
                    "INVALID_TOOL_ARGUMENTS"
                ).toTransportString(),
                true
            )
        }
    }

    /** 参数规整：别名/单键解包/扁平键还原（MCP 短路）；无工具或无改动时原样返回。 */
    private fun normalizeToolCall(call: ToolCall, tool: AgentTool?): ToolCall {
        val spec = toolSpecOf(tool) ?: return call
        val normalized = ArgNormalizer.normalize(spec, call.arguments)
        return if (normalized == call.arguments) call else call.copy(arguments = normalized)
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // planMode 退出 PLAN 时，后续会有计划审查面板兜底用户决策，
        // 此处权限弹窗冗余，直接放行；BUILD→PLAN 方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") {
                FileLogger.i(TAG, "权限判定放行 ${tool.name}（exit PLAN：计划审查面板兜底，跳过弹窗）")
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            FileLogger.i(TAG, "权限判定拒绝 ${tool.name}：$reason（$code）")
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty()
                )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> {
                        FileLogger.i(TAG, "权限判定拒绝 ${tool.name}：用户拒绝（USER_REJECTED）")
                        PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    }
                    PermissionChoice.ONCE -> {
                        FileLogger.i(TAG, "权限判定放行 ${tool.name}：用户单次允许（ONCE）")
                        PermissionCheckResult(true)
                    }
                    PermissionChoice.ALWAYS -> {
                        FileLogger.i(TAG, "权限判定放行 ${tool.name}：用户始终允许，记忆 ${eval.rememberablePatterns.size} 条规则")
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }
}
