package com.aicode.feature.agent.domain.workflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工具批调度的变更性分类。
 *
 * 默认 fail-closed：只有显式列入只读名单的工具才允许并行，其余（含写操作、命令、
 * 动态 MCP 工具）一律按「变更」串行，避免同批并发写同一目标造成静默覆盖。
 */
internal enum class ToolMutation { READ_ONLY, MUTATING }

/**
 * 判断某工具名是否只读。名单外的工具（含 MCP 动态工具）一律视为变更。
 */
internal fun classifyToolMutation(toolName: String): ToolMutation =
    if (toolName in READ_ONLY_TOOLS) ToolMutation.READ_ONLY else ToolMutation.MUTATING

/**
 * 明确的只读工具名单（与 ToolRegistry 注册名一致）。新增的写/命令类工具、
 * MCP 工具默认不在名单内 → 走串行，属安全的默认值。
 */
private val READ_ONLY_TOOLS: Set<String> = setOf(
    "readFile",
    "list",
    "search",
    "viewImage",
    "websearch",
    "webfetch",
    "retrieveToolResult",
    "diagnostics",
)

/**
 * 工具批调度器：只读工具并行、变更工具串行。
 *
 * 这是对「同批工具一律 `async{}.awaitAll()` 并行」的收敛——写类工具并发执行可能
 * 互相覆盖文件。串行范围按**工作区**分片（同一工作区内串行；不同工作区互不阻塞），
 * 单工作区时退化为单锁。结果顺序始终与输入一致。
 */
internal class ToolBatchScheduler {
    private val workspaceLocks = mutableMapOf<String, Mutex>()
    private val registryLock = Mutex()

    private suspend fun lockFor(workspaceKey: String): Mutex =
        registryLock.withLock { workspaceLocks.getOrPut(workspaceKey) { Mutex() } }

    /**
     * 按调度规则执行一批工具。
     *
     * @param calls 本批工具调用（含名称，用于分类）。
     * @param workspaceKey 工作区标识，用于分片串行。
     * @param exec 单项执行体。
     * @return 与 [calls] 等长、顺序一致的结果列表。
     */
    suspend fun <C, R> dispatch(
        calls: List<C>,
        workspaceKey: String,
        toolNameOf: (C) -> String,
        exec: suspend (C) -> R,
    ): List<R> = coroutineScope {
        calls.map { call ->
            async {
                when (classifyToolMutation(toolNameOf(call))) {
                    ToolMutation.READ_ONLY -> exec(call)
                    ToolMutation.MUTATING -> lockFor(workspaceKey).withLock { exec(call) }
                }
            }
        }.awaitAll()
    }
}
