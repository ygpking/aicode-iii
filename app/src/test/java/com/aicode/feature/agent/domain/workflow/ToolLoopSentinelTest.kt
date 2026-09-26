package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopSentinelTest {

    @Test
    fun repeatedFailureDetected() {
        val s = ToolLoopSentinel(repeatFailureLimit = 3, idleLimit = 4, blockThreshold = 2)
        assertEquals(LoopVerdict.Ok, s.observe("readFile", "p", true))
        assertEquals(LoopVerdict.Ok, s.observe("readFile", "p", true))
        val v = s.observe("readFile", "p", true)
        assertTrue(v is LoopVerdict.SuspectedLoop)
        assertEquals(LoopReason.REPEATED_FAILURE, (v as LoopVerdict.SuspectedLoop).reason)
    }

    @Test
    fun successBreaksRepeatedFailure() {
        // idleLimit 设高以隔离「重复失败」判定
        val s = ToolLoopSentinel(repeatFailureLimit = 3, idleLimit = 99, oscillationWindow = 99, blockThreshold = 2)
        s.observe("readFile", "p", true)
        s.observe("readFile", "p", false) // 成功打断
        s.observe("readFile", "p", true)
        assertEquals(LoopVerdict.Ok, s.observe("readFile", "p", true))
    }

    @Test
    fun idleNoProgressDetected() {
        val s = ToolLoopSentinel(repeatFailureLimit = 99, idleLimit = 4, blockThreshold = 2)
        // 同一工具同参、即便成功，连续重复也判空转
        s.observe("search", "same", false)
        s.observe("search", "same", false)
        s.observe("search", "same", false)
        val v = s.observe("search", "same", false)
        assertTrue(v is LoopVerdict.SuspectedLoop)
        assertEquals(LoopReason.NO_PROGRESS_IDLE, (v as LoopVerdict.SuspectedLoop).reason)
    }

    @Test
    fun oscillationDetected() {
        val s = ToolLoopSentinel(repeatFailureLimit = 99, idleLimit = 99, oscillationWindow = 6, maxPeriod = 2, blockThreshold = 2)
        // A,B,A,B,A,B
        s.observe("A", "1", false)
        s.observe("B", "2", false)
        s.observe("A", "1", false)
        s.observe("B", "2", false)
        s.observe("A", "1", false)
        val v = s.observe("B", "2", false)
        assertTrue("应检出周期震荡，实际=$v", v is LoopVerdict.SuspectedLoop)
        assertEquals(LoopReason.PERIODIC_OSCILLATION, (v as LoopVerdict.SuspectedLoop).reason)
    }

    @Test
    fun escalatesToBlockedAfterRepeatedSuspicion() {
        val s = ToolLoopSentinel(repeatFailureLimit = 2, idleLimit = 99, blockThreshold = 2)
        s.observe("x", "f", true)
        val first = s.observe("x", "f", true) // 第1次 Suspected
        assertTrue(first is LoopVerdict.SuspectedLoop)
        val second = s.observe("x", "f", true) // 第2次 → Blocked
        assertTrue("累计怀疑应升级为 Blocked，实际=$second", second is LoopVerdict.Blocked)
    }

    @Test
    fun distinctCallsAreOk() {
        val s = ToolLoopSentinel()
        assertEquals(LoopVerdict.Ok, s.observe("readFile", "a", false))
        assertEquals(LoopVerdict.Ok, s.observe("writeFile", "b", false))
        assertEquals(LoopVerdict.Ok, s.observe("search", "c", false))
    }
}
