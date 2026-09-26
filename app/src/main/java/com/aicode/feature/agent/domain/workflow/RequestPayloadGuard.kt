package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.domain.model.ModelContextPolicy

/**
 * 发往模型前的请求体治理：补三条与「上下文超限」相关的防线。
 *
 * 这些防线作用于**本次发送的消息副本**，不回写持久化历史，也不改变 UI——只是让请求更可能在
 * provider 侧通过。纯函数、零 IO，便于单测。
 *
 * 1. **D5 巨型单条用户消息头尾截断**：折叠（压缩）对「单条自身超线」无解（压缩还会因消息条数
 *    过少而直接跳过），粘贴超大文档时每轮必被 400；故对超线用户消息做头尾保留式截断。
 * 2. **D3 Split-Turn 角色桥接**：压缩保留段可能从 assistant/tool_call 中途开始（见
 *    `ContextCompactor.adjustSplitIndex`），此时首条不是 user，违反 Anthropic「首条必须 user」
 *    约束；补一条合成 user 说明。
 * 3. **D4 请求体字节预算**：token 预算看不见 UTF-8/JSON 膨胀与图片 base64，关闭压缩时历史仍可能
 *    把请求撑到 413；按估字节数压缩超长工具输出、再剥离历史图片。
 *
 * 顺序：D5（先让总量回到 token 预算内）→ D3（补首条 user）→ D4（最后按字节收缩）。
 */
internal object RequestPayloadGuard {

    /** 请求体字节硬上限。取 4MiB：多数中转/网关（Nginx 默认 1m、常见调至 8–20m）与 provider 都可接受。 */
    const val MAX_REQUEST_BYTES = 4 * 1024 * 1024

    /** 单条用户消息超过此 token 数才考虑截断（避免误伤正常长消息）。 */
    private const val MIN_GIANT_USER_TOKENS = 2_000

    /** 截断后单条至少保留的 token 数（保证仍带必要上下文）。 */
    private const val MIN_KEPT_USER_TOKENS = 800

    /** 工具输出压缩时保留的头/尾字符数。 */
    private const val KEEP_HEAD_CHARS = 6_000
    private const val KEEP_TAIL_CHARS = 1_500

    /** 每条消息的 JSON 框架开销（字段名/括号/逗号）的粗略估计（字节）。 */
    private const val JSON_FRAMING_PER_MESSAGE = 64

    private const val TRUNCATED_MARKER = "\n\n[…… 消息过长已截断：完整原文保留在会话记录中 ……]\n\n"

