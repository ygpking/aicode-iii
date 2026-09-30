package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run 级累积预算测试。
 *
 * 重点：**未启用时必须完全透明**（恒放行、不改变任何行为）——这是本类能安全合入的前提，
 * 因为 AiCode 此前没有 run 级预算。以及「预留/提交/回滚」三段式在异常路径下不能漏算。
 */
class ToolRunBudgetTest {

    @Test
    fun disabled_beforeReserveAlwaysGranted() {
        val budget = ToolRunBudget(ToolRunBudget.DISABLED)
        assertFalse(budget.enabled)
        repeat(100) {
            assertTrue("未启用时恒放行", budget.reserve(1_000_000).granted)
        }
        assertEquals("未启用时不累计", 0L, budget.committedChars)
        assertEquals(Long.MAX_VALUE, budget.remainingChars())
    }

    @Test
    fun negativeMax_isAlsoDisabled() {
        assertFalse(ToolRunBudget(-1).enabled)
    }

    @Test
    fun enabled_accumulatesUntilLimit() {
        val budget = ToolRunBudget(100)
        assertTrue(budget.reserve(60).granted)
        assertEquals(60L, budget.committedChars)
        assertTrue("还能容纳 40", budget.reserve(40).granted)
        assertEquals(100L, budget.committedChars)
        assertFalse("再加就超了", budget.reserve(1).granted)
        assertEquals("超限后不再累加，避免无界增长", 100L, budget.committedChars)
        assertEquals(0L, budget.remainingChars())
    }

    @Test
    fun reserve_zeroOrNegativeAmount_isTreatedAsZero() {
        val budget = ToolRunBudget(10)
        assertTrue(budget.reserve(-5).granted)
        assertEquals(0L, budget.committedChars)
    }

    @Test
    fun commit_adjustsByDelta() {
        val budget = ToolRunBudget(1_000)
        budget.reserve(100)          // 预留 100
        budget.commit(reservedChars = 100, actualChars = 250)  // 实际更多
        assertEquals(250L, budget.committedChars)
        budget.commit(reservedChars = 0, actualChars = -50)     // 负数按 0
        assertEquals(250L, budget.committedChars)
    }

    @Test
    fun commit_canReduceWhenActualIsLess() {
        val budget = ToolRunBudget(1_000)
        budget.reserve(500)
        budget.commit(reservedChars = 500, actualChars = 100)
        assertEquals(100L, budget.committedChars)
        assertEquals(900L, budget.remainingChars())
    }

    @Test
    fun rollback_returnsReservedAmount() {
        val budget = ToolRunBudget(1_000)
        budget.reserve(400)
        assertEquals(400L, budget.committedChars)
        budget.rollback(400)
        assertEquals("回滚后应完全释放", 0L, budget.committedChars)
        assertEquals(1_000L, budget.remainingChars())
    }

    @Test
    fun rollback_neverGoesNegative() {
        val budget = ToolRunBudget(1_000)
        budget.reserve(100)
        budget.rollback(9_999)
        assertEquals(0L, budget.committedChars)
    }

    @Test
    fun disabled_commitAndRollbackAreNoops() {
        val budget = ToolRunBudget(ToolRunBudget.DISABLED)
        budget.reserve(999_999)
        budget.commit(reservedChars = 0, actualChars = 999_999)
        budget.rollback(999_999)
        assertEquals(0L, budget.committedChars)
    }
}
