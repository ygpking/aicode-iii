package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreflightTest {

    @Test
    fun partitionKeepsOrderAndSplitsOverflow() {
        val (run, skipped) = partitionByRoundLimit(listOf(1, 2, 3, 4, 5), limit = 3)
        assertEquals(listOf(1, 2, 3), run)
        assertEquals(listOf(4, 5), skipped)
    }

    @Test
    fun partitionUnderLimitSkipsNothing() {
        val (run, skipped) = partitionByRoundLimit(listOf("a", "b"), limit = 12)
        assertEquals(listOf("a", "b"), run)
        assertTrue(skipped.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun partitionRejectsNonPositiveLimit() {
        partitionByRoundLimit(listOf(1), limit = 0)
    }

    @Test
    fun guidanceSuggestsNearCandidate() {
        val guidance = unknownToolGuidance("readFilee", setOf("readFile", "writeFile", "list"))
        assertTrue("应给出最近候选 readFile，实际=$guidance", guidance.contains("readFile"))
        assertTrue(guidance.contains("当前可用工具"))
    }

    @Test
    fun guidanceListsAllToolsWhenNoCandidate() {
        val guidance = unknownToolGuidance("zzzzzzzz", setOf("readFile", "list"))
        assertTrue(guidance.contains("工具 zzzzzzzz 不存在"))
        assertTrue(guidance.contains("readFile"))
        assertTrue(guidance.contains("list"))
    }

    @Test
    fun guidanceHandlesEmptyRegistry() {
        val guidance = unknownToolGuidance("foo", emptySet())
        assertEquals("工具 foo 不存在。", guidance)
    }
}
