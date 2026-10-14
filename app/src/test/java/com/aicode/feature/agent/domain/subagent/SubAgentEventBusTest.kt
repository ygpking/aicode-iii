package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃子代理集合的维护语义：并发槽位靠它计数，只要有一条路径忘了交回槽位，
 * 用户就会在没有任何子代理运行时被告知「已达上限」，直到进程重启。
 */
class SubAgentEventBusTest {

    private fun spawn(bus: SubAgentEventBus, id: String) = bus.emit(
        SubAgentEvent(subSessionId = id, parentSessionId = "parent", type = SubAgentEventType.SPAWNED)
    )

    @Test
    fun spawn_marksActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertFalse(bus.isFull)
    }

    @Test
    fun terminalEvents_freeSlot() {
        listOf(
            SubAgentEventType.COMPLETED,
            SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED
        ).forEach { type ->
            val bus = SubAgentEventBus()
            spawn(bus, "sub-1")
            bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = type))

            assertEquals("$type 应交回槽位", emptySet<String>(), bus.activeSubSessionIds.value)
        }
    }

    @Test
    fun isFull_atMaxRunning() {
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { spawn(bus, "sub-$it") }

        assertTrue(bus.isFull)

        bus.emit(SubAgentEvent(subSessionId = "sub-0", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertFalse("腾出一个后应能再派发", bus.isFull)
    }

    /** 用户手动停止走 release：必须真的交回槽位，否则上限被永久占用。 */
    @Test
    fun release_freesSlotAndReportsWhetherItWasActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 幂等且如实回报：TaskTool 的 stop 已经 emit 过 STOPPED 时，ViewModel 随后的 release
     * 必须返回 false，否则会给父代理重复投递一条「已被终止」通知。
     */
    @Test
    fun release_isIdempotentForUnknownId() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        bus.release("sub-1")

        assertFalse(bus.release("sub-1"))
        assertFalse(bus.release("never-existed"))
    }

    @Test
    fun release_doesNotTouchOtherSubAgents() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        spawn(bus, "sub-2")

        bus.release("sub-1")

        assertEquals(setOf("sub-2"), bus.activeSubSessionIds.value)
    }

    /**
     * 回归：已完成子代理被 `send` 唤醒、起新一轮时，经由 markActive 补登记，否则该 id 自始至终
     * 不在活跃集合——并发上限漏算、read/list 误报 completed、写租约被 pruneInactive 误删。
     */
    @Test
    fun markActive_marksIdleSubAgentActive() {
        val bus = SubAgentEventBus()
        // 模拟一轮已完成：曾经 spawn 过并已归还
        spawn(bus, "sub-1")
        bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)

        // 被 send 重新唤醒
        bus.markActive("sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertFalse(bus.isFull)
    }

    /** markActive 幂等：已在集合中再调用不重复计数。 */
    @Test
    fun markActive_isIdempotent() {
        val bus = SubAgentEventBus()
        bus.markActive("sub-1")
        bus.markActive("sub-1")
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
    }

    /** markActive 与 release 配对：唤醒后归还，名额回到零。 */
    @Test
    fun markActive_thenRelease_freesSlot() {
        val bus = SubAgentEventBus()
        bus.markActive("sub-1")
        assertTrue(bus.activeSubSessionIds.value.contains("sub-1"))

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 收发消息不改变运行状态：兄弟消息不能顺手把收件人算成活跃（会虚占名额、提前触发上限），
     * 也不能把它移出活跃集合（会让写租约被误回收）。
     */
    @Test
    fun messageEvents_doNotTouchActiveSet() {
        listOf(
            SubAgentEventType.MESSAGE_FROM_PARENT,
            SubAgentEventType.MESSAGE_FROM_SUB,
            SubAgentEventType.MESSAGE_FROM_SIBLING
        ).forEach { type ->
            val bus = SubAgentEventBus()
            spawn(bus, "sub-1")
            bus.emit(
                SubAgentEvent(
                    subSessionId = "sub-2",
                    parentSessionId = "parent",
                    type = type,
                    senderSessionId = "sub-1"
                )
            )

            assertEquals("$type 不应改变活跃集合", setOf("sub-1"), bus.activeSubSessionIds.value)
        }
    }

    // —— 会话流归属（多实例去重）——
    // 双 AIAgentViewModel 实例各收到同一份事件时，必须只有一个实例能启动/触发会话流。

    private fun flowOwnerPair() = Pair(Any(), Any())

    @Test
    fun tryAcquireFlow_firstOwnerWins_secondRejected() {
        val bus = SubAgentEventBus()
        val (vmA, vmB) = flowOwnerPair()

        assertTrue(bus.tryAcquireFlow("sub-1", vmA))
        assertFalse("已被 vmA 持有，vmB 抢占应失败", bus.tryAcquireFlow("sub-1", vmB))
        assertFalse("同 owner 重复抢占也应失败", bus.tryAcquireFlow("sub-1", vmA))
    }

    @Test
    fun flowOwnership_ownerLookup() {
        val bus = SubAgentEventBus()
        val (vmA, vmB) = flowOwnerPair()
        bus.tryAcquireFlow("sub-1", vmA)

        assertTrue(bus.isFlowOwnedBy("sub-1", vmA))
        assertTrue(bus.isFlowOwnedElsewhere("sub-1", vmB))
        assertFalse(bus.isFlowOwnedElsewhere("sub-1", vmA))
        assertFalse("无主会话：本人未持有、他人也未持有", bus.isFlowOwnedBy("sub-2", vmA))
        assertFalse(bus.isFlowOwnedElsewhere("sub-2", vmA))
    }

    @Test
    fun releaseFlow_onlyFreesOwnOwnership() {
        val bus = SubAgentEventBus()
        val (vmA, vmB) = flowOwnerPair()
        bus.tryAcquireFlow("sub-1", vmA)

        bus.releaseFlow("sub-1", vmB)
        assertTrue("异 owner 释放不应生效", bus.isFlowOwnedBy("sub-1", vmA))

        bus.releaseFlow("sub-1", vmA)
        assertFalse(bus.isFlowOwnedBy("sub-1", vmA))
        assertTrue("释放后他人可抢占", bus.tryAcquireFlow("sub-1", vmB))
    }

    /** 双实例核心场景：SPAWNED 被两份订阅各收到一次，只有一个实例能启动子会话流。 */
    @Test
    fun concurrentAcquire_singleWinner() {
        val bus = SubAgentEventBus()
        val (vmA, vmB) = flowOwnerPair()

        val aWon = bus.tryAcquireFlow("sub-1", vmA)
        val bWon = bus.tryAcquireFlow("sub-1", vmB)

        assertTrue("先到者应获胜", aWon)
        assertFalse("后到者应被拒", bWon)
        assertTrue("后到者视角：流在别处", bus.isFlowOwnedElsewhere("sub-1", vmB))
    }

    /** 多会话、多 owner 场景：releaseAllFlows 只释放目标 owner 的归属，不影响他人。 */
    @Test
    fun releaseAllFlows_onlyFreesOwnerOwnership() {
        val bus = SubAgentEventBus()
        val (vmA, vmB) = flowOwnerPair()
        bus.tryAcquireFlow("sub-1", vmA)
        bus.tryAcquireFlow("sub-2", vmA)
        bus.tryAcquireFlow("sub-3", vmB)

        bus.releaseAllFlows(vmA)

        assertFalse("vmA 的归属应全部释放", bus.isFlowOwnedBy("sub-1", vmA))
        assertFalse("vmA 的第二个归属也应释放", bus.isFlowOwnedBy("sub-2", vmA))
        assertTrue("vmB 的归属不受影响", bus.isFlowOwnedBy("sub-3", vmB))
        assertTrue("释放后他人可抢占", bus.tryAcquireFlow("sub-1", vmB))
    }
}
