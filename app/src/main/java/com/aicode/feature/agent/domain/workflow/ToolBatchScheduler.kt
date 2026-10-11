package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工具批调度的并发档位。
 *
 * 默认 fail-closed：只有能证明「无副作用」的工具才允许跨会话并行，其余
 * （含写操作、动态 MCP 工具）一律按 [FILE_MUTATING] 串行，避免并发写同一目标造成静默覆盖。
 */
internal enum class ToolMutation {
    /** 只读：无锁并行。 */
    READ_ONLY,

    /** 命令类：仅会话内串行；若识别为构建命令则再追全局锁，跨会话可并行。 */
    COMMAND,

    /** 文件/共享资源变更：会话内串行 + 全局串行（防跨会话写同一目标）。 */
    FILE_MUTATING,
}

/** 会走 shell 的命令类工具。 */
private val COMMAND_TOOLS: Set<String> = setOf("Bash", "Shizuku", "terminal")

/**
 * 「会改变外部状态」的能力集合。任一命中即非只读，必须串行。
 *
 * 判定采用**白名单式**（与变更能力集合**无交集**才只读），而非「含 READ_WORKSPACE 即只读」：
 * 后者会把同时声明读+写的工具（如 `MemoryTool` = READ_AGENT_CONFIG + MODIFY_AGENT_CONFIG）
 * 误判为只读 → 并发写静默覆盖。漏判一个写能力 = 数据损坏，比多串行一个只读工具严重得多。
 */
private val MUTATING_CAPABILITIES: Set<ToolCapability> = setOf(
    ToolCapability.WRITE_WORKSPACE,
    ToolCapability.EXECUTE_COMMANDS,
    ToolCapability.NETWORK_WRITE,
    ToolCapability.MODIFY_AGENT_CONFIG,
    ToolCapability.MODIFY_CONTAINER_ENV,
    ToolCapability.USER_INTERACTION,
    ToolCapability.MODIFY_SESSION_STATE,
    ToolCapability.MODIFY_TODO_STATE,
    ToolCapability.EXTERNAL_TOOL,
)

/**
 * 判断工具的默认档位（不含命令文本判定）。
 *
 * 三级、全部 fail-closed：
 * 1. 工具声明了能力集且与 [MUTATING_CAPABILITIES] 无交集 → 只读（可跨会话并行）；
 * 2. 能力集为空（未声明，典型为动态 MCP 工具）→ 回退按工具名判定；
 * 3. 都不命中 → [ToolMutation.FILE_MUTATING]。
 *
 * 能力优先于名单：名字在只读名单里但显式声明了写能力 → 以能力为准（保守）。
 *
 * @param toolName 工具注册名
 * @param capabilities 该工具声明的能力集合；未知/未声明传空集
 */
internal fun classifyToolMutation(
    toolName: String,
    capabilities: Set<ToolCapability> = emptySet(),
): ToolMutation = when {
    capabilities.isNotEmpty() ->
        if (capabilities.none { it in MUTATING_CAPABILITIES }) ToolMutation.READ_ONLY
        else ToolMutation.FILE_MUTATING
    toolName in READ_ONLY_TOOLS -> ToolMutation.READ_ONLY
    toolName in COMMAND_TOOLS -> ToolMutation.COMMAND
    else -> ToolMutation.FILE_MUTATING
}

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
    "loadSkill",
    "sendFile",
)

/**
 * 工具批调度器。
 *
 * 加锁分两层，键在进程内全局唯一（本类实例挂在 `@Singleton` 的 workflow 上）：
 *  - **会话锁** `sess:<会话>`：同一会话内的变更类工具**互斥**（不并发），
 *    避免同批 `[writeFile f, Bash "cat f"]` 这类依赖关系被并发打乱。
 *    注意：只保证「不并发」，**不保证谁先谁后**——`async` 的抢占顺序无硬承诺，
 *    需要严格先后的话应在调用方串行发起，不能靠本调度器。
 *  - **资源锁**：跨会话共享的资源才加。构建命令用 `build:`（守护进程全局单实例），
 *    文件与其它共享资源用工作区键。
 *
 * 只读工具不加任何锁；命令类不加资源锁（除构建外），这是跨会话并行度的来源。
 *
 * 结果顺序始终与输入顺序一致（`awaitAll` 保证返回列表对齐输入）。
 */
internal class ToolBatchScheduler {
    private val locks = mutableMapOf<String, Mutex>()
    private val registryLock = Mutex()

    private suspend fun lockFor(key: String): Mutex =
        registryLock.withLock { locks.getOrPut(key) { Mutex() } }

    /**
     * 按调用需要的锁键依次加锁后执行。
     *
     * 键**排序后**再加锁（全局一致的顺序）→ 任意两个调用的加锁序列无环，不会死锁。
     */
    private suspend fun <R> withLocks(keys: List<String>, block: suspend () -> R): R {
        if (keys.isEmpty()) return block()
        suspend fun acquire(index: Int): R {
            if (index == keys.size) return block()
            return lockFor(keys[index]).withLock { acquire(index + 1) }
        }
        return acquire(0)
    }

    /**
     * 该调用需要持有的锁键（会话锁 + 资源锁，未排序）。
     */
    internal fun lockKeysFor(
        toolName: String,
        sessionKey: String,
        commandText: String?,
        workspaceKey: String,
        capabilities: Set<ToolCapability> = emptySet(),
    ): List<String> {
        val sessionLock = "sess:$sessionKey"
        return when (classifyToolMutation(toolName, capabilities)) {
            ToolMutation.READ_ONLY -> emptyList()
            ToolMutation.COMMAND ->
                if (BuildCommandDetector.isBuildCommand(commandText)) {
                    listOf(sessionLock, BUILD_LOCK_KEY)
                } else {
                    listOf(sessionLock)
                }
            ToolMutation.FILE_MUTATING -> listOf(sessionLock, "ws:$workspaceKey")
        }
    }

    /**
     * 按调度规则执行一批工具。
     *
     * @param calls 本批工具调用（含名称，用于分类）。
     * @param sessionKey 会话标识：同会话内的变更类工具在此键上串行。
     * @param workspaceKey 工作区标识：文件与共享资源类的跨会话互斥范围。
     * @param toolNameOf 取调用名。
     * @param commandTextOf 取调用承载的 shell 命令（非命令类工具返回 null）。
     * @param capabilitiesOf 取该调用对应工具声明的能力集；返回空集表示未知/未声明（走名单兜底）。
     * @param exec 单项执行体。
     * @return 与 [calls] 等长、顺序一致的结果列表。
     */
    suspend fun <C, R> dispatch(
        calls: List<C>,
        sessionKey: String,
        workspaceKey: String,
        toolNameOf: (C) -> String,
        commandTextOf: (C) -> String? = { null },
        capabilitiesOf: (C) -> Set<ToolCapability> = { emptySet() },
        exec: suspend (C) -> R,
    ): List<R> = coroutineScope {
        calls.map { call ->
            async {
                val keys = lockKeysFor(
                    toolName = toolNameOf(call),
                    sessionKey = sessionKey,
                    commandText = commandTextOf(call),
                    workspaceKey = workspaceKey,
                    capabilities = capabilitiesOf(call),
                ).sorted()
                withLocks(keys) { exec(call) }
            }
        }.awaitAll()
    }

    companion object {
        /** 构建类命令的全局锁键：构建守护进程是进程级单实例，锁必须跨会话。 */
        internal const val BUILD_LOCK_KEY = "build:global"
    }
}
