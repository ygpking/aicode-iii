package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureCircuitBreakerTest {

    @Test
    fun warnsOnceAtSoftThreshold() {
        val b = FailureCircuitBreaker(softThreshold = 3, hardThreshold = 8)
        assertEquals(BreakerState.Closed, b.record(true))
        assertEquals(BreakerState.Closed, b.record(true))
        assertEquals("达到软阈应提示一次", BreakerState.WarnedOnce, b.record(true))
        assertEquals("软提示只应返回一次", BreakerState.Closed, b.record(true))
    }

    @Test
    fun tripsAtHardThreshold() {
        val b = FailureCircuitBreaker(softThreshold = 3, hardThreshold = 5)
        var last: BreakerState = BreakerState.Closed
        repeat(5) { last = b.record(true) }
        assertTrue("连续5次应熔断", last is BreakerState.Tripped)
        assertEquals(5, (last as BreakerState.Tripped).consecutiveFailures)
    }

    @Test
    fun successResetsStreakAndWarnFlag() {
        val b = FailureCircuitBreaker(softThreshold = 3, hardThreshold = 8)
        b.record(true); b.record(true); b.record(true) // WarnedOnce
        assertEquals(BreakerState.Closed, b.record(false)) // 成功清零
        assertEquals(0, b.consecutiveFailures)
        // 重新累计到软阈应再次提示
        b.record(true); b.record(true)
        assertEquals(BreakerState.WarnedOnce, b.record(true))
    }

    @Test
    fun resetClears() {
        val b = FailureCircuitBreaker()
        b.record(true); b.record(true)
        b.reset()
        assertEquals(0, b.consecutiveFailures)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdsRejected() {
        FailureCircuitBreaker(softThreshold = 5, hardThreshold = 3)
    }
}
