package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage

/**
 * 压缩输入头部清理：把开头一段**非 user** 消息折叠成一条 user 消息。
 *
 * 独立成纯逻辑便于单测（与 [CompactionTailSelector]、[CompactionMarkSelector] 同一思路）。
 *
 * ## 为什么需要折叠而不是丢弃
 *
 * agent 自动循环多轮工具调用时，一段 head 可能整段没有 user 消息（工具连续执行）；
 * 而 tail 侧的配对保护又会把 `assistant(toolCalls)` 拉进 tail，
 * 使下一次压缩的 head 正好以它开头。旧实现直接 `dropWhile { it !is UserMessage }`
 * 把这整段丢出摘要输入——**无报错、无日志**，该段此后永远不进任何摘要
 * （`previousSummary` 只含更早内容），模型对它永久失忆。
 *
 * 折叠成一条 user 消息则既不丢信息，又满足各 provider 的角色约束：
 * Anthropic 要求首条为 user；OpenAI 不允许首条是孤立的 tool 结果。
 */
internal object CompactionHeadFolder {

    /** 单条工具输出进摘要材料时的截断上限（与 [ContextCompactor] 的一致性由调用方保证）。 */
    private const val DIGEST_CHARS = 2000

    /**
     * 折叠头部非 user 段。
     *
     * 头部本就以 user 开头时由调用方跳过（不传 leading），本函数只管折叠。
     * 空列表返回 null。返回类型写明 [AgentMessage.UserMessage]（只产出这一种），
     * 免得调用方为了取 `content` 还要做类型转换。
     */
    fun fold(leading: List<AgentMessage>): AgentMessage.UserMessage? {
        if (leading.isEmpty()) return null
        return AgentMessage.UserMessage(content = leading.joinToString("\n\n") { digest(it) })
    }

    /** 把一条无法作为首条发送的消息压成摘要材料用的纯文本。 */
    private fun digest(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content
        is AgentMessage.ToolResultMessage ->
            "[工具 ${message.toolName} 结果] " + message.result.take(DIGEST_CHARS)
        is AgentMessage.AssistantMessage -> {
            val body = buildString {
                if (message.content.isNotBlank()) append(message.content)
                message.toolCalls.forEach { call ->
                    if (isNotEmpty()) append('\n')
                    append("[调用工具 ${call.name}] ")
                    append(call.arguments.toString().take(DIGEST_CHARS))
                }
            }
            body.ifBlank { "[助手消息（无正文）]" }
        }
    }
}
