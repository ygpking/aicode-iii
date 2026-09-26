package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentWriteLeaseTest {

    /** 用真实事件总线驱动「活跃集合」，租约的失效完全依赖它。 */
    private fun busWithActive(vararg ids: String) = SubAgentEventBus().also { bus ->
        ids.forEach { id ->
            bus.emit(SubAgentEvent(subSessionId = id, parentSessionId = "p", type = SubAgentEventType.SPAWNED))
        }
    }

    @Test
    fun noDeclarationMeansNoRestriction() {
        val lease = SubAgentWriteLease(busWithActive("s1"))
        // 未声明租约的会话：pathsFor 返回 null，写工具据此不做任何限制
        assertNull(lease.pathsFor("s1"))
        assertTrue(lease.isWithinLease("s1", "anything.kt"))
        assertTrue(lease.findConflict(listOf("a.kt")).isEmpty())
    }

    @Test
    fun conflictingPathIsDetected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        assertTrue(lease.findConflict(listOf("src/a.kt")).contains("s1"))
    }

    @Test
    fun prefixOverlapIsDetected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src"))
        // 目录租约覆盖其下文件
        assertTrue(lease.findConflict(listOf("src/a.kt")).contains("s1"))
    }

    @Test
    fun disjointPathsDoNotConflict() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        assertTrue(lease.findConflict(listOf("src/b.kt")).isEmpty())
    }

    @Test
    fun dotDotIsResolvedBeforeComparison() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/lib"))
        // `src/lib/../lib` 归一后与 `src/lib` 相同，应判冲突
        assertTrue(lease.findConflict(listOf("src/lib/../lib")).contains("s1"))
    }

    @Test
    fun wholeWorkspaceConflictsWithEverything() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("*"))
        assertTrue(lease.findConflict(listOf("anything.kt")).contains("s1"))
    }

    @Test
    fun leaseExpiresWhenSubAgentIsNoLongerActive() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        // 子代理结束（离开活跃集合）后租约应自动失效，不阻塞后续任务
        bus.emit(SubAgentEvent(subSessionId = "s1", parentSessionId = "p", type = SubAgentEventType.COMPLETED))
        assertTrue(lease.findConflict(listOf("src/a.kt")).isEmpty())
    }

    @Test
    fun explicitReleaseDropsLease() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        lease.release("s1")
        assertNull(lease.pathsFor("s1"))
    }

    @Test
    fun writeOutsideLeaseIsRejected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src"))
        assertTrue(lease.isWithinLease("s1", "src/a.kt"))
        assertFalse(lease.isWithinLease("s1", "other/a.kt"))
    }

    @Test
    fun blankLeaseIsIgnored() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("  ", ""))
        assertNull(lease.pathsFor("s1"))
        assertEquals(0, lease.findConflict(listOf("a.kt")).size)
    }
}
