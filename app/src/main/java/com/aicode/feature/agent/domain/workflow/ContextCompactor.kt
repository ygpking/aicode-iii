package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.summaryTimestampNotice
import com.aicode.feature.agent.domain.model.stripSummaryTimestampNotice
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.ProviderFailureKind
import com.aicode.feature.agent.domain.provider.ProviderFailureTaxonomy
import com.aicode.feature.agent.domain.tool.ToolOutputScrubber
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ProviderType
import android.os.SystemClock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.aicode.core.util.runCatchingCancellable

@Singleton
class ContextCompactor @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase,
    private val modelMetadataService: ModelMetadataService,
    private val systemPromptProvider: SystemPromptProvider,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val chatSessionDao: ChatSessionDao,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository
) {

    /** 会话上次成功压缩后的消息估算 token 数（自适应阈值的增长基准）。会话级内存态即可，丢了退回固定阈值。 */
    private val lastCompactionSizeBySession = ConcurrentHashMap<String, Int>()

    /** 压缩失败（空摘要/截断）后的冷却截止（elapsedRealtime）。冷却期内同会话跳过自动压缩，防每轮 LLM 调用前原地重试。 */
    private val compactionCooldownUntilBySession = ConcurrentHashMap<String, Long>()

    /**
     * 会话连续压缩失败次数（成功后清零）。
     *
     * 固定 60s 冷却只挡瞬时风暴：若失败是持续性的（如 head 长期未落库、压缩模型持续超预算），
     * 会变成「每 60s 一次完整压缩调用」的慢性成本（实测单次最长 12 分钟、48000 output tokens）。
     * 故按连续次数指数退避，超过 [COMPACTION_FAIL_MAX_STREAK] 后停到有新的成功或手动压缩为止。
     */
    private val compactionFailStreakBySession = ConcurrentHashMap<String, Int>()

    /**
     * 压缩单飞：同一会话同时只允许一次压缩在跑。
     *
     * 三个调用点（自动轮前、手动 /compress、上下文超限自愈）可能同时进入：
     * 实测 10:16:14/10:16:21、11:30:45/11:30:47、11:46:39/11:46:40 均成对触发，
     * 两个并发压缩各产一份摘要抢写同一会话（同一段 head 被压缩两次、各落一份摘要互相覆盖）。
     * 用 putIfAbsent 原子占位，抢不到者直接返回原消息（等持有者写完，下轮自然读到新上下文）。
     */
    private val compactionInFlightBySession = ConcurrentHashMap<String, Boolean>()

    private companion object {
        const val TAG = "ContextCompactor"

        const val TOOL_OUTPUT_MAX_CHARS = 2_000
        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"

        /** 压缩请求的系统提示词：明确「只输出摘要、不要思考」，与模板里的同类约束呼应。 */
        const val COMPACTION_SYSTEM_PROMPT =
            "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，" +
                "不要调用任何工具，只输出接手摘要。不要输出任何思考、推理或分析过程" +
                "（不要 reasoning_content），直接从摘要正文开始。"

        /**
         * 摘要为空/截断时的一次性补救档位。
         *
         * **必须传各 provider 真正的最低合法档**，不能统一传 "minimal"：
         * `OpenAIAdapter.normalizeReasoningEffort` 把 "minimal"/"none" 归一为 null = **不发该字段**，
         * 于是「最小档重试」与首次请求**完全相同**，对强制思考模型是空操作
         * （实测 2026-10-12：4 次失败各 attempted minimal 重试，无一次成功——因为重试请求与首次逐字节相同）。
         * 故按 provider 取真值：OpenAI 官方最低为 "low"（"minimal" 会被丢弃）；Anthropic 无 minimal，归一到 "low"；
         * Gemini 支持 "minimal" 原样透传。见 [minimalEffortFor]。
         */
        const val REASONING_EFFORT_LOWEST_OPENAI = "low"
        const val REASONING_EFFORT_LOWEST_ANTHROPIC = "low"
        const val REASONING_EFFORT_LOWEST_GEMINI = "minimal"

        /** 摘要窗口预留比例：30% 留给摘要提示词与旧摘要，故可用 70%。 */
        const val SUMMARY_WINDOW_RESERVE_RATIO = 0.7f

        /** 单次标记已压缩的 id 分块大小，避开 SQLite 绑定变量上限（旧版 999）。 */
        const val COMPACTED_IDS_CHUNK = 500

        /** 压缩失败后的同会话冷却时长：思考型压缩模型耗尽输出预算后短时间重试必再次失败，先冷却再试。 */
        const val COMPACTION_FAIL_COOLDOWN_MS = 60_000L

        /** 连续失败多少次后停止自动重试（手动 /compress 与强制自愈不受限）。 */
        const val COMPACTION_FAIL_MAX_STREAK = 4

        /** 熔断后的冷却时长：1 小时（足够长到不再形成慢性成本，又不会溢出）。 */
        const val COMPACTION_FAIL_STOP_MS = 60L * 60 * 1000

        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    /**
     * 如果消息体总长度超过阈值，则将早期的消息（Head）提取出来，
     * 通过后台 LLM 调用进行结构化摘要，然后替换回原来的位置。
     *
     * 压缩结果持久化到数据库：
     * - 被压缩的 head 部分消息标记 isCompacted=true（不删除，保留数据完整性）
     * - 摘要消息插入数据库，作为压缩后的上下文起点
     * - 重启后 [MessagePersistenceUseCase.buildHistory] 会跳过 isCompacted 的消息，
     *   只回放摘要 + tail 部分
     *
     * @return 压缩后的新列表（如果没有触发压缩则返回原列表的副本）
     */
    suspend fun compactIfNeeded(
        messages: List<AgentMessage>,
        aiProvider: AIProvider,
        sessionId: String? = null,
        force: Boolean = false,
        lastInputTokens: Int = 0,
        windowProvider: AIProvider? = null,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage> {
        // 失败冷却/熔断只挡**自动**压缩：force=true（手动 /compress 与上下文超限自愈）是用户
        // 主动意图或最后的自救手段，不能被冷却拦住——否则用户点了没反应、或溢出无法自愈。
        val cooldownUntil = sessionId?.takeIf { !force }?.let { compactionCooldownUntilBySession[it] }
        if (cooldownUntil != null && SystemClock.elapsedRealtime() < cooldownUntil) {
            FileLogger.d(TAG, "压缩失败冷却中（剩余 ${(cooldownUntil - SystemClock.elapsedRealtime()) / 1000}s），跳过本次自动压缩")
            return messages
        }
        // 单飞：抢不到租约说明同会话已有压缩在跑，直接返回原消息（持有者写完下轮自然读到新上下文）。
        if (sessionId != null && compactionInFlightBySession.putIfAbsent(sessionId, true) != null) {
            FileLogger.d(TAG, "同会话已有压缩在进行中，跳过本次并发压缩")
            return messages
        }
        try {
            return compactIfNeededLocked(messages, aiProvider, sessionId, force, lastInputTokens, windowProvider, onEvent)
        } finally {
            if (sessionId != null) compactionInFlightBySession.remove(sessionId)
        }
    }

    private suspend fun compactIfNeededLocked(
        messages: List<AgentMessage>,
        aiProvider: AIProvider,
        sessionId: String? = null,
        force: Boolean = false,
        lastInputTokens: Int = 0,
        /**
         * 触发判断用的窗口来源模型：正常为主聊天模型（决定「上下文快撑满谁」），
         * 与 [aiProvider]（执行摘要生成的压缩专用模型）分离，避免小窗口压缩模型导致过早压缩。
         * 为 null 时回退 [aiProvider]。
         */
        windowProvider: AIProvider? = null,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage> {
        val estimatedTokens = estimateTokens(messages)
        val windowModel = windowProvider ?: aiProvider
        val windowMetadata = modelMetadataService.resolve(windowModel.providerId, inferProviderType(windowModel), windowModel.model)
        val summaryMetadata = modelMetadataService.resolve(aiProvider.providerId, inferProviderType(aiProvider), aiProvider.model)
        val contextLimit = windowMetadata.contextTokens.takeIf { it > 0 } ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        // 触发阈值百分比由「偏好设置 → 模型」配置（默认 90，见 GeneralSettingsRepository）。
        // 自适应下调：距上次成功压缩增长超过窗口 15% 的会话（工具输出密集型）额外下调 15 个百分点
        // 提前压；固定 90% 追不上增长时压缩点会一路扬升（实测 136k→199k→224k）。
        // 下调**只降不升**，策略与判据集中在 [CompactionThreshold]（含回归用例）。
        val basePercent = generalSettingsRepository.compactionThresholdPercent()
        val lastCompactedSize = sessionId?.let { lastCompactionSizeBySession[it] } ?: 0
        // 口径统一：真实 usage（含 system prompt + tools）优先，本地估算只在取不到真实值时使用。
        // 增长速率与触发判断必须用**同一个** currentTokens，否则「账本记 A、触发比 B」
        // 会让 fastGrowth 长期漏判（本地估算不含 system+tools，系统性偏小 → 增长幅度被低估
        // → 阈值不下调 → 128k 下钉死在 90% 最坏点）。账本写入侧（见下方 lastCompactionSize
        // 与 updateLastInputTokens）也已同步统一，保证两端可比。
        val currentTokens = lastInputTokens.takeIf { it > 0 } ?: estimatedTokens
        val fastGrowth = CompactionThreshold.isFastGrowth(lastCompactedSize, currentTokens, contextLimit)
        val triggerThreshold = CompactionThreshold.triggerTokens(contextLimit, basePercent, fastGrowth)
        val reachedThreshold = currentTokens >= triggerThreshold
        val reachedHardLimit = currentTokens >= contextLimit
        if (messages.size <= 2 || (!force && !reachedThreshold && !reachedHardLimit)) {
            return messages
        }

        val tokensSource = if (lastInputTokens > 0) "真实 usage" else "本地估算"
        // 窗口来源一并打出来：命中目录（含命中的 provider 与自定义覆盖）还是走了 128k 兜底，
        // 是排查「压缩时机与预期不符」的第一手依据。
        val windowSource = if (windowMetadata.contextTokens > 0) "目录 ${windowMetadata.providerId}" else "128k 兜底"
        FileLogger.i(
            TAG,
            "会话 ${sessionId ?: "-"} 上下文约 $currentTokens tokens（$tokensSource），窗口 $contextLimit（$windowSource），" +
                "${if (force) "手动强制压缩" else "达到压缩触发条件（阈值 $triggerThreshold 或硬上限），触发自动压缩"}。"
        )
        onEvent(AgentEvent.CompactionStarted(currentTokens))

        // 拆分 Head（需要压缩的老数据）和 Tail（保留的新数据）
        // tail 预算受「摘要窗口」反推的下限约束：head = 阈值 − tail 必须装得进摘要输入窗口，
        // 否则会静默丢掉最旧历史（128k 主窗口 + 128k 摘要模型下 head≈95k > 预算 89.6k，每轮都丢）。
        // 详见 ModelContextPolicy.tailBudgetPlan。
        val summaryWindowTokensForSplit = summaryMetadata.contextTokens.takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        // 预算只算一次，供选择器与下方守卫**共用同一数**：两处若各算各的（历史上一次
        // 选择器用 tailBudget、守卫用更小的 preserveRecentTokens），会把「全装得下＝无需压缩」
        // 误判成「划分失败」并强制折叠全部历史。
        val budgetPlan = ModelContextPolicy.tailBudgetPlan(triggerThreshold, contextLimit, summaryWindowTokensForSplit)
        if (!budgetPlan.feasible) {
            FileLogger.w(
                TAG,
                "压缩预算不可行：${budgetPlan.reason}（触发阈值 $triggerThreshold，摘要模型窗口 $summaryWindowTokensForSplit）。" +
                    "本次压缩仍执行，但被折叠的 head 可能超出摘要窗口而被丢弃——" +
                    "建议把「压缩专用模型」换为窗口 ≥ ${ModelContextPolicy.minSummaryWindowForFeasibility()} 的模型。"
            )
        }
        val tailBudget = budgetPlan.tail
        var splitIndex = CompactionTailSelector.compute(messages, tailBudget) { estimateTokens(it) }
        if (force && splitIndex <= 0 && messages.size > 1) {
            splitIndex = messages.size - 1
        }
        if (splitIndex <= 0) {
            // 成因必须分开记，否则误诊：
            // ① 消息本地估算总量 <= tail 预算 → 确实无历史可压（正当跳过）；
            // ② 总量 > 预算却仍归零 → 划分逻辑有缺陷（如恢复段保护把回溯拉到 0）。
            // 判据与选择器同用 [tailBudget]：口径一致才能区分「全装得下」与「真异常」。
            // 降级（②）打 WARN 后按最小粒度强制拆一次，避免「划分 bug → 压缩彻底停摆」。
            //
            // **口径护栏（2026-10-12 死循环修复）**：触发认的是真实 usage（含 system+tools+
            // 记忆+图片），守卫比的是 messages 本地估算。两者背离时（est=43K vs usage=241K，
            // 差额主要是图片/固定开销），「est <= 预算」不再代表「真的没东西可折」——
            // usage 已越过阈值本身就是要折的铁证。此时必须降级强折，绝不能跳过：
            // 跳过 = usage 不降 = 下一轮再触发 = 每轮白烧一次摘要调用（实测 6 分钟 5 次）。
            val usageSaysOverBudget = !force && currentTokens >= triggerThreshold
            if ((estimatedTokens > tailBudget || usageSaysOverBudget) && messages.size > 1) {
                FileLogger.w(
                    TAG,
                    "压缩降级：拆分点落在最前端（消息估算 ${estimatedTokens} vs tail 预算 ${tailBudget}，" +
                        "真实 usage ${currentTokens} vs 触发阈值 ${triggerThreshold}，${messages.size} 条）——" +
                        "按最小 tail 预算强制拆分" +
                        "${if (usageSaysOverBudget && estimatedTokens <= tailBudget) "（估算口径低于预算但真实 usage 越阈：差额多为图片/固定开销，不可跳过）" else ""}"
                )
                splitIndex = adjustSplitIndex(messages, messages.size - 1)
            } else {
                FileLogger.i(
                    TAG,
                    "压缩跳过：消息估算 ${estimatedTokens} <= tail 预算 ${tailBudget}（${messages.size} 条），" +
                        "全部历史均需保留，无可压缩内容"
                )
                onEvent(AgentEvent.CompactionFinished)
                return messages
            }
        }

        // 确保 tail 的第一条消息不是孤立的 ToolResultMessage：
        // 如果 tail 以 ToolResultMessage 开头，需要向前回溯到其配对的 AssistantMessage(with toolCalls)，
        // 否则压缩后摘要 assistant 消息不含 toolCalls，导致 tool 消息变成孤立的，API 报 400。
        splitIndex = adjustSplitIndex(messages, splitIndex)

        val head = messages.subList(0, splitIndex)
        val tail = messages.subList(splitIndex, messages.size)
        val previousSummary = extractPreviousSummary(messages)
        val summaryWindowTokens = summaryMetadata.contextTokens.takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        // 摘要输入预算：head 必须装得进「摘要模型窗口 × 70%」。装不下时旧实现直接
        // 从最旧处截断丢弃（内容不进摘要 = 静默失忆）。这里改为**优先压缩材料**再截断：
        // trimLeadingForCompaction 会把超长工具输出截到 2000 字符，通常足以把 head 拉回预算内。
        // 先清理再判预算，避免「按未清理的原始 head 判定超限 → 无谓丢历史」。
        val cleanedHead = removeCompactionPairs(head).trimLeadingForCompaction()
        val summaryInputBudget = (summaryWindowTokens * SUMMARY_WINDOW_RESERVE_RATIO).toInt()
        val cleanedHeadTokens = cleanedHead.sumOf { estimateTokens(it) }
        val headForSummary = if (cleanedHeadTokens <= summaryInputBudget) {
            // 清理后已装得下：不丢任何消息。
            CompactionSummaryWindow.Result(cleanedHead)
        } else {
            CompactionSummaryWindow.selectTail(cleanedHead, summaryInputBudget) { estimateTokens(it) }
        }
        val headMessages = headForSummary.kept
        if (headForSummary.hasDrop) {
            // 清理后仍超预算才真的丢（压缩模型窗口远小于主窗口时的固有取舍）。
            // 丢弃量进日志与摘要产物，杜绝「摘要里没有 = 从未发生」的误读。
            FileLogger.w(
                TAG,
                "head 超出压缩模型窗口预算：清理后仍丢弃 ${headForSummary.droppedCount} 条最旧消息" +
                    "（约 ${headForSummary.droppedTokens} token，预算 $summaryInputBudget token）——这部分内容不会进摘要",
            )
        }
        if (headMessages.isEmpty()) {
            // 空只可能来自前两步：重复压缩时 head 只剩旧的 marker+summary 对（被 removeCompactionPairs 删光），
            // 或整体超出摘要窗口预算被截空。
            // 检查放在清理**之后**：清理会改变消息集，必须按最终要发送的内容判空，
            // 否则会发出一条只有指令、没有材料的摘要请求，模型只能凭空编造并落库顶替真实历史。
            FileLogger.i(TAG, "无可压缩内容（head 清理后为空），跳过压缩")
            onEvent(AgentEvent.CompactionFinished)
            return messages
        }
        // 压缩请求：head 原始消息数组 + 末尾一条压缩指令（Codex 式），tools 不发送。
        // 消息数组保留真实角色结构（user/assistant/tool 配对），比文本化拼接更利于模型理解。
        val summaryRequestMessages = headMessages + listOf(
            AgentMessage.UserMessage(content = buildSummaryInstruction(previousSummary, summaryWindowTokens))
        )

        // 调用统计埋点：压缩也是一次真实 LLM 调用（独立于主循环，kind=compaction）。
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callUsage: AIResponse? = null

        val summaryResponse = try {
            // 压缩是同一次请求拿全量摘要（非流式），推理档位从「压缩模型」设置单独读取：
            // 取不到（空串）则不传，走服务端默认。对 glm-5.3 这类强制思考的模型，
            // 适配器会把 none/minimal 归一到最低合法档（low），避免思考耗尽输出预算、摘要为空。
            val compactionEffort = runCatchingCancellable {
                compactionModelSettingsRepository.getCompactionReasoningEffort()
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val response = aiProvider.complete(
                systemPrompt = "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。不要输出任何思考、推理或分析过程（不要 reasoning_content），直接从摘要正文开始。",
                messages = summaryRequestMessages,
                tools = emptyList(),
                reasoningEffort = compactionEffort,
                // 摘要请求是一次性的、不会被后续请求复用，禁用显式缓存断点以免白付缓存写入费。
                disablePromptCaching = true
            )
            callUsage = response
            response.content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            FileLogger.e(TAG, "压缩上下文失败", e)
            // 临时性失败（网关 503/限流/连接拒绝等）置 transient，调用方不应因此关停本轮后续压缩尝试；
            // 确定性失败（上下文超限/鉴权/输出预算/图片/请求非法）才值得关停——重试也是白烧。
            // 特别是 INVALID_REQUEST（上游拒收未知字段、Base URL 配错返回网页）：重发同一请求必然再失败，
            // 若归 transient 就会变成每轮 LLM 调用前都原地重试一次（实测单会话十分钟内重试了 10 次）。
            val transient = when (ProviderFailureTaxonomy.classify(e)) {
                ProviderFailureKind.CONTEXT_OVERFLOW,
                ProviderFailureKind.AUTH_FAILED,
                ProviderFailureKind.INVALID_OUTPUT_BUDGET,
                ProviderFailureKind.UNSUPPORTED_VISION,
                ProviderFailureKind.INVALID_REQUEST -> false
                ProviderFailureKind.RATE_LIMITED,
                ProviderFailureKind.UNKNOWN -> true
            }
            // 异常路径**刻意不吃** failCompaction 的退避/熔断：这里已有更细的 transient 分类，
            // 临时失败（限流/网关抖动）本就该下轮再试，与「确定性失败才熔断」的语义相反。
            // 异常多为快速失败（成本远低于输出打满那次），不属「慢性烧钱」场景。
            onEvent(AgentEvent.CompactionFailed(callError, transient))
            onEvent(AgentEvent.CompactionFinished)
            return messages // 失败则原样返回，交由上层自行承担溢出风险
        }

        // 空摘要/截断保护：思考型压缩模型（如 glm-5.3）可能把输出预算全花在推理上（reasoning_content），
        // 正文为空或被打满截断。照常持久化会把会话上下文替换成空摘要，历史静默丢失
        // （实测：13:50 压缩 48000 output tokens 全耗在推理、content=""，摘要长度 0 照样入库并替换上下文）。
        // 截断判定复用 [AIResponse.isTruncated] 的同一口径（TRUNCATION_STOP_REASONS 已含
        // OpenAI `length` / Anthropic `max_tokens` / Gemini `MAX_TOKENS`）——不能只认 "length"，
        // 否则压缩模型配成 Anthropic/Gemini 时，被截断的**半截非空摘要**会绕过保护入库替换上下文。
        // 失败则保留原上下文并进入冷却，防下一轮原地重试死循环。
        var summaryTextRaw = summaryResponse.trim()
        var summaryTruncated = callUsage?.isTruncated == true
        if (summaryTextRaw.isEmpty() || summaryTruncated) {
            // 诊断：记录首次失败形态。若 output 有量但正文空、且 reasoning 非空，
            // 即「预算全耗在推理」——这是强制思考模型空摘要的判别特征（供未来日志直接定位）。
            FileLogger.w(
                TAG,
                "摘要首次不可用：正文长 ${summaryTextRaw.length}、stopReason=${callUsage?.stopReason}、" +
                    "output ${callUsage?.outputTokens ?: 0} tokens、reasoning 长 ${callUsage?.reasoning?.length ?: 0}——" +
                    "${if (!callUsage?.reasoning.isNullOrEmpty()) "疑为推理占满输出预算（强制思考模型）" else "未产出正文"}，尝试最低档补救"
            )
            // 一次性补救：思考型压缩模型把输出预算耗在推理上是**用户可见「压缩失败」的主因**
            // （128k 阈值高、head 大、摘要请求更长，思考模型更易打满）。
            // 关键：必须传**该 provider 真正的最低合法档**（见 [minimalEffortFor]）——
            // 统一传 "minimal" 在 OpenAI 路径会被归一为「不发字段」，重试请求与首次逐字节相同=空操作
            // （实测 2026-10-12：4 次失败的重试无一成功，即此故）。
            val retryEffort = minimalEffortFor(aiProvider)
            val retryStartElapsed = SystemClock.elapsedRealtime()
            val retryStartWall = System.currentTimeMillis()
            var retryError: String? = null
            val retry = try {
                aiProvider.complete(
                    systemPrompt = COMPACTION_SYSTEM_PROMPT,
                    messages = summaryRequestMessages,
                    tools = emptyList(),
                    reasoningEffort = retryEffort,
                    disablePromptCaching = true
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                retryError = e.message ?: e.javaClass.simpleName
                null
            }
            // 补救调用同样要入账：它是真实成本，漏记会让成本审计看不见「压缩一度失败」的真实开销。
            runCatchingCancellable {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = aiProvider.providerId.ifBlank { null },
                        model = aiProvider.model,
                        kind = "compaction_retry",
                        inputTokens = retry?.inputTokens ?: 0,
                        outputTokens = retry?.outputTokens ?: 0,
                        cachedInputTokens = retry?.cachedInputTokens ?: 0,
                        cacheCreationTokens = retry?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = (SystemClock.elapsedRealtime() - retryStartElapsed).toInt(),
                        status = if (retry != null) "success" else "error",
                        errorMessage = retryError,
                        stopReason = retry?.stopReason,
                        createdAt = retryStartWall
                    )
                )
            }
            val retryText = retry?.content?.trim().orEmpty()
            if (retry != null && retryText.isNotEmpty() && retry.isTruncated != true) {
                FileLogger.i(TAG, "摘要首次为空/截断，以最低推理档「$retryEffort」重试成功（output ${retry.outputTokens} tokens）")
                summaryTextRaw = retryText
                summaryTruncated = false
                callUsage = retry
            } else {
                FileLogger.w(
                    TAG,
                    "摘要最低档「$retryEffort」补救仍为空/截断（output ${retry?.outputTokens ?: 0} tokens，" +
                        "reasoning 长 ${retry?.reasoning?.length ?: 0}）——该压缩模型疑为强制思考且关不掉，建议更换"
                )
            }
        }
        // 调用统计埋点移到补救之后：记录**最终生效**的调用（重试成功记重试那次，
        // 否则记首次失败那次）。原先在补救前落库，会把重试的 token/时长记成 0 且状态错位。
        runCatchingCancellable {
            llmCallRecordDao.insert(
                LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = aiProvider.providerId.ifBlank { null },
                    model = aiProvider.model,
                    kind = "compaction",
                    inputTokens = callUsage?.inputTokens ?: 0,
                    outputTokens = callUsage?.outputTokens ?: 0,
                    cachedInputTokens = callUsage?.cachedInputTokens ?: 0,
                    cacheCreationTokens = callUsage?.cacheCreationTokens ?: 0,
                    ttfbMillis = null,
                    durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt(),
                    // 成功 ⟺ 最终拿到了可用摘要（首次或补救）；否则为 error（含被截断/空正文）。
                    status = if (summaryTextRaw.isNotEmpty() && !summaryTruncated) "success" else "error",
                    errorMessage = callError,
                    stopReason = callUsage?.stopReason,
                    createdAt = callStartWall
                )
            )
        }
        if (summaryTextRaw.isEmpty() || summaryTruncated) {
            return failCompaction(
                messages, sessionId, "empty_or_truncated_summary",
                "摘要正文为空或输出被截断(stopReason=${callUsage?.stopReason})",
                callUsage?.outputTokens ?: 0, force, onEvent
            )
        }
        // 成功即清零连续失败计数与残余冷却（熔断复位）。
        // 冷却也要清：否则失败进入 60s 冷却后，手动压缩成功紧接着的自动压缩仍被残余冷却拦一次。
        sessionId?.let {
            compactionFailStreakBySession.remove(it)
            compactionCooldownUntilBySession.remove(it)
        }
        // 文件清单不再由代码机械提取附加（原 CompactionFileTracker）：它只认 readFile/writeFile/
        // editFile 的 path 参数，实测对本项目真实会话的路径覆盖率仅 0~17%（大量路径出现在
        // search/Bash 的输出里提取不到），而附加的清单块会常驻上下文。改由摘要提示词要求模型
        // 在「涉及的文件」小节自行逐条列出。
        // 凭据脱敏：摘要是唯一「模型生成 → 直接落库 → 每轮回放」的文本。
        //
        // 工具输出侧由 ToolOutputStore 前置 scrub（ToolOutputStore.kt），但本链路
        // 完全独立于它——全仓检索确认 ContextCompactor 与压缩 DAO 路径上没有任何
        // 脱敏调用点。未过 ToolOutputStore 的凭据（用户直接粘贴的 key、日志类工具
        // 输出、脱敏正则未覆盖的自定义 token 格式）会经摘要誊抄后被永久持久化。
        //
        // 而 compact-summary.md 反而要求「标识符原文保留」，进一步把这些明文固化
        // 进摘要。
        //
        // 在此统一收口：summaryText 的全部下游（落库、内存回放）均取自本变量，
        // 一处过滤即全覆盖。
        val scrubbedSummary = ToolOutputScrubber.scrub(summaryTextRaw)
        // B1 修复：摘要长度封顶。摘要是常驻回放文本，无上限会单调膨胀到 ≈ 触发阈值，
        // 使压缩后立即再触发（实测 42 次/日、13k→105k 字符、r1=0.889 的反馈环）。
        // 裁剪保头（任务状态）+ 保尾（下一步动作）、折叠中段，见 [SummaryTrimmer]。
        val summaryTokenCap = ModelContextPolicy.maxSummaryTokens(summaryWindowTokens)
        val summaryText = capSummaryLength(scrubbedSummary, summaryTokenCap)
        // 时点标注：摘要只总结折叠边界前的历史，与保留段（边界前最后几轮原文）拼接时状态可能矛盾，
        // 接手者需要知道哪个更新（实测 cd700c44 会话：轻量模型需专门取证才能分辨）。
        val stampedSummary = summaryTimestampNotice(
            java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ) + summaryText
        FileLogger.i(
            TAG,
            "上下文压缩完成，摘要长度：${stampedSummary.length}" +
                if (summaryText.length < scrubbedSummary.length) {
                    "（原始 ${scrubbedSummary.length}，已按上限 $summaryTokenCap token 裁剪）"
                } else ""
        )

        val markerId = UUID.randomUUID().toString()
        val compactedId = UUID.randomUUID().toString()
        // 块 id：head 标记、marker、summary 三者共持，供 restoreCompactedRange 整体翻转。
        // 覆盖式语义：后续压缩若把本块消息吸进新 head，会写入新块 id 接管——
        // 恢复旧块只回它仍持有的那部分，与最新摘要不冲突。
        val blockId = UUID.randomUUID().toString()
        val markerMessage = AgentMessage.UserMessage(
            id = markerId,
            content = CONTEXT_COMPACTION_MARKER
        )
        val compactedMessage = AgentMessage.AssistantMessage(
            id = compactedId,
            content = summaryText,
            toolCalls = emptyList()
        )

        // 事件驱动：把「本块可恢复」作为事实写进摘要尾部（而非静态工具描述），
        // 模型每轮回放都看得到这个块 id，需要细节时自然会去调 restoreCompactedRange。
        // 与工具 schema description 互补：description 说「什么时候该用」，这里说「现在有块可用」。
        //
        // 若本次因压缩模型窗口不足而丢了最旧的一段，同样写进产物：否则接手方
        // 会把「摘要里没有」当成「从未发生」，而实际是「发生但未纳入摘要」——两者后果完全不同。
        val droppedNotice = headForSummary.notice()
        // B6 修复：提示文案**不内嵌随机 blockId**。原实现把块 id 的 8 位随机前缀写进文案，
        // 该前缀每次压缩都随机 → 即使摘要正文一字不变，回放前缀也必变 → prompt cache 每次压缩
        // 都要整段重建（实测 48 次/日、每次丢 ~79k token 全价重算）。模型并不需要字面 id：
        // restoreCompactedRange 的 blocks 模式可列出块、preview 模式可按内容检索，均无需预知 id。
        val compactedMessageWithHint = compactedMessage.copy(
            content = stampedSummary + droppedNotice + "\n\n---\n> 本压缩块含被折叠的早期消息原文，" +
                "如摘要缺关键细节（报错原文、代码片段、精确数值）：先用 restoreCompactedRange 的 preview 模式" +
                "检索定位（返回截断预览，省 token），确认需要完整原文再以 action=restore 恢复整块；" +
                "不要为复核上下文而无谓恢复。"
        )

        // 持久化压缩结果到数据库
        if (sessionId != null) {
            try {
                val dbEntities = agentMessageDao.getMessagesBySessionOnce(sessionId
                )
                // 按 id 精确标记 head（而不是靠 tail 首条的时间戳）：
                // 旧实现以 tail 首条是否已落库决定能否写标记，遇持久化竞态（tail 首条尚未落库）
                // 就整个跳过标记，导致 head 的 isCompacted 保持 0，下个轮次 buildHistory
                // 把整段已压缩历史原样读回——压缩花了摘要的钱却没能缩短上下文
                // （实测同一会话 231677 → 239446 → 327335 tokens 不降反升）。
                // head 的消息绝大多数早已落库，故仍只标记 DB 中确实存在的 id，
                // 既消除竞态，也保留「绝不误标未落库/全部历史」的原设计初衷。
                val persistedIds = dbEntities.mapTo(HashSet()) { it.id }
                // tool 结果行落库 id 是 "tool_<callId>"，而内存 ToolResultMessage 的 id 是 toolCallId 本身
                // （buildHistory 重建时也用 toolCallId）。不并入的话本 run 产生的 tool 结果永远匹配不上，
                // 纯新会话的首个长 run 会误判 head 全部未落库（实测 2026-10-11 会话 22ddb225）。
                dbEntities.forEach { e -> e.toolCallId?.let { persistedIds.add(it) } }
                val headIdsToMark = CompactionMarkSelector.selectIdsToMark(head, persistedIds)
                // head 整段都未落库（进程被杀导致内存消息未持久化，实测 31 次回合未见收尾）：
                // 无法标记 → 重启后 buildHistory 会回放完整 head。若仍插入 marker+summary，
                // 回放结果 = 完整 head + 追加摘要，上下文不降反升（实测 125855 → 261138）。
                // 故整轮折叠放弃：内存也不替换，与 DB 状态保持一致，并记失败与冷却防重试风暴。
                // 判断必须在 withTransaction 之前——withTransaction 不是 inline 函数，
                // 内部的裸 return 无法退到外层函数。
                if (headIdsToMark.isEmpty()) {
                    return failCompaction(
                        messages, sessionId, "head_not_persisted",
                        "head 内无已落库消息（共 ${head.size} 条），折叠无法在重启后生效", 0, force, onEvent
                    )
                }
                // 三步写（标已压缩 + marker + summary）必须原子：若标记成功但摘要未落库，
                // head 会被回放过滤掉（isCompacted）而摘要缺失 → 重启后那段上下文静默消失。
                agentDatabase.withTransaction {
                    // 先接管块归属再标折叠：老压缩块若被本块吸走，其块 id 被覆盖，
                    // 旧块恢复只会回它仍持有的部分（与最新摘要不冲突）。
                    headIdsToMark.chunked(COMPACTED_IDS_CHUNK).forEach { chunk ->
                        agentMessageDao.assignCompactionBlock(chunk, blockId)
                    }
                    // 分块更新：Room 的 IN (...) 会为每个元素生成一个绑定参数，
                    // 超 SQLite 变量上限（旧版 999）会直接报错；压缩型会话的 head 可达数百条。
                    headIdsToMark.chunked(COMPACTED_IDS_CHUNK).forEach { chunk ->
                        agentMessageDao.markMessagesCompactedByIds(chunk)
                    }

                    // 摘要收尾：marker + summary 时间戳放在 tail 最后一条之后，回放/UI 顺序 = tail → 摘要，
                    // 与 Codex 一致（最近消息在前、接手摘要收尾），避免摘要插在历史最前导致观感混乱。
                    val tailLastTs = tail.asReversed().firstNotNullOfOrNull { msg ->
                        // tool 消息的内存 id 是 toolCallId，对应行 id 不同，两者都试。
                        dbEntities.find { it.id == msg.id || it.toolCallId == msg.id }?.timestamp
                    }
                    val insertBase = maxOf(System.currentTimeMillis(), tailLastTs ?: 0L) + 1
                    agentMessageDao.insert(
                        AgentMessageEntity(
                            id = markerId,
                            sessionId = sessionId,
                            role = MessageRole.USER.name,
                            content = CONTEXT_COMPACTION_MARKER,
                            timestamp = insertBase,
                            isCompactionMarker = true,
                            compactionBlockId = blockId
                        )
                    )
                    agentMessageDao.insert(
                        AgentMessageEntity(
                            id = compactedId,
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            content = compactedMessageWithHint.content,
                            timestamp = insertBase + 1,
                            isContextSummary = true,
                            compactionBlockId = blockId
                        )
                    )
                }
                FileLogger.i(TAG, "已持久化压缩结果到数据库，会话 $sessionId")
            } catch (e: Exception) {
                FileLogger.e(TAG, "持久化压缩结果失败", e)
            }
        }
        onEvent(AgentEvent.CompactionFinished)

        val newMessages = mutableListOf<AgentMessage>()
        // Codex 式布局：tail（保留的最近消息）在前，摘要收尾。
        newMessages.addAll(tail)
        newMessages.add(markerMessage)
        newMessages.add(compactedMessageWithHint)

        // 记录压缩后基准，供下次触发判断计算增长速率（自适应阈值）。
        if (sessionId != null) {
            val newSize = estimateTokens(newMessages)
            // 口径对齐：newSize 是**本地估算**（不含 system prompt + tools），而账本另一端
            // currentTokens 优先取真实 usage（含二者）。直接把 newSize 当基准会让两边差一个
            // 固定开销 Δ，fastGrowth 每轮都少算 Δ、长期漏判。
            // 故用「本次触发时的真实 usage − 本次触发时的本地估算」把 Δ 显式带过来：
            // 无真实 usage 时 Δ=0，退化为纯本地估算（与旧行为一致）。
            val usageOverhead = if (lastInputTokens > 0) (lastInputTokens - estimatedTokens).coerceAtLeast(0) else 0
            val ledgerSize = newSize + usageOverhead
            lastCompactionSizeBySession[sessionId] = ledgerSize
            // 死循环修复：压缩成功后立即把会话的 lastInputTokens 写回压缩后大小。
            // 否则旧大值（如 1M 窗口时代记录的 27 万）恒超 128k 阈值，每轮都触发压缩。
            // 同样带上 Δ，使写回值与下一轮真实 usage 同口径、不再来回震荡。
            runCatchingCancellable {
                chatSessionDao.updateLastInputTokens(sessionId, ledgerSize)
            }
        }

        return newMessages
    }

    private suspend fun failCompaction(
        messages: List<AgentMessage>,
        sessionId: String?,
        code: String,
        reason: String,
        outputTokens: Int,
        force: Boolean,
        onEvent: suspend (AgentEvent) -> Unit
    ): List<AgentMessage> {
        FileLogger.e(TAG, "压缩失败：$reason（output $outputTokens tokens），保留原上下文")
        // force 失败不累加熔断计数也不设冷却：手动触发/溢出自救失败不该污染自动压缩的熔断状态
        // （否则用户手动连点几次，就把自动压缩提前熔断了）。
        if (sessionId != null && !force) {
            val streak = (compactionFailStreakBySession[sessionId] ?: 0) + 1
            compactionFailStreakBySession[sessionId] = streak
            // 线性递增退避：第 1 次 60s、第 2 次 120s、第 3 次 180s；
            // 连续达到上限后转长期冷却，停到成功或手动压缩为止（防 60s 一次的慢性成本）。
            // 注意用有限常量而非 Long.MAX_VALUE：后者与 elapsedRealtime 相加会溢出成负数，
            // 反而使「冷却中」判断恒为 false、熔断形同虚设。
            val backoff = if (streak >= COMPACTION_FAIL_MAX_STREAK) COMPACTION_FAIL_STOP_MS
            else COMPACTION_FAIL_COOLDOWN_MS * streak
            compactionCooldownUntilBySession[sessionId] = SystemClock.elapsedRealtime() + backoff
            if (streak >= COMPACTION_FAIL_MAX_STREAK) {
                FileLogger.w(TAG, "压缩连续失败 $streak 次，已停止自动重试（手动 /compress 仍可触发）")
            }
        }
        onEvent(AgentEvent.CompactionFailed(code, transient = false))
        onEvent(AgentEvent.CompactionFinished)
        return messages
    }

    /**
     * 调整拆分索引，确保 tail 的起点既不会劈开 tool 调用配对，也不会劈开压缩 marker/summary 对。
     *
     * 规则与理由集中在纯逻辑 [CompactionSplitAdjuster]（含回归用例），此处只做委托。
     */
    private fun adjustSplitIndex(messages: List<AgentMessage>, initialSplitIndex: Int): Int =
        CompactionSplitAdjuster.adjust(messages, initialSplitIndex)

    private fun estimateTokens(messages: List<AgentMessage>): Int =
        messages.sumOf { estimateTokens(it) }

    /**
     * 单条消息的 token 估算：文本 + **图片**。
     *
     * 图片必须计入：视觉模型按瓦片计费，一张图几千 token，而文本口径下它是 0。
     * 实测（2026-10-12 会话 f0947716）：一晚截几十张图后真实 usage=241K、
     * 纯文本估算仅 43K，压缩器据此判「无可压缩内容」跳过 → 立即再触发 → 死循环。
     */
    private fun estimateTokens(message: AgentMessage): Int =
        ModelContextPolicy.estimateTokens(messageText(message)) +
            ModelContextPolicy.estimateImageTokens(imageCountOf(message))

    /** 消息携带的图片数（user 附件 + 工具结果图）。 */
    private fun imageCountOf(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage -> message.images.size
        is AgentMessage.AssistantMessage -> message.images.size
        is AgentMessage.ToolResultMessage -> message.images.size
    }

    /**
     * 聚合一条消息的**文本**（供 CJK 感知的 token 估算与窗口/字符预算）。
     */
    private fun messageText(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content
        is AgentMessage.AssistantMessage -> buildString {
            append(message.content)
            append(message.reasoning)
            message.toolCalls.forEach { append(it.name).append(it.arguments.toString()) }
        }
        is AgentMessage.ToolResultMessage -> message.toolName + message.result
    }

    private fun inferProviderType(aiProvider: AIProvider): ProviderType {
        val className = aiProvider::class.simpleName.orEmpty()
        return when {
            "Anthropic" in className -> ProviderType.ANTHROPIC
            "Gemini" in className -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }

    /**
     * 该 provider 的**真正最低推理档**（供空摘要补救用）。
     *
     * 不能统一传 "minimal"：`OpenAIAdapter.normalizeReasoningEffort` 把 "minimal"/"none" 归一为 null
     * = 不发该字段，于是「最小档重试」与首次请求完全相同，对强制思考模型是空操作。
     * 各 provider 的真值：OpenAI 官方最低 "low"；Anthropic 无 minimal 档、归一到 "low"；
     * Gemini 支持 "minimal" 原样透传。
     */
    private fun minimalEffortFor(aiProvider: AIProvider): String = when (inferProviderType(aiProvider)) {
        ProviderType.ANTHROPIC -> REASONING_EFFORT_LOWEST_ANTHROPIC
        ProviderType.GEMINI -> REASONING_EFFORT_LOWEST_GEMINI
        else -> REASONING_EFFORT_LOWEST_OPENAI
    }

    private fun buildSummaryInstruction(previousSummary: String?, summaryWindowTokens: Int): String {
        // B1 修复：旧摘要回炉时同样封顶。否则「更新已有摘要」会把膨胀的旧摘要整份带进输入，
        // 模型在提示词要求下继续扩写，形成单调增长（实测 r1=0.889 的反馈环）。
        val cappedPrev = previousSummary?.let {
            capSummaryLength(it, ModelContextPolicy.maxSummaryTokens(summaryWindowTokens))
        }
        val instruction = if (cappedPrev.isNullOrBlank()) {
            "请根据下面的对话历史创建一个新的锚定摘要。"
        } else {
            """
                请根据下面的新对话历史更新已有锚定摘要。
                保留仍然正确的信息，移除过时信息，并合并新事实。
                输出必须精炼：只保留接手者继续任务所必需的事实，**总长度不得超过 $summaryWindowTokens 窗口的 20%**。

                <previous-summary>
                $cappedPrev
                </previous-summary>
            """.trimIndent()
        }

        return systemPromptProvider.resolvePrompt(COMPACT_PROMPT_FILE)
            .replace(LEADING_COMMENT, "")
            .replace("{{INSTRUCTION}}", instruction)
    }

    /**
     * 压缩请求前的清理：头部非 user 段折叠成一条 user 消息，并去掉图片与超长工具输出。
     *
     * 折叠规则与理由见 [CompactionHeadFolder]（该处含「丢光则永久失忆」的实测背景）。
     */
    private fun List<AgentMessage>.trimLeadingForCompaction(): List<AgentMessage> {
        val leading = takeWhile { it !is AgentMessage.UserMessage }
        val rest = drop(leading.size)
        val folded = CompactionHeadFolder.fold(leading)
        val withHead = if (folded == null) rest else listOf(folded) + rest
        return withHead.map { msg ->
            when {
                msg is AgentMessage.UserMessage && msg.images.isNotEmpty() -> msg.copy(images = emptyList())
                msg is AgentMessage.ToolResultMessage && msg.result.length > TOOL_OUTPUT_MAX_CHARS ->
                    msg.copy(result = msg.result.truncateForSummary())
                else -> msg
            }
        }
    }

    private fun extractPreviousSummary(messages: List<AgentMessage>): String? {
        for (index in messages.indices.reversed()) {
            val current = messages[index]
            val next = messages.getOrNull(index + 1)
            if (
                current is AgentMessage.UserMessage &&
                current.content == CONTEXT_COMPACTION_MARKER &&
                next is AgentMessage.AssistantMessage
            ) {
                return next.content.cleanSummary()
            }
            if (
                current is AgentMessage.AssistantMessage &&
                current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)
            ) {
                return current.content.cleanSummary()
            }
        }
        return null
    }

    private fun removeCompactionPairs(messages: List<AgentMessage>): List<AgentMessage> {
        val result = mutableListOf<AgentMessage>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            val next = messages.getOrNull(index + 1)
            if (
                current is AgentMessage.UserMessage &&
                current.content == CONTEXT_COMPACTION_MARKER &&
                next is AgentMessage.AssistantMessage
            ) {
                index += 2
                continue
            }
            if (
                current is AgentMessage.AssistantMessage &&
                current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)
            ) {
                index++
                continue
            }
            result.add(current)
            index++
        }
        return result
    }

    private fun String.cleanSummary(): String {
        // 尾部「本压缩块」提示是写给接手模型的操作指引（需要细节时调 restoreCompactedRange），
        // 不是摘要内容——滚动压缩时若原样进 previous-summary，摘要模型可能把它当旧内容复读。
        // 丢弃注记（droppedNotice）位于块提示之前、不在锚点内，保留（滚动更新需知道更早历史未进摘要）。
        // B6 修复后块提示不再含随机 id，锚点随之简化为不含 id 的稳定前缀。
        val blockHintAnchor = "\n\n---\n> 本压缩块"
        // 取最后一次出现：正文若逐字复读含锚点（模型复读旧尾注是真实行为），只剥真正的尾部提示；
        // previous-summary 里正文中残留的复读提示文本是有意保留（无法也不应区分，脚本侧已钉住）。
        val withoutHint = if (contains(blockHintAnchor)) {
            substringBeforeLast(blockHintAnchor).trimEnd()
        } else this
        return withoutHint
            .removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX)
            .let(::stripSummaryTimestampNotice)
            .trimStart()
    }

    private fun String.truncateForSummary(): String {
        if (length <= TOOL_OUTPUT_MAX_CHARS) return this
        return take(TOOL_OUTPUT_MAX_CHARS) + "\n[Tool output truncated for compaction]"
    }

    /**
     * B1 修复：把摘要裁到不超过 [maxTokens]（按 CJK 感知的 [ModelContextPolicy.estimateTokens] 复核）。
     *
     * 先用「字符/token ≈ 1.8」给出保守字符预算（CJK 摘要最坏情形），再交给 [SummaryTrimmer]
     * 做「保头保尾、折叠中段」裁剪，最后按真实估算复核——若仍超，用二分退到安全字符数。
     * 纯逻辑在 [SummaryTrimmer]，此处只负责「token ↔ 字符」的口径换算。
     */
    private fun capSummaryLength(summary: String, maxTokens: Int): String {
        if (maxTokens <= 0) return summary
        if (ModelContextPolicy.estimateTokens(summary) <= maxTokens) return summary
        // 保守起步：按 1.8 字符/token（CJK 最坏）折算字符预算；再二分收缩直到估算不超上限。
        var lo = 1
        var hi = minOf(summary.length, (maxTokens * 2).coerceAtLeast(1))
        var best = SummaryTrimmer.trim(summary, hi)
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val candidate = SummaryTrimmer.trim(summary, mid)
            if (ModelContextPolicy.estimateTokens(candidate) <= maxTokens) {
                best = candidate
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

}

