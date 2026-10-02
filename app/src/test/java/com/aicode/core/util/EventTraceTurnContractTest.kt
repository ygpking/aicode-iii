package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EventTrace` 的回合隔离与收尾契约（**纯 JVM** 版本）。
 *
 * 为什么另建一份：[EventTraceTurnIsolationTest] 用 Robolectric（仅为满足 `android.util.Log`
 * 的类加载），而本容器（Android 上的 PRoot）**加载不了 Robolectric 的 conscrypt native 库**，
 * 于是那 4 个用例在这里恒为 `UnsatisfiedLinkError`——**契约被环境掩盖，改了逻辑也无从察觉**。
 *
 * 这些契约本身只依赖内存状态（`activeTurns` / `seqCounters`），`write()` 在 `logDir == null`
 * 时直接返回、不触碰 Android API，故完全可以纯 JVM 验证。保持两份是有意的：
 * Robolectric 版在 CI 上覆盖包含日志落盘的完整路径，本版保证**任何环境**下契约都可验证。
 */
class EventTraceTurnContractTest {

    private fun scope(tag: String) = "contract-$tag-0000-0000-0000-000000000000"

    /** 两个会话各自的 `t1` 必须拿到**独立**的 seq 序列，不得共享计数器。 */
    @Test
    fun concurrentTurns_doNotShareSeqCounter() {
        val a = scope("iso-a")
        val b = scope("iso-b")

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
     * 先结束的会话**不得**连带清掉另一会话的活跃映射。
     *
     * 修复前 `endTurn` 用 `activeTurns.entries.removeIf { it.value == turnId }`，
     * 因两会话 turnId 都是 `t1`，会一并删除对方映射 → 对方后续 `record` 被判「回合已收尾」丢弃。
     */
    @Test
    fun endTurn_doesNotDropOtherSessionActiveTurn() {
        val a = scope("iso-c")
        val b = scope("iso-d")

        val turnA = EventTrace.beginTurn(a)
        val turnB = EventTrace.beginTurn(b)
        EventTrace.endTurn(turnA, a, "test")

        assertEquals("B 的活跃回合不应被 A 的 endTurn 清掉", turnB, EventTrace.currentTurnOf(b))
        assertNotNull(
            "B 收尾前后仍应能记录事件",
            EventTrace.record(turnB, b, "EVENT", "b-after-a-ended")
        )

        EventTrace.endTurn(turnB, b, "test")
    }

    /** `endTurn` 之后，本会话的回合确已收尾：**动作类**记录应被拒（返回 null）。 */
    @Test
    fun recordAfterEndTurn_isRejected() {
        val s = scope("reject")
        val turn = EventTrace.beginTurn(s)
        EventTrace.endTurn(turn, s, "test")

        assertNull("回合已收尾后不应再记录动作类事件", EventTrace.record(turn, s, "EVENT", "late"))
        assertNull("活跃映射应已摘除", EventTrace.currentTurnOf(s))
    }

    /** 同会话连开两回合：编号递增，且旧回合的收尾不影响新回合。 */
    @Test
    fun sameSession_secondTurnGetsIncrementedId() {
        val s = scope("second")
        val t1 = EventTrace.beginTurn(s)
        EventTrace.endTurn(t1, s, "test")
        val t2 = EventTrace.beginTurn(s)

        assertEquals("t1", t1)
        assertEquals("t2", t2)
        assertEquals(2L, EventTrace.record(t2, s, "EVENT", "second-turn"))
        assertTrue(EventTrace.currentTurnOf(s) == t2)
        EventTrace.endTurn(t2, s, "test")
    }

    /**
     * 回合外的**观察类**记录走虚拟回合 `t0`，且 `t0` 不被 `endTurn` 影响——
     * 这是「尾巴打点」能落盘的根据（它的语义就是回合结束后的观察）。
     */
    @Test
    fun outOfTurnObservationSurvivesEndTurn() {
        val s = scope("t0-survive")

        val seq1 = EventTrace.recordFor(s, "UI", "回合外观察 1")
        assertNotNull(seq1)

        // 开一个真实回合再收尾：t0 的记录能力不应受影响
        val turn = EventTrace.beginTurn(s)
        EventTrace.endTurn(turn, s, "test")

        val seq2 = EventTrace.recordFor(s, "UI", "回合外观察 2")
        assertNotNull("endTurn 不应影响回合外观察的记录能力", seq2)
    }
}
