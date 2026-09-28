package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.ProviderFailureKind
import com.aicode.feature.agent.domain.provider.ProviderFailureTaxonomy
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.data.remote.ModelMetadataService
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

@Singleton
class ContextCompactor @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase,
    private val modelMetadataService: ModelMetadataService,
    private val systemPromptProvider: SystemPromptProvider,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val generalSettingsRepository: GeneralSettingsRepository
) {

    /** 会话上次成功压缩后的消息估算 token 数（自适应阈值的增长基准）。会话级内存态即可，丢了退回固定阈值。 */
    private val lastCompactionSizeBySession = ConcurrentHashMap<String, Int>()

    private companion object {
        const val TAG = "ContextCompactor"

        const val TOOL_OUTPUT_MAX_CHARS = 2_000
        const val COMPACT_PROMPT_FILE = "agent/compact-summary.md"

        /** 摘要窗口预留比例：30% 留给摘要提示词与旧摘要，故可用 70%。 */
        const val SUMMARY_WINDOW_RESERVE_RATIO = 0.7f
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
        // 自适应下调：距上次成功压缩增长超过窗口 15% 的会话（工具输出密集型），阈值降 15 个百分点
        // 提前压；固定 90% 追不上增长时压缩点会一路扬升（实测 136k→199k→224k）。下限 60%。
        val basePercent = generalSettingsRepository.compactionThresholdPercent()
        val lastCompactedSize = sessionId?.let { lastCompactionSizeBySession[it] } ?: 0
        val fastGrowth = lastCompactedSize > 0 && estimatedTokens - lastCompactedSize > contextLimit * 0.15
        val effectivePercent = if (fastGrowth) (basePercent - 15).coerceAtLeast(60) else basePercent
        val triggerThreshold = (contextLimit * effectivePercent / 100.0).toInt()
        // 真实 usage 优先（含 system prompt + tools，与上下文窗口同口径）；取不到（0）回退本地估算
        val currentTokens = lastInputTokens.takeIf { it > 0 } ?: estimatedTokens
        val reachedThreshold = currentTokens >= triggerThreshold
        val reachedHardLimit = currentTokens >= contextLimit
        if (messages.size <= 2 || (!force && !reachedThreshold && !reachedHardLimit)) {
            return messages.toList()
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
        var splitIndex = selectTailStartIndex(messages, triggerThreshold)
        if (force && splitIndex <= 0 && messages.size > 1) {
            splitIndex = messages.size - 1
        }
        if (splitIndex <= 0) {
            onEvent(AgentEvent.CompactionFinished)
            return messages.toList()
        }

        // 确保 tail 的第一条消息不是孤立的 ToolResultMessage：
        // 如果 tail 以 ToolResultMessage 开头，需要向前回溯到其配对的 AssistantMessage(with toolCalls)，
        // 否则压缩后摘要 assistant 消息不含 toolCalls，导致 tool 消息变成孤立的，API 报 400。
        splitIndex = adjustSplitIndex(messages, splitIndex)

        val head = messages.subList(0, splitIndex)
        val tail = messages.subList(splitIndex, messages.size)
        val previousSummary = extractPreviousSummary(messages)
        // 文件清单在移除旧摘要配对之前提取，否则上一轮累积的清单会随配对一起丢掉。
        val fileOps = CompactionFileTracker.extract(messages)
        val summaryWindowTokens = summaryMetadata.contextTokens.takeIf { it > 0 }
            ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        val headForSummary = removeCompactionPairs(head).truncateForSummaryWindow(summaryWindowTokens)
        if (headForSummary.isEmpty()) {
            // 重复压缩时 head 可能只剩旧的 marker+summary 对，删光后无可压缩内容，跳过本轮压缩。
            FileLogger.i(TAG, "无可压缩内容（head 为空），跳过压缩")
            onEvent(AgentEvent.CompactionFinished)
            return messages.toList()
        }
        // 压缩请求：head 原始消息数组 + 末尾一条压缩指令（Codex 式），tools 不发送。
        // 消息数组保留真实角色结构（user/assistant/tool 配对），比文本化拼接更利于模型理解。
        val summaryRequestMessages = headForSummary.trimLeadingForCompaction() + listOf(
            AgentMessage.UserMessage(content = buildSummaryInstruction(previousSummary))
        )

        // 调用统计埋点：压缩也是一次真实 LLM 调用（独立于主循环，kind=compaction）。
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callCompleted = false
        var callUsage: AIResponse? = null

        val summaryResponse = try {
            val response = aiProvider.complete(
                systemPrompt = "你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。",
                messages = summaryRequestMessages,
                tools = emptyList(),
                // 摘要请求是一次性的、不会被后续请求复用，禁用显式缓存断点以免白付缓存写入费。
                disablePromptCaching = true
            )
            callUsage = response
            callCompleted = true
            response.content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            FileLogger.e(TAG, "压缩上下文失败", e)
            // 临时性失败（网关 503/限流/连接拒绝等）置 transient，调用方不应因此关停本轮后续压缩尝试；
            // 确定性失败（上下文超限/鉴权/输出预算/图片）才值得关停——重试也是白烧。
            val transient = when (ProviderFailureTaxonomy.classify(e)) {
                ProviderFailureKind.CONTEXT_OVERFLOW,
                ProviderFailureKind.AUTH_FAILED,
                ProviderFailureKind.INVALID_OUTPUT_BUDGET,
                ProviderFailureKind.UNSUPPORTED_VISION -> false
                ProviderFailureKind.RATE_LIMITED,
                ProviderFailureKind.UNKNOWN -> true
            }
            onEvent(AgentEvent.CompactionFailed(callError, transient))
            onEvent(AgentEvent.CompactionFinished)
            return messages.toList() // 失败则原样返回，交由上层自行承担溢出风险
        }

        val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
        runCatching {
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
                    durationMillis = durationMillis,
                    status = if (callCompleted) "success" else "error",
                    errorMessage = callError,
                    stopReason = callUsage?.stopReason,
                    createdAt = callStartWall
                )
            )
        }

        val summaryText = CompactionFileTracker.append(summaryResponse, fileOps)
        if (!fileOps.isEmpty) {
            FileLogger.i(TAG, "摘要已附加文件清单：读 ${fileOps.read.size} 个、改 ${fileOps.modified.size} 个")
        }
        FileLogger.i(TAG, "上下文压缩完成，摘要长度：${summaryText.length}")

        val markerId = UUID.randomUUID().toString()
        val compactedId = UUID.randomUUID().toString()
        val markerMessage = AgentMessage.UserMessage(
            id = markerId,
            content = CONTEXT_COMPACTION_MARKER
        )
        val compactedMessage = AgentMessage.AssistantMessage(
            id = compactedId,
            content = summaryText,
            toolCalls = emptyList()
        )

        // 持久化压缩结果到数据库
        if (sessionId != null) {
            try {
                val dbEntities = agentMessageDao.getMessagesBySessionOnce(sessionId
                )
                val firstTailId = tail.firstOrNull { msg -> msg.id.isNotEmpty() }?.id
                val tailEntity = if (firstTailId != null) dbEntities.find { it.id == firstTailId } else null
                // cutoff 必须来自 tailEntity 的真实时间戳。tail 首条尚未落库（持久化竞态）时**不能**
                // 回退到 now()——那会把所有已落库消息都标为已压缩，而摘要又晚落，重启后上下文全丢。
                // 查不到就直接跳过持久化标记（内存态不受影响）。
                val cutoffTimestamp = tailEntity?.timestamp
                // 三步写（标已压缩 + marker + summary）必须原子：若标记成功但摘要未落库，
                // head 会被回放过滤掉（isCompacted）而摘要缺失 → 重启后那段上下文静默消失。
                agentDatabase.withTransaction {
                    if (cutoffTimestamp != null) {
                        agentMessageDao.markMessagesCompactedBeforeTimestamp(sessionId, cutoffTimestamp)
                    } else {
                        FileLogger.w(TAG, "压缩持久化：tail 首条未落库，跳过已压缩标记以免误标全部历史（会话 $sessionId）")
                    }

                    // 摘要收尾：marker + summary 时间戳放在 tail 最后一条之后，回放/UI 顺序 = tail → 摘要，
                    // 与 Codex 一致（最近消息在前、接手摘要收尾），避免摘要插在历史最前导致观感混乱。
                    val tailLastTs = tail.asReversed().firstNotNullOfOrNull { msg ->
                        dbEntities.find { it.id == msg.id }?.timestamp
                    }
                    val insertBase = maxOf(System.currentTimeMillis(), tailLastTs ?: 0L) + 1
                    agentMessageDao.insert(
                        AgentMessageEntity(
                            id = markerId,
                            sessionId = sessionId,
                            role = MessageRole.USER.name,
                            content = CONTEXT_COMPACTION_MARKER,
                            timestamp = insertBase,
                            isCompactionMarker = true
                        )
                    )
                    agentMessageDao.insert(
                        AgentMessageEntity(
                            id = compactedId,
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            content = compactedMessage.content,
                            timestamp = insertBase + 1,
                            isContextSummary = true
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
        newMessages.add(compactedMessage)

        // 记录压缩后基准，供下次触发判断计算增长速率（自适应阈值）。
        if (sessionId != null) {
            lastCompactionSizeBySession[sessionId] = estimateTokens(newMessages)
        }

        return newMessages
    }

    /**
     * 调整拆分索引，确保 tail 不是以 ToolResultMessage 开头。
     *
     * OpenAI API 要求 role: "tool" 消息必须紧接在包含对应 tool_calls 的 assistant 消息之后。
     * 如果 tail 以 ToolResultMessage 开头，压缩后其前面的 assistant 消息（摘要）不含 toolCalls，
     * 该 tool 消息就变成了"孤立"的，API 会报 400 错误。
     *
     * 解决方案：向前回溯，把配对的 AssistantMessage(with toolCalls) 纳入 tail，
     * 确保所有 tool 消息都有配对的 toolCalls。
     */
    private fun adjustSplitIndex(messages: List<AgentMessage>, initialSplitIndex: Int): Int {
        var splitIndex = initialSplitIndex

        // 如果 tail 的第一条消息是 ToolResultMessage，
        // 需要向前找到对应的 AssistantMessage(with toolCalls)
        while (splitIndex > 0 && messages[splitIndex] is AgentMessage.ToolResultMessage) {
            splitIndex--
        }

        // 现在 splitIndex 可能指向一个 AssistantMessage(with toolCalls) 或其他类型消息
        // 如果是含 toolCalls 的 AssistantMessage，它必须和其后的 ToolResultMessage 一起在 tail 中
        if (splitIndex >= 0 && messages[splitIndex] is AgentMessage.AssistantMessage) {
            val assistantMsg = messages[splitIndex] as AgentMessage.AssistantMessage
            if (assistantMsg.toolCalls.isNotEmpty()) {
                // 这个 assistant 和紧随其后的 tool results 必须一起保留在 tail 中
                // splitIndex 已经指向它，无需再调整
                return splitIndex
            }
        }

        // 如果 splitIndex 指向的是一个普通消息（非 tool 相关），直接使用
        return splitIndex
    }

    private fun selectTailStartIndex(messages: List<AgentMessage>, usableTokens: Int): Int {
        val budget = ModelContextPolicy.preserveRecentTokens(usableTokens)
        var total = 0
        var splitIndex = messages.size

        for (index in messages.indices.reversed()) {
            val next = estimateTokens(messages[index])
            if (total + next > budget && splitIndex < messages.size) break
            total += next
            splitIndex = index
        }

        return splitIndex
    }

    private fun estimateTokens(messages: List<AgentMessage>): Int =
        messages.sumOf { estimateTokens(it) }

    private fun estimateTokens(message: AgentMessage): Int =
        ModelContextPolicy.estimateTokens(messageText(message))

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

    private fun buildSummaryInstruction(previousSummary: String?): String {
        val instruction = if (previousSummary.isNullOrBlank()) {
            "请根据下面的对话历史创建一个新的锚定摘要。"
        } else {
            """
                请根据下面的新对话历史更新已有锚定摘要。
                保留仍然正确的信息，移除过时信息，并合并新事实。

                <previous-summary>
                $previousSummary
                </previous-summary>
            """.trimIndent()
        }

        return systemPromptProvider.resolvePrompt(COMPACT_PROMPT_FILE)
            .replace(LEADING_COMMENT, "")
            .replace("{{INSTRUCTION}}", instruction)
    }

    /**
     * 压缩请求前的清理：截断可能丢弃最旧的 user 消息，导致头部出现孤立的 assistant/tool 消息，
     * 丢到第一条 user 为止；去掉图片与超长工具输出，压缩模型按纯文本做摘要。
     */
    private fun List<AgentMessage>.trimLeadingForCompaction(): List<AgentMessage> {
        val trimmed = dropWhile { it !is AgentMessage.UserMessage }
        return trimmed.map { msg ->
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

    private fun String.cleanSummary(): String =
        removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart()

    private fun String.truncateForSummary(): String {
        if (length <= TOOL_OUTPUT_MAX_CHARS) return this
        return take(TOOL_OUTPUT_MAX_CHARS) + "\n[Tool output truncated for compaction]"
    }

    /**
     * 按压缩模型窗口预算截断 head：从新到旧保留消息，超预算丢弃更旧的消息。
     * 预算用与全局一致的 CJK 感知 token 口径（[ModelContextPolicy.estimateTokens]），预留 30%
     * 给摘要提示词与旧摘要（故乘 0.7）；不使用「字符数」的第二套口径，避免中文截不干净。
     */
    private fun List<AgentMessage>.truncateForSummaryWindow(contextTokens: Int): List<AgentMessage> {
        if (isEmpty()) return this
        val budgetTokens = (contextTokens * SUMMARY_WINDOW_RESERVE_RATIO).toInt()
        var totalTokens = 0
        val kept = mutableListOf<AgentMessage>()
        for (msg in asReversed()) {
            val tokens = estimateTokens(msg)
            if (kept.isNotEmpty() && totalTokens + tokens > budgetTokens) break
            totalTokens += tokens
            kept.add(msg)
        }
        val truncated = kept.asReversed()
        if (truncated.size != size) {
            FileLogger.i(TAG, "head 超出压缩模型窗口预算，丢弃 ${size - truncated.size} 条最旧消息（预算 $budgetTokens token）")
        }
        return truncated
    }
}

/**
 * 压缩摘要的文件清单累积器。
 *
 * 文件清单**不交给摘要模型回忆**——模型会漏。改为从工具调用里机械提取：读过的（readFile）
 * 与改过的（writeFile / editFile）分别成集，改过的再从「读过」里剔除（与 Pi 的
 * `readFiles excludes files also modified` 一致）；并解析既有摘要里的清单块，使清单跨轮累积。
 *
 * 局限：[AgentMessage.ToolResultMessage] 不带 toolCallId，无法与具体调用精确配对，
 * 故按「调用意图」统计，失败或空操作的调用同样计入。
 */
internal object CompactionFileTracker {

    private val READ_BLOCK = Regex("(?s)<read-files>(.*?)</read-files>")
    private val MODIFIED_BLOCK = Regex("(?s)<modified-files>(.*?)</modified-files>")

    private val READ_TOOLS = setOf("readFile")
    private val MODIFY_TOOLS = setOf("writeFile", "editFile")

    internal data class FileOps(
        val read: List<String> = emptyList(),
        val modified: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = read.isEmpty() && modified.isEmpty()
    }

    fun extract(messages: List<AgentMessage>): FileOps {
        val read = LinkedHashSet<String>()
        val modified = LinkedHashSet<String>()
        for (message in messages) {
            // 只从 assistant 消息取：用户消息里出现同样字样的文本属巧合，不该被当元数据。
            if (message !is AgentMessage.AssistantMessage) continue
            for (call in message.toolCalls) {
                val path = call.arguments["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (path.isEmpty()) continue
                when (call.name) {
                    in READ_TOOLS -> read.add(path)
                    in MODIFY_TOOLS -> modified.add(path)
                }
            }
            READ_BLOCK.findAll(message.content).forEach { read.addAll(parseBlock(it.groupValues[1])) }
            MODIFIED_BLOCK.findAll(message.content).forEach { modified.addAll(parseBlock(it.groupValues[1])) }
        }
        read.removeAll(modified)
        return FileOps(read.toList(), modified.toList())
    }

    fun append(summary: String, ops: FileOps): String {
        if (ops.isEmpty) return summary
        return buildString {
            append(summary.trimEnd())
            if (ops.read.isNotEmpty()) {
                append("\n\n<read-files>\n")
                ops.read.forEach { append(it).append('\n') }
                append("</read-files>")
            }
            if (ops.modified.isNotEmpty()) {
                append("\n\n<modified-files>\n")
                ops.modified.forEach { append(it).append('\n') }
                append("</modified-files>")
            }
        }
    }

    private fun parseBlock(body: String): List<String> =
        body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}
