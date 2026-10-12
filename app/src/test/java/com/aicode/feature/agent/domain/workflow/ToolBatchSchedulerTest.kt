package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具批调度器的并发语义测试：只读并行、命令类跨会话并行、同会话串行、
 * 构建类全局串行、文件类跨会话互斥、顺序保持、未知工具 fail-closed。
 */
class ToolBatchSchedulerTest {

    private class Probe {
        private val active = AtomicInteger(0)
        private val peak = AtomicInteger(0)

        /**
         * 进入临界区并记录峰值。
         *
         * incrementAndGet 必须放在 updateAndGet **之外**：lambda 在竞争下会被重试执行，
         * 写在里面会把 active 重复自增（实测泄漏 4.3 万次），峰值随之失真。
         */
        fun begin() {
            val current = active.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, current) }
        }

        fun end() { active.decrementAndGet() }
        fun peak(): Int = peak.get()
    }

    /** 同一调度器实例内跑两个会话的同一批调用，返回峰值并发。 */
    private fun twoSessions(name: String, command: String? = null, workspace: String = "/ws"): Int {
        val probe = Probe()
        val scheduler = ToolBatchScheduler()
        runBlocking {
            coroutineScope {
                listOf("s1", "s2").map { session ->
                    async {
                        scheduler.dispatch(
                            calls = listOf(name),
                            sessionKey = session,
                            workspaceKey = workspace,
                            toolNameOf = { it },
                            commandTextOf = { command },
                        ) {
                            probe.begin()
                            try { delay(80) } finally { probe.end() }
                            1
                        }
                    }
                }.awaitAll()
            }
        }
        return probe.peak()
    }

    @Test
    fun readOnlyToolsRunInParallel(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val probe = Probe()
        val calls = listOf("readFile", "readFile", "readFile", "readFile")
        scheduler.dispatch(calls, sessionKey = "s1", workspaceKey = "/ws", toolNameOf = { it }) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertTrue("只读工具应并行，实测峰值=${probe.peak()}", probe.peak() > 1)
    }

    @Test
    fun mutatingToolsRunSeriallyWithinSession(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val probe = Probe()
        val calls = listOf("writeFile", "editFile", "Bash", "mcp__x__tool")
        scheduler.dispatch(calls, sessionKey = "s1", workspaceKey = "/ws", toolNameOf = { it }) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertEquals("同会话内变更类工具必须串行", 1, probe.peak())
    }

    @Test
    fun nonBuildCommandsRunInParallelAcrossSessions() {
        val peak = twoSessions("Bash", "ls -la ~/workspace")
        assertTrue("跨会话的非构建命令应并行，实测峰值=$peak", peak > 1)
    }

    @Test
    fun buildCommandsRunSeriallyAcrossSessions() {
        val peak = twoSessions("Bash", "sh gradlew :app:testUniversalDebugUnitTest")
        assertEquals("跨会话的构建命令必须串行（防守护进程互杀）", 1, peak)
    }

    @Test
    fun buildCommandsRunSeriallyWithinSameBatch(): Unit = runBlocking {
        val probe = Probe()
        val scheduler = ToolBatchScheduler()
        scheduler.dispatch(
            calls = listOf("Bash", "Bash"),
            sessionKey = "s1",
            workspaceKey = "/ws",
            toolNameOf = { it },
            commandTextOf = { "sh gradlew assembleUniversalDebug" },
        ) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertEquals("同批的两个构建命令必须串行", 1, probe.peak())
    }

    @Test
    fun fileMutationsRunSeriallyAcrossSessions() {
        val peak = twoSessions("writeFile")
        assertEquals("跨会话的文件写入必须串行（防静默覆盖）", 1, peak)
    }

    @Test
    fun unknownToolIsTreatedAsFileMutating() {
        assertEquals(ToolMutation.READ_ONLY, classifyToolMutation("readFile"))
        assertEquals(ToolMutation.FILE_MUTATING, classifyToolMutation("writeFile"))
        assertEquals(ToolMutation.FILE_MUTATING, classifyToolMutation("totallyUnknown"))
        assertEquals(ToolMutation.FILE_MUTATING, classifyToolMutation("mcp__server__tool"))
        assertEquals(ToolMutation.COMMAND, classifyToolMutation("Bash"))
        assertEquals(ToolMutation.COMMAND, classifyToolMutation("Shizuku"))
        assertEquals(ToolMutation.COMMAND, classifyToolMutation("terminal"))
    }

    @Test
    fun resultsPreserveInputOrder(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val calls = listOf("writeFile", "readFile", "editFile")
        val out = scheduler.dispatch(calls, sessionKey = "s1", workspaceKey = "/ws", toolNameOf = { it }) { name ->
            if (name == "readFile") delay(10)
            "done:$name"
        }
        assertEquals(listOf("done:writeFile", "done:readFile", "done:editFile"), out)
    }

    @Test
    fun lockKeysMatchBuildAndCommandShape() {
        val scheduler = ToolBatchScheduler()
        // 构建命令：会话锁 + 全局构建锁
        assertEquals(
            listOf(ToolBatchScheduler.BUILD_LOCK_KEY, "sess:s1"),
            scheduler.lockKeysFor("Bash", "s1", "sh gradlew test", "/ws").sorted(),
        )
        // 非构建命令：仅会话锁（跨会话可并行）
        assertEquals(listOf("sess:s1"), scheduler.lockKeysFor("Bash", "s1", "ls -la", "/ws").sorted())
    }

    @Test
    fun lockKeysMatchFileAndReadOnlyShape() {
        val scheduler = ToolBatchScheduler()
        // 文件类：会话锁 + 工作区锁
        assertEquals(listOf("sess:s1", "ws:/ws"), scheduler.lockKeysFor("writeFile", "s1", null, "/ws").sorted())
        // 只读：无锁
        assertEquals(emptyList<String>(), scheduler.lockKeysFor("readFile", "s1", null, "/ws"))
        // terminal 不承载命令时（close/read/key）不触发构建锁
        assertEquals(listOf("sess:s1"), scheduler.lockKeysFor("terminal", "s1", null, "/ws").sorted())
    }

    // ── 基于 capabilities 的判定（本次新增）────────────────────────────────────

    /**
     * 声明了能力集时，判定以能力为准：与变更能力集合无交集 → 只读（无锁并行）。
     * 这让「协议声明只读」的 MCP 工具（McpTool 在 readOnlyHint=true 时声明 READ_WORKSPACE）
     * 能跨会话并行，而不是因名字不在名单里被判为 FILE_MUTATING。
     */
    @Test
    fun declaredReadWorkspaceCapabilityIsReadOnly() {
        assertEquals(
            ToolMutation.READ_ONLY,
            classifyToolMutation("someMcpReadTool", setOf(ToolCapability.READ_WORKSPACE)),
        )
        // NETWORK_READ 属只读（web 类语义）
        assertEquals(
            ToolMutation.READ_ONLY,
            classifyToolMutation("someFetchTool", setOf(ToolCapability.NETWORK_READ)),
        )
        // 只读工具拿到的锁键为空
        assertEquals(
            emptyList<String>(),
            ToolBatchScheduler().lockKeysFor(
                "someMcpReadTool", "s1", null, "/ws", setOf(ToolCapability.READ_WORKSPACE),
            ),
        )
    }

    /**
     * 关键回归：同时声明读+写的工具**绝不能**判为只读，否则同批并发写会静默覆盖。
     * 这正是简单用 `capabilities.contains(READ_WORKSPACE)` 判定会踩的坑（如 MemoryTool）。
     */
    @Test
    fun toolWithBothReadAndWriteCapabilitiesIsMutating() {
        assertEquals(
            ToolMutation.FILE_MUTATING,
            classifyToolMutation(
                "memory",
                setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG),
            ),
        )
        assertEquals(
            ToolMutation.FILE_MUTATING,
            classifyToolMutation(
                "generateImage",
                setOf(ToolCapability.NETWORK_WRITE, ToolCapability.WRITE_WORKSPACE),
            ),
        )
        // MCP 工具的默认能力 EXTERNAL_TOOL 含变更语义 → 仍串行
        assertEquals(
            ToolMutation.FILE_MUTATING,
            classifyToolMutation("mcp__server__tool", setOf(ToolCapability.EXTERNAL_TOOL)),
        )
    }

    @Test
    fun declaredCapabilitiesTakePrecedenceOverBuiltinNameList() {
        // 名字在只读名单里，但显式声明了写能力 → 以能力为准（保守，串行）
        assertEquals(
            ToolMutation.FILE_MUTATING,
            classifyToolMutation("search", setOf(ToolCapability.WRITE_WORKSPACE)),
        )
        // 名字不在名单里，但显式声明了只读能力 → 并行
        assertEquals(
            ToolMutation.READ_ONLY,
            classifyToolMutation("customReadOnly", setOf(ToolCapability.READ_WORKSPACE)),
        )
        // 能力集为空 → 回退按名判定（不改变原有行为）
        assertEquals(ToolMutation.READ_ONLY, classifyToolMutation("readFile"))
        assertEquals(ToolMutation.COMMAND, classifyToolMutation("Bash"))
        assertEquals(ToolMutation.FILE_MUTATING, classifyToolMutation("mcp__srv__x"))
    }

    @Test
    fun readOnlyCapabilityToolsRunInParallelAcrossSessions(): Unit = runBlocking {
        val probe = Probe()
        val scheduler = ToolBatchScheduler()
        coroutineScope {
            listOf("s1", "s2", "s3").map { session ->
                async {
                    scheduler.dispatch(
                        calls = listOf("mcp__a__read"),
                        sessionKey = session,
                        workspaceKey = "/ws",
                        toolNameOf = { it },
                        capabilitiesOf = { setOf(ToolCapability.READ_WORKSPACE) },
                    ) {
                        probe.begin()
                        try { delay(40) } finally { probe.end() }
                        1
                    }
                }
            }.awaitAll()
        }
        assertTrue("声明只读能力的工具应跨会话并行，实测峰值=${probe.peak()}", probe.peak() > 1)
    }

    @Test
    fun unspecifiedCapabilityToolsStillSerializeWithinSession(): Unit = runBlocking {
        val probe = Probe()
        val scheduler = ToolBatchScheduler()
        scheduler.dispatch(
            calls = listOf("mcp__a__x", "mcp__b__y", "mcp__c__z"),
            sessionKey = "s1",
            workspaceKey = "/ws",
            toolNameOf = { it },
            capabilitiesOf = { emptySet() },   // 未声明能力 → 回退按名 → 未知 → FILE_MUTATING
        ) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertEquals("未声明能力的未知工具仍串行（fail-closed）", 1, probe.peak())
    }

    // ── 同路径「读先于写」依赖排序（T3-A）─────────────────────────────────

    /** 依赖表：写工具要等同路径的读工具完成。 */
    @Test
    fun readBeforeWriteWaits_buildsDependencyOnSamePath() {
        val calls = listOf(
            Call("readFile", "/a.kt"),
            Call("editFile", "/a.kt"),
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals("读工具无依赖", emptyList<Int>(), waits[0])
        assertEquals("edit 必须等 read", listOf(0), waits[1])
    }

    @Test
    fun readBeforeWriteWaits_noDependencyAcrossDifferentPaths() {
        val calls = listOf(
            Call("readFile", "/a.kt"),
            Call("editFile", "/b.kt"),   // 不同路径 → 不建边
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals(emptyList<Int>(), waits[1])
    }

    @Test
    fun readBeforeWriteWaits_writeBeforeReadInList_stillWaits() {
        // 输入里 edit 在前、read 在后（典型样本：同一条消息里先写 edit 参数再写 read 参数）。
        // 必须**全批扫描**而不是只向前看，否则这种排列仍会 NOT_READ 白失败。
        val calls = listOf(
            Call("editFile", "/a.kt"),
            Call("readFile", "/a.kt"),
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals("edit 必须等本批的 read（不管出场顺序）", listOf(1), waits[0])
        assertEquals(emptyList<Int>(), waits[1])
    }

    @Test
    fun readBeforeWriteWaits_nullPath_neverBuildsEdge() {
        val calls = listOf(
            Call("readFile", null),
            Call("editFile", null),
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals(emptyList<Int>(), waits[1])
    }

    @Test
    fun readBeforeWriteWaits_nonFileToolsIgnored() {
        val calls = listOf(
            Call("readFile", "/a.kt"),
            Call("Bash", "/a.kt"),        // 命令类不参与
            Call("editFile", "/a.kt"),
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals(emptyList<Int>(), waits[1])
        assertEquals("edit 只等 read，不等 Bash", listOf(0), waits[2])
    }

    @Test
    fun readBeforeWriteWaits_multipleReads_allAwaited() {
        val calls = listOf(
            Call("readFile", "/a.kt"),
            Call("readFile", "/a.kt"),    // 同路径两次读（分批读长文件）
            Call("editFile", "/a.kt"),
        )
        val waits = readBeforeWriteWaits(calls, { it.name }, { it.path })
        assertEquals(listOf(0, 1), waits[2])
    }

    /**
     * 关键行为回归：edit 必须等 read **完成**后才执行。
     * 这正是 8 天 93 次 NOT_READ（58 个批次 edit 先跑）的修复点。
     */
    @Test
    fun dispatch_readCompletesBeforeEditOnSamePath(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val order = mutableListOf<String>()
        val calls = listOf(
            Call("editFile", "/a.kt"),
            Call("readFile", "/a.kt"),
            Call("editFile", "/a.kt"),
        )
        // 故意让第一个 edit 排在最前：若没有依赖排序，它会先于 read 执行
        scheduler.dispatch(
            calls = calls,
            sessionKey = "s1",
            workspaceKey = "/ws",
            toolNameOf = { it.name },
            pathOf = { it.path },
        ) { call ->
            order += "${call.name}:${call.path}"
            1
        }
        // read 必须先于两个 edit 完成
        val firstEdit = order.indexOfFirst { it.startsWith("editFile") }
        val readAt = order.indexOfFirst { it.startsWith("readFile") }
        assertTrue("read 必须先于 edit 执行，实测顺序=$order", readAt in 0 until firstEdit)
    }

    /**
     * 读失败也不能让依赖它的写永久挂起（finally 里必须发信号）。
     *
     * 「失败」用**返回值**表达而不是抛异常：生产里 readFile 失败由
     * `StatefulAgentWorkflow.runToolSync` 的 catch 收成 ToolRunResult(TOOL_EXECUTION_FAILED)，
     * exec 本身永不抛。用 error() 模拟会断言一个生产里不存在的语义——async 的异常会经
     * awaitAll 重抛并取消兄弟协程，dispatch 随之抛出，这条用例拿到的是
     * IllegalStateException 而不是 assertEquals 的结果（实测 3/3 复现）。
     */
    @Test
    fun dispatch_failedReadDoesNotBlockDependentEdit(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val executed = mutableListOf<String>()
        val results = mutableListOf<String>()
        val calls = listOf(Call("readFile", "/a.kt"), Call("editFile", "/a.kt"))
        scheduler.dispatch(
            calls = calls,
            sessionKey = "s1",
            workspaceKey = "/ws",
            toolNameOf = { it.name },
            pathOf = { it.path },
        ) { call ->
            executed += call.name
            if (call.name == "readFile") "FAIL" else "OK"
        }.also { results += it }
        assertEquals("两个调用都应执行完（读失败不阻塞写）", listOf("readFile", "editFile"), executed)
        assertEquals("读失败以结果值传递，不影响写", listOf("FAIL", "OK"), results)
    }

    private data class Call(val name: String, val path: String?)
}
