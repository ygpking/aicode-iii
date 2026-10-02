package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合外**观察类**记录（UI / SNAPSHOT）不应被丢弃。
 *
 * 背景（真机实测）：`TRACE_DROPPED` 全部是 `[UI] 尾巴`——那处打点的用途是诊断
 * 「回合结束后，尾巴气泡为何没隐藏」，而它**恰恰发生在回合结束之后**，此时
 * `endTurn` 已摘掉 `activeTurns`，于是每条都被丢弃。不是偶发时序问题，是必然结果。
 *
 * 修法：观察类（`UI` / `SNAPSHOT*`）在无活跃回合时挂到虚拟回合 `t0`；动作类
 * （`TOOL` / `EVENT`）保持原语义——它们本该挂在回合上，缺失即异常，仍应告警丢弃。
 *
 * 纯 JVM 测试：`enabled` 由 `FileLogger.minLevel` 决定（默认非 NONE），落盘在
 * `logDir == null` 时直接跳过，故不触碰 Android API。每个用例用独立 scope 避免全局状态串扰。
 */
class EventTraceOutOfTurnTest {

    private fun scopeOf(tag: String) = "test-$tag-0000-0000-0000-000000000000"

    @Test
    fun uiRecordWithoutActiveTurn_isRecorded() {
        val scope = scopeOf("ui-no-turn")

        val seq = EventTrace.recordFor(scope, "UI", "尾巴 busy=false")

        assertNotNull("回合外的 UI 观察不该被丢弃", seq)
        assertTrue("应分配到正整数 seq", seq!! >= 1L)
    }

    @Test
    fun snapshotRecordWithoutActiveTurn_isRecorded() {
        val scope = scopeOf("snapshot-no-turn")

        val seq = EventTrace.recordFor(scope, "SNAPSHOT/panel", "仍有 1 个面板未清理")

        assertNotNull("回合外的收尾快照不该被丢弃", seq)
    }

    @Test
    fun actionLayersStillDroppedWithoutActiveTurn() {
        val scope = scopeOf("action-no-turn")

        // 动作类层缺失回合属异常，应保持丢弃（不因本次修复被顺手放行）
        assertNull("TOOL 层在无活跃回合时应仍被丢弃", EventTrace.recordFor(scope, "TOOL", "some tool"))
        assertNull("EVENT 层在无活跃回合时应仍被丢弃", EventTrace.recordFor(scope, "EVENT", "some event"))
        assertNull("TURN 层在无活跃回合时应仍被丢弃", EventTrace.recordFor(scope, "TURN", "some turn"))
    }

    @Test
    fun realTurnKeepsPriorityOverOutOfTurn() {
        val scope = scopeOf("turn-priority")
        val turnId = EventTrace.beginTurn(scope)
        try {
            // 有活跃回合时，UI 记录应挂在真实回合上（不再走 t0）
            val seq = EventTrace.recordFor(scope, "UI", "回合内界面跳变")
            assertNotNull(seq)
        } finally {
            EventTrace.endTurn(turnId, scope, "done")
        }
    }

    @Test
    fun outOfTurnSeqIsIndependentPerScope() {
        val a = scopeOf("indep-a")
        val b = scopeOf("indep-b")

        val seqA = EventTrace.recordFor(a, "UI", "状态 X")
        val seqB = EventTrace.recordFor(b, "UI", "状态 Y")

        assertNotNull(seqA)
        assertNotNull(seqB)
        // 不同会话各自的 seq 空间独立：都应从 1 开始
        assertEquals("不同会话的回合外 seq 应各自独立从 1 起", 1L, seqA)
        assertEquals("不同会话的回合外 seq 应各自独立从 1 起", 1L, seqB)
    }

    @Test
    fun beginTurnResetsOutOfTurnCounter() {
        val scope = scopeOf("reset")

        val first = EventTrace.recordFor(scope, "UI", "回合外状态 1")
        assertEquals(1L, first)

        // 新回合开始 = 上一轮的回合外观察翻页
        val turnId = EventTrace.beginTurn(scope)
        EventTrace.endTurn(turnId, scope, "done")

        val afterReset = EventTrace.recordFor(scope, "UI", "回合外状态 2")
        assertEquals("beginTurn 后 t0 的 seq 应重置并从 1 重新计数", 1L, afterReset)
    }

    @Test
    fun blankScopeIsIgnored() {
        assertNull("scope 为 null 时不应记录", EventTrace.recordFor(null, "UI", "无会话"))
    }
}
