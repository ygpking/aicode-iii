package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [EventTrace] 的回合隔离契约（并发会话 turnId 碰撞的回归护栏）。
 *
 * 背景：`turnId` 只在**会话内**唯一（`t1`/`t2`…），故按回合索引的内部状态必须用
 * `scope/turnId` 组合键。真机复现过两个同秒开启的 `t1` 互相干扰：一方 [EventTrace.endTurn]
 * 会连带清掉另一方的活跃映射，使其后续事件被误判为「回合已收尾」而丢弃。
 *
 * 这些用例只依赖纯内存逻辑（[FileLogger.minLevel] 默认 VERBOSE → 记录开启），
 * 故用 Robolectric 仅为满足 `android.util.Log` 的类加载，不触碰文件系统。
 */
@RunWith(RobolectricTestRunner::class)
class EventTraceTurnIsolationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    /** 两个会话各自的 `t1` 必须拿到**独立**的 seq 序列，不得共享计数器。 */
    @Test
    fun concurrentTurns_doNotShareSeqCounter() {
        val a = "aaaa1111-0000-0000-0000-000000000001"
        val b = "bbbb2222-0000-0000-0000-000000000002"

        val turnA = EventTrace.beginTurn(a)
        val turnB = EventTrace.beginTurn(b)
        assertEquals("会话内编号，两个会话都应是 t1", "t1", turnA)
        assertEquals("t1", turnB)

        // A 记 3 条 → seq 1(轮次开始),2,3
        assertEquals(2L, EventTrace.record(turnA, a, "EVENT", "a-1"))
        assertEquals(3L, EventTrace.record(turnA, a, "EVENT", "a-2"))
        // B 的 seq 从自己的计数器继续，不受 A 影响
        assertEquals("B 的 seq 应为 2，说明未与 A 共享", 2L, EventTrace.record(turnB, b, "EVENT", "b-1"))

        EventTrace.endTurn(turnA, a, "test")
        EventTrace.endTurn(turnB, b, "test")
    }

    /**
     * 关键回归：先结束的会话**不得**连带清掉另一会话的活跃映射。
     *
     * 修复前 `endTurn` 用 `activeTurns.entries.removeIf { it.value == turnId }`，
     * 因两会话 turnId 都是 `t1`，会一并删除对方映射 → 对方后续 `record` 被判「回合已收尾」丢弃。
     */
    @Test
    fun endTurn_doesNotDropOtherSessionActiveTurn() {
        val a = "cccc3333-0000-0000-0000-000000000003"
        val b = "dddd4444-0000-0000-0000-000000000004"

        val turnA = EventTrace.beginTurn(a)
        val turnB = EventTrace.beginTurn(b)

        // A 先收尾
        EventTrace.endTurn(turnA, a, "test")

        // B 仍在进行：其活跃映射必须还在，且记录不该被丢弃（返回非 null）
        assertEquals("B 的活跃回合不应被 A 的 endTurn 清掉", turnB, EventTrace.currentTurnOf(b))
        assertNotNull(
            "B 收尾前后仍应能记录事件",
            EventTrace.record(turnB, b, "EVENT", "b-after-a-ended")
        )

        EventTrace.endTurn(turnB, b, "test")
    }

    /** `endTurn` 之后，本会话的回合确已收尾：再记录应被拒（返回 null）。 */
    @Test
    fun recordAfterEndTurn_isRejected() {
        val s = "eeee5555-0000-0000-0000-000000000005"
        val turn = EventTrace.beginTurn(s)
        EventTrace.endTurn(turn, s, "test")

        assertNull("回合已收尾后不应再记录", EventTrace.record(turn, s, "EVENT", "late"))
        assertNull("活跃映射应已摘除", EventTrace.currentTurnOf(s))
    }

    /** 同会话连开两回合：编号递增，且旧回合的收尾不影响新回合。 */
    @Test
    fun sameSession_secondTurnGetsIncrementedId() {
        val s = "ffff6666-0000-0000-0000-000000000006"
        val t1 = EventTrace.beginTurn(s)
        EventTrace.endTurn(t1, s, "test")
        val t2 = EventTrace.beginTurn(s)

        assertEquals("t1", t1)
        assertEquals("t2", t2)
        assertEquals(2L, EventTrace.record(t2, s, "EVENT", "second-turn"))
        assertTrue(EventTrace.currentTurnOf(s) == t2)
        EventTrace.endTurn(t2, s, "test")
    }
}
