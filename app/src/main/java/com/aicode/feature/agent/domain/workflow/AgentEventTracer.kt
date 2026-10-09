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
 *
 * 同理，逐字增量（[AgentEvent.AssistantDelta] / [AgentEvent.ReasoningDelta]）与流式
 * 工具输出片段（[AgentEvent.ToolCallProgress]）**一律不记**：它们按字符刷屏，实测占轨迹总量的 80%，
 * 却只重复「正在输出」这一个事实，而定量信息已由随后的 [AgentEvent.AssistantText] /
 * [AgentEvent.ToolCallFinished] 各一条给出。
 */
internal object AgentEventTracer {

    /**
     * 从工具参数/结果文本里抽取文件路径，让「本回合动了哪些文件」在轨迹里可见。
     *
     * 动机（真机踩坑）：轨迹里工具事件只有「成功/失败 + 结果 N 字」，**不记路径**。
     * 于是排查「editFile 为何报未读即改」时，只能去翻 486MB 的 ai-logs 逐个对齐参数，
     * 当场多花大量时间——而这条信息本就在结果与参数里。
     *
     * 两种来源：文件工具结果的 JSON（`"path":"…"`），以及未读即改类错误的自然语言文案
     * （`…读取 ~/workspace/xxx 的当前内容…`）。只抽路径、不记全文，与「只记结构与度量」的原则一致。
     */
    private val PATH_IN_JSON = Regex("\"(?:path|file|target|source|destination)\"\\s*:\\s*\"([^\"]{1,200})\"")
    private val PATH_IN_TEXT = Regex("(~/[\\w./\\-]{2,200})")

    private fun pathEvidence(text: String): String? {
        val raw = PATH_IN_JSON.find(text)?.groupValues?.get(1)
            ?: PATH_IN_TEXT.find(text)?.groupValues?.get(1)
            ?: return null
        // 路径可能含空格，而轨迹是空格分列的；替换掉以保证字段可切分。
        return raw.take(200).replace(' ', '_')
    }

    /**
     * 从工具结果的 transport JSON 里抽 `status.code`（如 `TOOL_TIMEOUT`）。
     *
     * 动机：失败行此前只有 120 字中文摘要，无法按类聚合、无法稳定区分「重试中的失败」与
     * 「确定性失败」；AI 与人都只能靠中文子串匹配。code 本就是结构化字段，落进轨迹后即可按类统计。
     * 只在失败时调用；成功结果里若出现同名键不受影响。
     */
    private val ERROR_CODE_IN_JSON = Regex("\"code\"\\s*:\\s*\"([A-Z][A-Z0-9_]{1,60})\"")

    private fun errorCode(text: String): String? =
        ERROR_CODE_IN_JSON.find(text)?.groupValues?.get(1)

    /**
     * 记录一次事件。
     *
     * @param turnId 本回合 id；为 null 时不记录（调用方未开启轨迹）。
     * @param scope 作用域（通常是 sessionId），用于日志里区分会话。
     * @return 本条分配到的 `seq`；未记录时返回 null。
     */
    fun onEvent(turnId: String?, scope: String?, event: AgentEvent): Long? {
        if (turnId == null) return null
        val detail = describe(event) ?: return null
        // 工具调用是**交错**的：同一回合可并发多次，而默认因果是「同回合上一条」，
        // 于是 `tool_finished` 会指向另一个工具的 `started`（实测 1640 条里 583 条、35.6%）。
        // 用调用 id 作业务键，把 finished 显式绑回它自己的 started。
        val seq = EventTrace.record(
            turnId, scope, "EVENT", detail,
            causeKey = (event as? AgentEvent.ToolCallFinished)?.let { toolCauseKey(it.id) }
        )
        if (event is AgentEvent.ToolCallStarted) {
            EventTrace.bindCause(turnId, scope, toolCauseKey(event.id), seq)
        }
        return seq
    }

    /** 工具调用的因果键。同一 `id` 的 started/finished 成对，并发时彼此不混。 */
    private fun toolCauseKey(id: String) = "tool:$id"

