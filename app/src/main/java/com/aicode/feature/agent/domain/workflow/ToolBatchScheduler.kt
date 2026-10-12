package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.coroutines.CompletableDeferred
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
     * @param pathOf 取调用操作的文件路径（归一化后；非文件类工具或取不到返回 null）。
     *   用于同批内「读先于写」的依赖排序。
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
        pathOf: (C) -> String? = { null },
        exec: suspend (C) -> R,
    ): List<R> = coroutineScope {
        // 依赖表必须先算完再起协程：read 的完成信号用 CompletableDeferred 广播，
        // 避免「协程里反向索引尚未赋值的 Deferred」这种竞态。
        val waits = readBeforeWriteWaits(calls, toolNameOf, pathOf)
        val finished = calls.map { CompletableDeferred<Unit>() }
        calls.indices.map { i ->
            async {
                // 依赖等待放在加锁**之前**：等待期间不持会话锁，
                // 否则一个 edit 等 read 会把同批其它变更类工具一起堵住。
                waits[i].forEach { finished[it].await() }
                try {
                    val keys = lockKeysFor(
                        toolName = toolNameOf(calls[i]),
                        sessionKey = sessionKey,
                        commandText = commandTextOf(calls[i]),
                        workspaceKey = workspaceKey,
                        capabilities = capabilitiesOf(calls[i]),
                    ).sorted()
                    withLocks(keys) { exec(calls[i]) }
                } finally {
                    // 无论成败都发信号：读失败时依赖它的写照常运行（随后按 NOT_READ 正常报错），
                    // 绝不让下游永久挂起。
                    finished[i].complete(Unit)
                }
            }
        }.awaitAll()
    }

    companion object {
        /** 构建类命令的全局锁键：构建守护进程是进程级单实例，锁必须跨会话。 */
        internal const val BUILD_LOCK_KEY = "build:global"
    }
}

/** 参与「读先于写」排序的写工具（文件级私有，供顶层函数 [readBeforeWriteWaits] 使用）。 */
private val DEPENDENCY_WRITERS: Set<String> = setOf("editFile", "writeFile")

/** 参与「读先于写」排序的读工具（读状态在这里登记，见 FileTools.record）。 */
private val DEPENDENCY_READERS: Set<String> = setOf("readFile")

/**
 * 计算同批内每个调用需要等待的调用下标。
 *
 * 规则：**写工具**（editFile/writeFile）必须等**同路径的 readFile** 完成，
 * 因为读状态是在 readFile 成功后才登记的（`FileTools.readStateStore.record`），
 * 而 `EditFileTool.checkReadState` 在编辑前要求该登记存在。
 * 并行批次里二者顺序由 `async` 抢占决定、无硬承诺，实测 8 天有 58 个批次
 * edit 先跑 → 直接 NOT_READ 失败（editFile 失败的最大单一原因，共 93 次）。
 *
 * **全批扫描、不看出场顺序**：典型样本是同一条 assistant 消息里先写 editFile 参数、
 * 再写 readFile 参数（实测 17:09:28 两个 editFile 失败、8 秒后两个 readFile 才成功），
 * 所以不能只向前看——只要同路径本批里有读，写就等它。
 * 若本批没有同路径的读，写不等任何人（按 NOT_READ 正常失败，不白等）。
 *
 * 只对「路径可比较」的调用建边：任一侧路径为 null 不建边（宁可漏排序，
 * 也不能把无关调用硬绑成依赖而白等）。
 *
 * 纯函数、无 IO，便于单测覆盖全部边界。
 */
internal fun <C> readBeforeWriteWaits(
    calls: List<C>,
    toolNameOf: (C) -> String,
    pathOf: (C) -> String?,
): List<List<Int>> {
    // 第一遍：路径 -> 本批全部读调用下标（不分先后）
    val readIndexByPath = mutableMapOf<String, MutableList<Int>>()
    calls.forEachIndexed { i, call ->
        if (toolNameOf(call) in DEPENDENCY_READERS) {
            pathOf(call)?.takeIf { it.isNotBlank() }?.let { readIndexByPath.getOrPut(it) { mutableListOf() }.add(i) }
        }
    }
    // 第二遍：写工具取同路径的读下标（排除自己，读工具本就不会命中写分支）
    return calls.mapIndexed { i, call ->
        val name = toolNameOf(call)
        if (name !in DEPENDENCY_WRITERS) {
            emptyList()
        } else {
            pathOf(call)?.takeIf { it.isNotBlank() }
                ?.let { readIndexByPath[it]?.filter { idx -> idx != i } }
                .orEmpty()
        }
    }
}
