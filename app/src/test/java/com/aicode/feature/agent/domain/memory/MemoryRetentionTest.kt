package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆陈旧度评估测试。
 *
 * 重点：**绝不能把「信息缺失」当成「足够旧」**——mtime 读不到（0）时必须不判陈旧，
 * 否则一次读取失败就会诱导把用户记忆全删掉。pinned 记忆同样必须永久豁免。
 */
class MemoryRetentionTest {

    private val day = 24L * 60 * 60 * 1000

    private fun mem(name: String, pinned: Boolean = false) = Memory(
        name = name,
        description = "desc",
        scope = MemoryScope.GLOBAL,
        file = null,
        content = "body",
        pinned = pinned
    )

    /** 用注入的 mtime 映射替代真实文件系统，保持测试零 IO。 */
    private fun assess(
        memories: List<Memory>,
        nowMs: Long,
        staleDays: Long,
        mtimes: Map<String, Long>
    ) = MemoryRetention.assess(memories, nowMs, staleDays) { mtimes[it.name] ?: 0L }

    @Test
    fun stale_whenOlderThanThreshold() {
        val now = 1_000L * day
        val report = assess(listOf(mem("old")), now, staleDays = 180, mtimes = mapOf("old" to now - 200 * day))
        assertTrue("超过阈值应判陈旧", report.isStale("old"))
        assertEquals(1, report.staleCount)
    }

    @Test
    fun notStale_whenNewerThanThreshold() {
        val now = 1_000L * day
        val report = assess(listOf(mem("fresh")), now, staleDays = 180, mtimes = mapOf("fresh" to now - 10 * day))
        assertFalse(report.isStale("fresh"))
        assertEquals(0, report.staleCount)
    }

    @Test
    fun boundary_exactlyThreshold_isNotStale() {
        // 语义是「超过」N 天，恰好 N 天（含）不算陈旧——边界取严，宁可少删。
        val now = 1_000L * day
        val report = assess(listOf(mem("edge")), now, staleDays = 180, mtimes = mapOf("edge" to now - 180 * day))
        assertFalse("恰好阈值当天不应判陈旧", report.isStale("edge"))
    }

    @Test
    fun pinnedMemory_isNeverStale() {
        val now = 1_000L * day
        val report = assess(
            listOf(mem("pinned-one", pinned = true)),
            now,
            staleDays = 1,
            mtimes = mapOf("pinned-one" to now - 999 * day)
        )
        assertFalse("pinned 记忆必须永久豁免", report.isStale("pinned-one"))
        assertEquals("应计入豁免数", 1, report.pinnedExemptCount)
        assertEquals(0, report.staleCount)
    }

    @Test
    fun unknownMtime_isNotTreatedAsStale() {
        // 读取失败（mtime=0）是信息缺失，不能当作「足够旧」——否则一次 IO 失败就会误删。
        val now = 1_000L * day
        val report = assess(listOf(mem("broken")), now, staleDays = 180, mtimes = emptyMap())
        assertFalse("mtime 未知必须不判陈旧", report.isStale("broken"))
        assertEquals(0, report.staleCount)
    }

    @Test
    fun futureMtime_isNotStale() {
        // 时钟回拨 / 文件系统时间异常导致 mtime 在未来时，按 0 天计，不误伤。
        val now = 1_000L * day
        val report = assess(listOf(mem("future")), now, staleDays = 180, mtimes = mapOf("future" to now + 50 * day))
        assertFalse(report.isStale("future"))
    }

    @Test
    fun disabled_whenStaleDaysNotPositive() {
        val now = 1_000L * day
        val report = assess(listOf(mem("a")), now, staleDays = 0, mtimes = mapOf("a" to now - 9999 * day))
        assertFalse("staleDays<=0 表示关闭评估", report.isStale("a"))
        assertEquals(0, report.staleCount)
    }

    @Test
    fun report_aggregatesMixedSet() {
        val now = 1_000L * day
        val memories = listOf(mem("old"), mem("new"), mem("pinned-old", pinned = true), mem("broken"))
        val report = assess(
            memories,
            now,
            staleDays = 180,
            mtimes = mapOf(
                "old" to now - 300 * day,
                "new" to now - 1 * day,
                "pinned-old" to now - 300 * day
                // broken 无 mtime
            )
        )
        assertEquals(listOf("old"), report.ages.filter { it.stale }.map { it.name })
        assertEquals(1, report.staleCount)
        assertEquals(1, report.pinnedExemptCount)
        assertEquals(4, report.ages.size)
    }
}