    /**
     * 事件 → 一行摘要；返回 null 表示该事件**不值得记**（逐字/流式片段）。
     * 新增 [AgentEvent] 子类时此处会因 `when` 不穷尽而编译失败。
     */
    private fun describe(event: AgentEvent): String? = when (event) {
        // 增量流一律不记：按字符刷屏，且每轮必有对应的汇总行。
        is AgentEvent.AssistantDelta, is AgentEvent.ReasoningDelta, is AgentEvent.ToolCallProgress -> null
        is AgentEvent.AssistantText -> buildString {
            append("assistant_text ")
            append("正文=${event.content.length}字 思考=${event.reasoning.length}字 ")
            append("工具调用=${event.toolCalls.size} ")
            append("token=${event.inputTokens}in/${event.outputTokens}out 缓存=${event.cachedInputTokens}")
            if (event.signature.isNotEmpty()) append(" 含签名")
            if (event.attachments.isNotEmpty()) append(" 附件=${event.attachments.size}")
        }

        is AgentEvent.ToolCallPreparing -> "tool_preparing ${event.toolName}"
        is AgentEvent.ToolCallStarted -> buildString {
            append("tool_started ${event.toolName} id=${event.id}")
            // 参数里带路径的（editFile/readFile/writeFile/…）落一份，事后一眼看出「动的哪个文件」。
            pathEvidence(event.argsPreview)?.let { append(" path=$it") }
        }
        is AgentEvent.ToolCallFinished -> buildString {
            append("tool_finished ${event.toolName} id=${event.id} ")
            append(if (event.isError) "失败" else "成功")
            append(" 结果=${event.result.length}字")
            // 路径**只在失败时**从结果抽：错误文案必带真实路径（未读即改/陈旧内容都会回显）。
            // 成功时结果可能是文件正文（readFile），正文里出现 "path" 键会记下无关路径、污染证据；
            // 而成功场景的路径已由 tool_started 从参数给出，此处不必再抽。
            if (event.isError) pathEvidence(event.result)?.let { append(" path=$it") }
            // 失败时带上错误码：让轨迹可**按 code 聚合**（把 TOOL_TIMEOUT 与 MISSING_COMMAND 分开统计），
            // 不再只能读中文摘要。来自 ToolResult 的 transport JSON。
            if (event.isError) errorCode(event.result)?.let { append(" code=$it") }
            // 失败时带上结果开头：原本只有「失败 + 字数」，要查为何失败得另翻 AILogger；
            // 摘要一行即可自证，无需再去别处找。
            if (event.isError) append(" 原因摘要=${event.result.take(120).replace('\n', ' ')}")
        }

        is AgentEvent.Retrying ->
            "retrying 第${event.attempt}/${event.maxRetries}次 原因=${event.error.kind}"
        is AgentEvent.KeySwitched -> "key_switched 换到第${event.newIndex}/${event.total}个"

        is AgentEvent.CompactionStarted -> "compaction_started 估算=${event.estimatedTokens}token"
        AgentEvent.CompactionFinished -> "compaction_finished"
        // transient 必须落盘：它区分「重试可成功」与「确定性失败」。
        // 此前只记原因，导致 30 次失败被读成 34% 的失败率，而其中 18 次其实是重试中的一环——
        // 缺这一个布尔值，就会把「自愈」误判为「故障」。
        is AgentEvent.CompactionFailed ->
            "compaction_failed ${event.reason} transient=${event.transient}"

        is AgentEvent.Failed -> "failed ${event.reasonCode ?: "-"} ${event.error.take(200)}"
        AgentEvent.Completed -> "completed"

        is AgentEvent.ModeChanged -> "mode_changed ${event.newMode} 原因=${event.reason.take(100)}"

        is AgentEvent.EvidenceGuardReport ->
            "evidence_guard_report 未通过项=${event.notices.size} ${event.notices.firstOrNull()?.take(120) ?: ""}"
    }
}
