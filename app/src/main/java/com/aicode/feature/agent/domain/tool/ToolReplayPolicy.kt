package com.aicode.feature.agent.domain.tool

/**
 * 崩溃/中断后孤儿工具调用的处置策略（fail-closed）。
 *
 * 场景：assistant 声明了一批 tool_calls，但进程在工具执行完成前被杀，结果行永远没写。
 * 历史重建时若把这些孤儿 tool_call 直接丢弃，模型既不知道曾尝试过、也无法据此自纠；
 * 极端情况下（该轮正文为空且唯一工具调用被丢弃）整条 assistant 消息都会消失，历史出现空洞。
 *
 * 处置：
 * - **不自动重放任何工具**（重放需要重新执行，副作用不可控）——统一补一条「未完成」占位结果，
 *   让 assistant(tool_calls) 与 tool 结果保持配对（避免 API 400），并让模型知道「上次没跑完」；
 * - [isReplaySafe] 仅用于在占位文案里区分「无副作用可直接重试」与「重试前先确认副作用」。
 *   MCP 工具名以 `mcp_` 开头，远端行为本地无从判定，一律视为不可安全重试。
 */
internal object ToolReplayPolicy {

    /** 明确无副作用、中断后可安全重试的只读工具。名单外（写工具、命令工具、MCP）一律不可安全重试。 */
    private val SAFE_REPLAY_TOOLS: Set<String> = setOf(
        "readFile",
        "list",
        "search",
        "viewImage",
        "websearch",
        "webfetch",
    )

    fun isReplaySafe(toolName: String): Boolean =
        !toolName.startsWith("mcp_") && toolName in SAFE_REPLAY_TOOLS

    /** 孤儿工具调用的占位结果文本：保持与其它工具失败的同一传输格式，供模型自纠。 */
    fun interruptedStubText(toolName: String): String {
        val hint = if (isReplaySafe(toolName)) {
            "该工具无副作用，可直接重试。"
        } else {
            "重试前请先确认它是否已产生副作用。"
        }
        return ToolResult.Error(
            "上一次会话中断，工具调用（$toolName）未完成，没有结果。$hint",
            "INTERRUPTED",
        ).toTransportString()
    }
}
