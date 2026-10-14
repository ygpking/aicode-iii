package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理 → 兄弟子代理的消息工具 `messageSibling`。
 *
 * 只允许发给**同一父会话**派出的兄弟子代理：跨父（不同主会话）一律拒绝，
 * 避免不同项目/会话的子代理互相越权。
 *
 * 异步不阻塞，复用主会话消息同一条分发链路（忙碌时下一批工具结果搭车，
 * 空闲时触发收件方新一轮），因此对已完成的兄弟发消息会把它重新唤醒——
 * 这是「叫兄弟分摊任务」的基础。
 *
 * 只在子代理会话中可用——主会话的工具集剔除了它（见 AIAgentViewModel）。
 */
class MessageSiblingTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val eventBus: SubAgentEventBus
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "MessageSiblingTool"
        const val MESSAGE_MAX = 4000
    }

    override val name = "messageSibling"

    override val description = "向兄弟子代理（同一个主会话派出的其它子代理）发送一条消息：同步进展、交换结论、或请它接手一部分任务。异步发送、不阻塞你继续执行；对方若已完成会被重新唤醒。收件人用 to 指定：取其子会话 id，或它的标题（主代理在派你时可把兄弟的 id/标题告诉你）。只限同一个主会话下的兄弟，跨父会话会被拒绝。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "to" to ToolParameter(
            name = "to",
            type = ParameterType.STRING,
            description = "收件兄弟子代理：子会话 id，或它的标题（即派发时的 description）。这些信息由主代理在派你时提供。多个兄弟标题相同时必须改用 id",
            required = true
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给兄弟的消息正文（要它知道的信息、结论，或请它做的事）",
            required = true
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val self = sessionUseCase.getSessionById(sessionId)
            ?: return ToolResult.Error("会话不存在", "NO_SESSION")
        val parentSessionId = self.parentId
            ?: return ToolResult.Error("当前会话不是子代理，无法向兄弟子代理发送消息", "NO_PARENT")

        val to = (args["to"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (to.isNullOrBlank()) {
            return ToolResult.Error("参数无效：to 不能为空", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        if (message.length > MESSAGE_MAX) {
            return ToolResult.Error("消息过长（上限 $MESSAGE_MAX 字）", "INVALID_ARGS")
        }

        // 只在同父兄弟里找：跨父一律不可见。
        val siblings = sessionUseCase.getSubSessionsByParentOnce(parentSessionId)
        if (to == sessionId) {
            return ToolResult.Error("不能给自己发消息：to 指向的是当前会话", "INVALID_TARGET")
        }
        // 按 id 优先；只有 id 未命中时才回退到标题。
        // 标题同名（典型：连派两个「Explore」）时必须报错而不是取第一个——静默取错人
        // 比报错更贵：消息送错对象、发送方毫无感知。
        val byId = siblings.firstOrNull { it.id == to }
        val target = byId ?: run {
            val byTitle = siblings.filter { it.title == to }
            when (byTitle.size) {
                1 -> byTitle.first()
                0 -> return ToolResult.Error(
                    "找不到兄弟子代理「$to」：它必须与你在同一个主会话下。请向主代理确认兄弟的 id 或标题",
                    "SIBLING_NOT_FOUND"
                )
                else -> return ToolResult.Error(
                    "有 ${byTitle.size} 个兄弟的标题都是「$to」，无法确定收件人。请向主代理确认要发给哪一个，改用它的子会话 id：${byTitle.joinToString("、") { it.id }}",
                    "AMBIGUOUS_TARGET"
                )
            }
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = target.id,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_SIBLING,
                detail = message,
                senderSessionId = sessionId
            )
        )
        FileLogger.i(TAG, "子代理向兄弟发消息: from=$sessionId to=${target.id} parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("state", "sent")
                put("to", target.id)
                put("message", "消息已发送给兄弟子代理「${target.title}」，可继续手头工作；运行中的会在下一批工具结果里收到，已完成的会被重新唤醒。")
            }
        )
    }
}
