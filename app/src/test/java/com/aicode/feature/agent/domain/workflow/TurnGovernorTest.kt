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
        assertEquals("第3轮=段尾，应收束", TurnVerdict.InjectWrapUpNotice, g.beginTurn())
        // 进入第2段
        assertEquals(TurnVerdict.InjectWrapUpNotice, g.beginTurn())
    }

    @Test
    fun hardStopAfterSegmentsExhausted() {
        val g = TurnGovernor(roundsPerSegment = 2, maxContinuations = 1)
        // 第1段：轮1 Continue，轮2 收束
        assertEquals(TurnVerdict.Continue, g.beginTurn())
        assertEquals(TurnVerdict.InjectWrapUpNotice, g.beginTurn())
        // 续跑第2段：跨段收束提示，然后轮1 Continue
        assertEquals(TurnVerdict.InjectWrapUpNotice, g.beginTurn())
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
