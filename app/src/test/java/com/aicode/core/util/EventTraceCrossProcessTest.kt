package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回合号**跨进程**不重号的契约。
 *
 * 背景（真机实测 2026-10-05）：`turnCounters` 是内存态，进程重启即清零，于是同一会话的 `t1`
 * 在每个进程段重新发放——单日 36/70 个回合桶被复用、7293/10671 条（68.4%）记录落在复用桶里，
 * 「`s=<会话> t<N> #<seq>`」这份被当作唯一标识的引用跨进程失效。修复后回合号从轨迹里回读的
 * 高水位继续递增。
 *
 * 纯 JVM：契约只依赖内存状态，无需 Robolectric（见 [EventTraceTurnContractTest] 的说明）。
 */
class EventTraceCrossProcessTest {

    private val scopeCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** 生成前 8 位**互不相同**的会话 id（高水位按短号索引，见 [floor_isPerSession]）。 */
    private fun scope(tag: String): String {
        val n = scopeCounter.incrementAndGet()
        return "%08x-0000-0000-0000-000000000000".format(n) + "-$tag"
    }

    /** 高水位生效时，同会话的回合号必须从高水位继续，而不是回到 t1。 */
    @Test
    fun turnId_continuesFromRestoredFloor() {
        val s = "cccccccc-3333-0000-0000-000000000000"
        // 模拟「上个进程已用到 t7」——真实实现从轨迹文件回读，此处直接注入同等的内存状态。
        EventTrace.setTurnFloorForTest(s.take(8), 7L)

        val turn = EventTrace.beginTurn(s)

        assertEquals("应从高水位继续，不得重发上个进程用过的号", "t8", turn)
        EventTrace.endTurn(turn, s, "test")
    }

    /** 无高水位（首次安装）时仍从 t1 起，不因修复而改变首次行为。 */
    @Test
    fun turnId_startsAtOneWithoutFloor() {
        val s = scope("fresh")

        val turn = EventTrace.beginTurn(s)

        assertEquals("无历史时首次回合仍应是 t1", "t1", turn)
        EventTrace.endTurn(turn, s, "test")
    }

    /**
     * 高水位按会话隔离：一个会话的历史不得抬高另一个会话的起点。
     *
     * 两个 id 的**前 8 位必须不同**——[EventTrace] 按短号（`scope.take(8)`）索引高水位，
     * 因为轨迹文件里只有短号。构造测试数据时若两者前 8 位相同，会人为制造短号碰撞。
     */
    @Test
    fun floor_isPerSession() {
        val a = "aaaaaaaa-1111-0000-0000-000000000000"
        val b = "bbbbbbbb-2222-0000-0000-000000000000"
        EventTrace.setTurnFloorForTest(a.take(8), 100L)

        val turnA = EventTrace.beginTurn(a)
        val turnB = EventTrace.beginTurn(b)

        assertEquals("A 从自己的高水位继续", "t101", turnA)
        assertEquals("B 不受 A 的高水位影响", "t1", turnB)
        EventTrace.endTurn(turnA, a, "test")
        EventTrace.endTurn(turnB, b, "test")
    }

    /**
     * 因果绑定：`tool_finished` 必须指回**自己**那次 `tool_started`，
     * 而不是同回合的上一条（并发交错时上一条是邻居，不是原因）。
     */
    @Test
    fun boundCause_pointsToOwnStartedNotPreviousRecord() {
        val s = scope("cause")
        val turn = EventTrace.beginTurn(s)

        // 交错场景：两个工具先后 started，再分别 finished（真机实测交错 277 次）
        val seqA = EventTrace.record(turn, s, "EVENT", "tool_started a")
        EventTrace.bindCause(turn, s, "tool:a", seqA)
        val seqB = EventTrace.record(turn, s, "EVENT", "tool_started b")
        EventTrace.bindCause(turn, s, "tool:b", seqB)

        // 此时 seq 已推进；默认因果会指向「上一条」，绑定后应指向各自的 started
        assertEquals(seqA, EventTrace.resolveCauseForTest(turn, s, "tool:a"))
        assertEquals(seqB, EventTrace.resolveCauseForTest(turn, s, "tool:b"))

        EventTrace.endTurn(turn, s, "test")
    }

    /** 绑定不存在（如进程重启后 lost started）时退回 null，由调用方走默认因果，不报错。 */
    @Test
    fun unboundCause_returnsNull() {
        val s = scope("unbound")
        val turn = EventTrace.beginTurn(s)

        assertNull("未绑定的键不应凭空造出因果", EventTrace.resolveCauseForTest(turn, s, "tool:missing"))

        EventTrace.endTurn(turn, s, "test")
    }

    /** 回合收尾后绑定必须清掉，否则下个回合会指到上个回合的 seq。 */
    @Test
    fun boundCause_clearedAfterEndTurn() {
        val s = scope("clear")
        val t1 = EventTrace.beginTurn(s)
        val seq = EventTrace.record(t1, s, "EVENT", "tool_started x")
        EventTrace.bindCause(t1, s, "tool:x", seq)
        EventTrace.endTurn(t1, s, "test")

        val t2 = EventTrace.beginTurn(s)
        assertNull("上个回合的绑定不得泄漏到新回合", EventTrace.resolveCauseForTest(t2, s, "tool:x"))
        assertNotEquals("新回合必须是新号", t1, t2)

        EventTrace.endTurn(t2, s, "test")
    }
}
