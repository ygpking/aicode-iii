package com.aicode.feature.agent.domain.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具批调度器的并发语义测试：只读并行、变更串行、顺序保持、未知工具 fail-closed。
 */
class ToolBatchSchedulerTest {

    private class Probe {
        private val active = AtomicInteger(0)
        private val peak = AtomicInteger(0)
        fun begin(): Int = active.incrementAndGet().also { peak.updateAndGet { p -> maxOf(p, it) } }
        fun end() { active.decrementAndGet() }
        fun peak(): Int = peak.get()
    }

    @Test
    fun readOnlyToolsRunInParallel(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val probe = Probe()
        val calls = listOf("readFile", "readFile", "readFile", "readFile")
        scheduler.dispatch(calls, workspaceKey = "/ws", toolNameOf = { it }) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertTrue("只读工具应并行，实测峰值=${probe.peak()}", probe.peak() > 1)
    }

    @Test
    fun mutatingToolsRunSerially(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val probe = Probe()
        val calls = listOf("writeFile", "editFile", "Bash", "mcp__x__tool")
        scheduler.dispatch(calls, workspaceKey = "/ws", toolNameOf = { it }) {
            probe.begin()
            try { delay(40) } finally { probe.end() }
            1
        }
        assertEquals("变更工具必须串行", 1, probe.peak())
    }

    @Test
    fun unknownToolIsTreatedAsMutating() {
        assertEquals(ToolMutation.READ_ONLY, classifyToolMutation("readFile"))
        assertEquals(ToolMutation.MUTATING, classifyToolMutation("writeFile"))
        assertEquals(ToolMutation.MUTATING, classifyToolMutation("totallyUnknown"))
        assertEquals(ToolMutation.MUTATING, classifyToolMutation("mcp__server__tool"))
    }

    @Test
    fun resultsPreserveInputOrder(): Unit = runBlocking {
        val scheduler = ToolBatchScheduler()
        val calls = listOf("writeFile", "readFile", "editFile")
        val out = scheduler.dispatch(calls, workspaceKey = "/ws", toolNameOf = { it }) { name ->
            if (name == "readFile") delay(10)
            "done:$name"
        }
        assertEquals(listOf("done:writeFile", "done:readFile", "done:editFile"), out)
    }
}
