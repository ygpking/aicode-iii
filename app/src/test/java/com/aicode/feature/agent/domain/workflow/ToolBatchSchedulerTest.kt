package com.aicode.feature.agent.domain.workflow

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
}
