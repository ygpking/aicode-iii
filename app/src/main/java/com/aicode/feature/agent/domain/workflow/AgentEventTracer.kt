package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.EventTrace

/**
 * 把 [AgentEvent] 转成一行轨迹文本，供 [EventTrace] 记录。
 *
 * 存在意义：Agent 一次执行会推送 13 种事件，但此前这些事件**没有任何日志**——
 * 出问题时只能看到「开始/结束」两个端点，中间「模型吐了什么、工具何时开始、为何重试」
 * 全是黑盒。把映射集中在这里，好处是**只在一处维护**：新增事件类型时编译器会提示补分支，
 * 而调用方（[StatefulAgentWorkflow]）只需一行接入，不侵入事件产出的各个分支。
 *
 * 记录原则：只记**结构与度量**（长度、数量、状态、标识），不记正文原文。
 * 正文与完整 SSE 已由 [com.aicode.core.util.AILogger] 按会话完整留存，此处重复只会撑爆日志。
 */
internal object AgentEventTracer {

    /**
     * 记录一次事件。
     *
     * @param turnId 本回合 id；为 null 时不记录（调用方未开启轨迹）。
     * @param scope 作用域（通常是 sessionId），用于日志里区分会话。
     */
    fun onEvent(turnId: String?, scope: String?, event: AgentEvent) {
        if (turnId == null) return
        EventTrace.record(turnId, scope, "EVENT", describe(event))
    }

    /** 事件 → 一行摘要。新增 [AgentEvent] 子类时此处会因 `when` 不穷尽而编译失败。 */
    private fun describe(event: AgentEvent): String = when (event) {
        is AgentEvent.AssistantDelta -> "assistant_delta +${event.accumulated.length}字"
        is AgentEvent.ReasoningDelta -> "reasoning_delta +${event.accumulated.length}字"

        is AgentEvent.AssistantText -> buildString {
            append("assistant_text ")
            append("正文=${event.content.length}字 思考=${event.reasoning.length}字 ")
            append("工具调用=${event.toolCalls.size} ")
            append("token=${event.inputTokens}in/${event.outputTokens}out 缓存=${event.cachedInputTokens}")
            if (event.signature.isNotEmpty()) append(" 含签名")
            if (event.attachments.isNotEmpty()) append(" 附件=${event.attachments.size}")
        }

        is AgentEvent.ToolCallPreparing -> "tool_preparing ${event.toolName}"
        is AgentEvent.ToolCallStarted -> "tool_started ${event.toolName} id=${event.id}"
        is AgentEvent.ToolCallProgress -> "tool_progress ${event.toolName} id=${event.id} +${event.accumulated.length}字"
        is AgentEvent.ToolCallFinished -> buildString {
            append("tool_finished ${event.toolName} id=${event.id} ")
            append(if (event.isError) "失败" else "成功")
            append(" 结果=${event.result.length}字")
        }

        is AgentEvent.Retrying ->
            "retrying 第${event.attempt}/${event.maxRetries}次 原因=${event.error.kind}"
        is AgentEvent.KeySwitched -> "key_switched 换到第${event.newIndex}/${event.total}个"

        is AgentEvent.CompactionStarted -> "compaction_started 估算=${event.estimatedTokens}token"
        AgentEvent.CompactionFinished -> "compaction_finished"
        is AgentEvent.CompactionFailed -> "compaction_failed ${event.reason}"

        is AgentEvent.Failed -> "failed ${event.reasonCode ?: "-"} ${event.error.take(200)}"
        AgentEvent.Completed -> "completed"

        is AgentEvent.ModeChanged -> "mode_changed ${event.newMode} 原因=${event.reason.take(100)}"
    }
}
