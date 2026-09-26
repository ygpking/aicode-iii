package com.aicode.feature.agent.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseSizeGovernorTest {

    @Test
    fun smallResponseIsInline() {
        assertEquals(SizeVerdict.Inline, ResponseSizeGovernor.decide(0))
        assertEquals(SizeVerdict.Inline, ResponseSizeGovernor.decide(1_024))
    }

    @Test
    fun exactlyInlineLimitIsInline() {
        assertEquals(SizeVerdict.Inline, ResponseSizeGovernor.decide(512L * 1024))
    }

    @Test
    fun justAboveInlineLimitSpills() {
        assertEquals(
            SizeVerdict.SpillToDisk(oversized = false),
            ResponseSizeGovernor.decide(512L * 1024 + 1),
        )
    }

    @Test
    fun exactlySpillLimitSpillsNormally() {
        assertEquals(
            SizeVerdict.SpillToDisk(oversized = false),
            ResponseSizeGovernor.decide(4L * 1024 * 1024),
        )
    }

    @Test
    fun justAboveSpillLimitSpillsWithWarning() {
        assertEquals(
            SizeVerdict.SpillToDisk(oversized = true),
            ResponseSizeGovernor.decide(4L * 1024 * 1024 + 1),
        )
    }

    @Test
    fun exactlyHardLimitSpillsWithWarning() {
        assertEquals(
            SizeVerdict.SpillToDisk(oversized = true),
            ResponseSizeGovernor.decide(8L * 1024 * 1024),
        )
    }

    @Test
    fun aboveHardLimitIsRejected() {
        assertEquals(SizeVerdict.Reject, ResponseSizeGovernor.decide(8L * 1024 * 1024 + 1))
        assertEquals(SizeVerdict.Reject, ResponseSizeGovernor.decide(Long.MAX_VALUE))
    }

    @Test
    fun thresholdsMatchSpec() {
        assertEquals(512L * 1024, ResponseSizeGovernor.INLINE_MAX_BYTES)
        assertEquals(4L * 1024 * 1024, ResponseSizeGovernor.SPILL_MAX_BYTES)
        assertEquals(8L * 1024 * 1024, ResponseSizeGovernor.HARD_MAX_BYTES)
    }
}
