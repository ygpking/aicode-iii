package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSpillBudgetTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun stateCountsAliveBytesAndExpiredSeparately() {
        val budget = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 10, ttlMs = 100)
        val entries = listOf(
            SpillEntry("a", 100, t0, "Bash"),
            SpillEntry("b", 200, t0 - 10, "readFile"),
            SpillEntry("stale", 999, t0 - 200, "Bash"),
        )
        val s = budget.state(entries, t0)
        assertEquals(300, s.totalBytes)
        assertEquals(2, s.fileCount)
        assertEquals(1, s.expiredCount)
        assertTrue(!s.overBudget)
    }

    @Test
    fun stateFlagsOverBudget() {
        val budget = OutputSpillBudget(maxTotalBytes = 100, maxFiles = 10, ttlMs = 1000)
        val s = budget.state(listOf(SpillEntry("big", 101, t0, "Bash")), t0)
        assertTrue(s.overBudget)

        val byCount = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 1, ttlMs = 1000)
        val s2 = byCount.state(
            listOf(SpillEntry("a", 1, t0, "Bash"), SpillEntry("b", 1, t0, "Bash")),
            t0,
        )
        assertTrue("条目数超限也应标 overBudget", s2.overBudget)
    }

    @Test
    fun ttlExpiredEntriesAreDeletedEvenWhenUnderBudget() {
        val budget = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 10, ttlMs = 100)
        val entries = listOf(
            SpillEntry("fresh", 10, t0, "Bash"),
            SpillEntry("stale", 10, t0 - 101, "Bash"),
        )
        assertEquals(listOf("stale"), budget.decide(entries, t0))
    }

    @Test
    fun entryExactlyAtTtlBoundaryIsKept() {
        val budget = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 10, ttlMs = 100)
        val entries = listOf(SpillEntry("edge", 10, t0 - 100, "Bash"))
        assertTrue(budget.decide(entries, t0).isEmpty())
    }

    @Test
    fun oldestIsEvictedFirstWhenFileCountExceeded() {
        val budget = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 2, ttlMs = 1000)
        val entries = listOf(
            SpillEntry("c", 1, t0, "Bash"),
            SpillEntry("a", 1, t0 - 30, "Bash"),
            SpillEntry("b", 1, t0 - 20, "Bash"),
        )
        assertEquals(listOf("a"), budget.decide(entries, t0))
    }

    @Test
    fun byteBudgetExactFitTriggersNoDeletion() {
        val budget = OutputSpillBudget(maxTotalBytes = 100, maxFiles = 10, ttlMs = 1000)
        val entries = listOf(
            SpillEntry("a", 60, t0 - 10, "Bash"),
            SpillEntry("b", 40, t0, "Bash"),
        )
        assertTrue(budget.decide(entries, t0).isEmpty())
    }

    @Test
    fun byteBudgetOverflowEvictsOldestUntilItFits() {
        val budget = OutputSpillBudget(maxTotalBytes = 100, maxFiles = 10, ttlMs = 1000)
        val entries = listOf(
            SpillEntry("a", 60, t0 - 10, "Bash"),
            SpillEntry("b", 40, t0 - 5, "Bash"),
            SpillEntry("c", 50, t0, "Bash"),
        )
        assertEquals(listOf("a"), budget.decide(entries, t0))
    }

    @Test
    fun bothBudgetsCanForceMultipleEvictions() {
        val budget = OutputSpillBudget(maxTotalBytes = 60, maxFiles = 2, ttlMs = 1000)
        val entries = listOf(
            SpillEntry("a", 40, t0 - 30, "Bash"),
            SpillEntry("b", 40, t0 - 20, "Bash"),
            SpillEntry("c", 40, t0 - 10, "Bash"),
        )
        assertEquals(listOf("a", "b"), budget.decide(entries, t0))
    }

    @Test
    fun sameTimestampEvictionIsDeterministicByKey() {
        val budget = OutputSpillBudget(maxTotalBytes = 1000, maxFiles = 1, ttlMs = 1000)
        val entries = listOf(
            SpillEntry("zeta", 1, t0, "Bash"),
            SpillEntry("alpha", 1, t0, "Bash"),
        )
        assertEquals(listOf("alpha"), budget.decide(entries, t0))
    }

    @Test
    fun emptyInputYieldsNoEvictions() {
        val budget = OutputSpillBudget()
        assertTrue(budget.decide(emptyList(), t0).isEmpty())
    }

    @Test
    fun directionFollowsToolKind() {
        val budget = OutputSpillBudget()
        assertEquals(Direction.TAIL, budget.headTailDirection("Bash"))
        assertEquals(Direction.TAIL, budget.headTailDirection("terminal"))
        assertEquals(Direction.HEAD_TAIL, budget.headTailDirection("readFile"))
        assertEquals(Direction.HEAD, budget.headTailDirection("webfetch"))
        assertEquals(Direction.HEAD, budget.headTailDirection("list"))
    }

    @Test
    fun defaultBudgetsMatchSpecifiedLimits() {
        assertEquals(64L * 1024 * 1024, OutputSpillBudget.DEFAULT_MAX_TOTAL_BYTES)
        assertEquals(256, OutputSpillBudget.DEFAULT_MAX_FILES)
        assertEquals(7L * 24 * 60 * 60 * 1000, OutputSpillBudget.DEFAULT_TTL_MS)
    }
}