    /**
     * 发送前统一治理。按需依次应用 D5/D3/D4；无变化时返回原列表。
     *
     * @param budgetTokens 模型上下文窗口（token）；<=0 表示未知，跳过 D5。
     */
    fun prepareForSend(messages: List<AgentMessage>, budgetTokens: Int): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        val afterSizing = truncateOversizedUserMessages(messages, budgetTokens)
        val withBridge = ensureLeadingUser(afterSizing)
        return enforceByteBudget(withBridge, MAX_REQUEST_BYTES)
    }

    // ---------- D5 ----------

    /**
     * 巨型单条用户消息头尾截断：总量已超 [limitTokens] 时，从最大的用户消息开始，用「头 3/4 + 尾 1/4」
     * 加标记的截断文本替换，直到回到预算内或无可截对象。
     */
    fun truncateOversizedUserMessages(messages: List<AgentMessage>, limitTokens: Int): List<AgentMessage> {
        if (limitTokens <= 0 || messages.isEmpty()) return messages
        var overage = messages.sumOf { estimateMessageTokens(it) } - limitTokens
        if (overage <= 0) return messages

        val out = messages.toMutableList()
        val candidates = out.withIndex()
            .filter { it.value is AgentMessage.UserMessage && estimateMessageTokens(it.value) > MIN_GIANT_USER_TOKENS }
            .sortedByDescending { estimateMessageTokens(it.value) }
            .map { it.index }

        for (index in candidates) {
            if (overage <= 0) break
            val message = out[index] as AgentMessage.UserMessage
            val oldTokens = ModelContextPolicy.estimateTokens(message.content)
            val targetTokens = (oldTokens - overage).coerceAtLeast(MIN_KEPT_USER_TOKENS)
            val fitted = fitText(message.content, targetTokens) ?: continue
            val newTokens = ModelContextPolicy.estimateTokens(fitted)
            if (newTokens >= oldTokens) continue
            overage -= oldTokens - newTokens
            out[index] = message.copy(content = fitted)
        }
        return if (out == messages) messages else out
    }

    /** 头尾保留式截断到目标 token 内；无法在 6 轮收缩内装下则返回 null（调用方跳过该条）。 */
    private fun fitText(text: String, targetTokens: Int): String? {
        var keptChars = (targetTokens * 1.5f).toInt().coerceAtLeast(TRUNCATED_MARKER.length + 2)
        repeat(6) {
            if (text.length <= keptChars) return null
            val head = keptChars * 3 / 4
            val candidate = text.take(head) + TRUNCATED_MARKER + text.takeLast(keptChars - head)
            if (ModelContextPolicy.estimateTokens(candidate) <= targetTokens) return candidate
            keptChars = (keptChars * 3 / 4).coerceAtLeast(TRUNCATED_MARKER.length + 2)
        }
        return null
    }

    // ---------- D3 ----------

    /** 首条不是 user 时，在最前补一条合成 user 说明（满足 Anthropic「首条必须 user」约束）。 */
    fun ensureLeadingUser(messages: List<AgentMessage>): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        if (messages.first() is AgentMessage.UserMessage) return messages
        return listOf(AgentMessage.UserMessage(content = SPLIT_TURN_BRIDGE_MESSAGE)) + messages
    }

    private const val SPLIT_TURN_BRIDGE_MESSAGE =
        "[系统说明] 为避免单个超长任务轮次撑爆上下文，更早的步骤已折叠为后续摘要。以下从当前任务中途继续，请勿重复已完成的工作。"

    // ---------- D4 ----------

    /**
     * 请求体字节预算：超 [maxBytes] 时先压缩最长的工具输出（头尾保留），再按从旧到新剥离图片，
     * 直到回到预算内或无可压对象。不改变消息条数与顺序。
     *
     * 注：视觉能力剥图由调用方 `sanitizeImagesForModel` 负责（在本函数之前）；本函数只管字节。
     */
    fun enforceByteBudget(messages: List<AgentMessage>, maxBytes: Int): List<AgentMessage> {
        if (maxBytes <= 0 || messages.isEmpty()) return messages
        if (estimatePayloadBytes(messages) <= maxBytes) return messages

        val out = messages.toMutableList()
        var changed = false

        while (estimatePayloadBytes(out) > maxBytes) {
            val idx = out.indices
                .mapNotNull { i ->
                    val m = out[i] as? AgentMessage.ToolResultMessage
                    m?.takeIf { it.result.length > KEEP_HEAD_CHARS + KEEP_TAIL_CHARS }?.let { i }
                }
                .maxByOrNull { (out[it] as AgentMessage.ToolResultMessage).result.length }
                ?: break
            val result = out[idx] as AgentMessage.ToolResultMessage
            val compacted = compactText(result.result)
            if (compacted.length >= result.result.length) break
            out[idx] = result.copy(result = compacted)
            changed = true
        }

        // 图片兜底：字节体积多在图片 base64 而非文本；最后按从旧到新剥离。
        for (index in out.indices) {
            if (estimatePayloadBytes(out) <= maxBytes) break
            val stripped = stripImages(out[index])
            if (stripped != null) {
                out[index] = stripped
                changed = true
            }
        }
        return if (changed) out else messages
    }

    private fun compactText(text: String): String {
        if (text.length <= KEEP_HEAD_CHARS + KEEP_TAIL_CHARS + 80) return text
        val omitted = text.length - KEEP_HEAD_CHARS - KEEP_TAIL_CHARS
        return text.take(KEEP_HEAD_CHARS) +
            "\n... [请求体体积限制，已省略 $omitted 字符] ...\n" +
            text.takeLast(KEEP_TAIL_CHARS)
    }

    /** 剥离一条消息携带的图片并留说明；无图片或无变化时返回 null。 */
    private fun stripImages(message: AgentMessage): AgentMessage? = when (message) {
        is AgentMessage.UserMessage ->
            if (message.images.isEmpty()) null
            else message.copy(
                images = emptyList(),
                content = message.content +
                    "\n\n[…… 本消息携带的 ${message.images.size} 张图片因请求体体积限制已从模型上下文省略 ……]",
            )
        is AgentMessage.ToolResultMessage ->
            if (message.images.isEmpty()) null
            else message.copy(
                images = emptyList(),
                result = message.result +
                    "\n\n[…… ${message.images.size} 张图片因请求体体积限制已从模型上下文省略 ……]",
            )
        is AgentMessage.AssistantMessage -> null
    }

    // ---------- 估算 ----------

    /** 整批消息的估算字节数（UTF-8 文本 + JSON 框架 + 图片 base64 长度）。 */
    fun estimatePayloadBytes(messages: List<AgentMessage>): Long {
        var bytes = 0L
        messages.forEach { message ->
            bytes += JSON_FRAMING_PER_MESSAGE
            when (message) {
                is AgentMessage.UserMessage -> {
                    bytes += utf8Len(message.content)
                    bytes += message.images.sumOf { it.base64Data.length.toLong() }
                }
                is AgentMessage.AssistantMessage -> {
                    bytes += utf8Len(message.content)
                    bytes += utf8Len(message.reasoning)
                    message.toolCalls.forEach { bytes += utf8Len(it.name) + utf8Len(it.arguments.toString()) }
                    bytes += message.images.sumOf { it.base64Data.length.toLong() }
                }
                is AgentMessage.ToolResultMessage -> {
                    bytes += utf8Len(message.toolName) + utf8Len(message.result)
                    bytes += message.images.sumOf { it.base64Data.length.toLong() }
                }
            }
        }
        return bytes
    }

    private fun utf8Len(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    private fun estimateMessageTokens(message: AgentMessage): Int = ModelContextPolicy.estimateTokens(messageText(message))

    private fun messageText(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content
        is AgentMessage.AssistantMessage -> buildString {
            append(message.content)
            append(message.reasoning)
            message.toolCalls.forEach { append(it.name).append(it.arguments.toString()) }
        }
        is AgentMessage.ToolResultMessage -> message.toolName + message.result
    }
}
