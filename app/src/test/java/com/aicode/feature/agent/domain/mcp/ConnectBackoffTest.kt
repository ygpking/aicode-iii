package com.aicode.feature.agent.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectBackoffTest {

    private var nowMs = 1_000L
    private fun backoff() = ConnectBackoff(clock = { nowMs })

    @Test
    fun firstFailureWaitsBaseDelay() {
        val b = backoff()
        assertEquals(30_000L, b.onFailure("srv"))
    }

    @Test
    fun delayDoublesOnEachConsecutiveFailure() {
        val b = backoff()
        assertEquals(30_000L, b.onFailure("srv"))
        assertEquals(60_000L, b.onFailure("srv"))
        assertEquals(120_000L, b.onFailure("srv"))
        assertEquals(240_000L, b.onFailure("srv"))
    }

    @Test
    fun delayIsCappedAtFiveMinutes() {
        val b = backoff()
        repeat(4) { b.onFailure("srv") } // 30s,60s,120s,240s
        assertEquals(300_000L, b.onFailure("srv"))
        assertEquals(300_000L, b.onFailure("srv"))
    }

    @Test
    fun successClearsCooldown() {
        val b = backoff()
        b.onFailure("srv")
        b.onFailure("srv")
        assertEquals(120_000L, b.onFailure("srv"))
        b.onSuccess("srv")
        // 清零后重新从首次计起
        assertEquals(30_000L, b.onFailure("srv"))
    }

    @Test
    fun delayUntilCountsDownAndReachesZero() {
        val b = backoff()
        b.onFailure("srv") // nowMs=1000, blocked until 31000
        assertEquals(30_000L, b.delayUntil("srv", 1_000L))
        assertEquals(11_000L, b.delayUntil("srv", 20_000L))
        assertEquals(0L, b.delayUntil("srv", 31_000L))
        assertEquals(0L, b.delayUntil("srv", 99_999L))
    }

    @Test
    fun unknownServerHasNoDelay() {
        val b = backoff()
        assertEquals(0L, b.delayUntil("never-failed", 123_456L))
    }

    @Test
    fun serversAreIsolated() {
        val b = backoff()
        b.onFailure("a")
        assertEquals(30_000L, b.onFailure("b"))
        assertEquals(30_000L, b.delayUntil("a", nowMs))
        assertEquals(30_000L, b.delayUntil("b", nowMs))

        b.onSuccess("a")
        assertEquals(0L, b.delayUntil("a", nowMs))
        // b 仍处于冷却
        assertEquals(30_000L, b.delayUntil("b", nowMs))
    }

    @Test
    fun clearOneServerLeavesOthersCooling() {
        val b = backoff()
        b.onFailure("a")
        b.onFailure("b")
        b.clear("a")
        assertEquals(0L, b.delayUntil("a", nowMs))
        assertEquals(30_000L, b.delayUntil("b", nowMs))
    }

    @Test
    fun clearAllResetsEveryone() {
        val b = backoff()
        b.onFailure("a")
        b.onFailure("b")
        b.clear()
        assertEquals(0L, b.delayUntil("a", nowMs))
        assertEquals(0L, b.delayUntil("b", nowMs))
    }

    @Test
    fun defaultConstantsMatchSpec() {
        assertEquals(30_000L, ConnectBackoff.DEFAULT_BASE_DELAY_MS)
        assertEquals(300_000L, ConnectBackoff.DEFAULT_MAX_DELAY_MS)
    }
}
