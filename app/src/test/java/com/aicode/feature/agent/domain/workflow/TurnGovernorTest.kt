package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnGovernorTest {

    @Test
    fun withinSegmentContinues() {
        val g = TurnGovernor(roundsPerSegment = 3, maxContinuations = 2)
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        assertEquals(TurnVerdict.Continue, g.beginTurn())
    }

    @Test
    fun segmentBoundaryRequestsWrapUpThenContinues() {
        val g = TurnGovernor(roundsPerSegment = 3, maxContinuations = 2)
        g.beginTurn(); g.beginTurn()
        // 段尾那轮照常执行（不再在段尾注入），新段开始前才注入一次
        assertEquals("第3轮=段尾轮，正常继续", TurnVerdict.Continue, g.beginTurn())
        assertEquals("新段开始前注入一次收束提示", TurnVerdict.InjectWrapUpNotice, g.beginTurn())
        // 新段第 1 轮正常继续
        assertEquals(TurnVerdict.Continue, g.beginTurn())
    }

    @Test
    fun hardStopAfterSegmentsExhausted() {
        val g = TurnGovernor(roundsPerSegment = 2, maxContinuations = 1)
        // 第1段：轮1、轮2 都 Continue（段尾轮照常执行）
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        // 跨段：新段开始前注入一次收束提示
        assertEquals(TurnVerdict.InjectWrapUpNotice, g.beginTurn())
        // 续跑第2段：轮1 Continue
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        // 第2段末（续跑额度已用尽）→ 硬停
        val v = g.beginTurn()
        assertTrue("第2段末应硬停，实际=$v", v is TurnVerdict.HardStop)
    }

    @Test
    fun zeroContinuationStopsAtFirstSegmentEnd() {
        val g = TurnGovernor(roundsPerSegment = 2, maxContinuations = 0)
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        val v = g.beginTurn()
        assertTrue(v is TurnVerdict.HardStop)
        assertEquals(StopReason.ROUNDS_EXHAUSTED, (v as TurnVerdict.HardStop).reason)
    }

    @Test
    fun remainingInSegmentNeverNegative() {
        val g = TurnGovernor(roundsPerSegment = 2, maxContinuations = 0)
        g.beginTurn(); g.beginTurn()
        g.beginTurn() // 已终止，再次调用
        assertTrue("剩余不应为负", g.remainingInSegment >= 0)
    }

    @Test
    fun segmentsUsedTracksContinuations() {
        val g = TurnGovernor(roundsPerSegment = 1, maxContinuations = 3)
        g.beginTurn() // 轮1=段尾
        g.beginTurn() // 续跑，段2
        assertEquals(1, g.segmentsUsed)
    }
}
