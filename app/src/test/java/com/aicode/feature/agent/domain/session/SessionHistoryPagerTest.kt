package com.aicode.feature.agent.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻阅历史的分页与字符预算守卫。这些边界（空结果、单条超限、恰好用尽预算）一旦写错，
 * 表现是「工具把上下文撑爆」或「页永远为空、翻不动」，都难以从现象反推，故直接单测。
 */
class SessionHistoryPagerTest {

    private fun item(content: String, ts: Long = 0L, compacted: Boolean = false) =
        SessionHistoryItem(timestamp = ts, role = "USER", content = content, compacted = compacted)

    @Test
    fun `空输入返回空页且不标记触顶`() {
        val page = SessionHistoryPager.pack(emptyList())
        assertTrue(page.items.isEmpty())
        assertFalse(page.truncatedByChars)
        assertFalse(page.truncatedMessages)
    }

    @Test
    fun `预算充足时全量装包`() {
        val rows = listOf(item("a"), item("b"), item("c"))
        val page = SessionHistoryPager.pack(rows, maxCharsPerMessage = 100, maxTotalChars = 1000)
        assertEquals(3, page.items.size)
        assertFalse(page.truncatedByChars)
    }

    @Test
    fun `超出总预算时截断并标记`() {
        // 每条 10 字符；预算 25 → 只能装 2 条（第 3 条会超）
        val rows = List(5) { item("0123456789", ts = it.toLong()) }
        val page = SessionHistoryPager.pack(rows, maxCharsPerMessage = 100, maxTotalChars = 25)
        assertEquals(2, page.items.size)
        assertTrue(page.truncatedByChars)
    }

    @Test
    fun `单条超长时首条仍须入包`() {
        // 首条单独就超总预算；若被挡掉，页将永远为空、调用方拿到游标却翻不动 → 死循环
        val rows = listOf(
            item("x".repeat(500), ts = 100),
            item("y", ts = 200)
        )
        val page = SessionHistoryPager.pack(rows, maxCharsPerMessage = 1000, maxTotalChars = 100)
        assertEquals(1, page.items.size)
        assertEquals(100L, page.items.first().timestamp)
        assertTrue(page.truncatedByChars)
    }

    @Test
    fun `恰好用尽预算不入下一轮截断`() {
        val rows = listOf(item("aa"), item("bb"))
        val page = SessionHistoryPager.pack(rows, maxCharsPerMessage = 100, maxTotalChars = 4)
        assertEquals(2, page.items.size)
        assertFalse(page.truncatedByChars)
    }

    @Test
    fun `单条超限时标记 truncatedMessages`() {
        val rows = listOf(item("x".repeat(50)))
        val page = SessionHistoryPager.pack(rows, maxCharsPerMessage = 10, maxTotalChars = 1000)
        assertTrue(page.truncatedMessages)
        assertEquals(1, page.items.size)
    }

    @Test
    fun `truncate 未超限时原样返回`() {
        assertEquals("abc", SessionHistoryPager.truncate("abc", max = 10))
    }

    @Test
    fun `truncate 超限时截断并附说明`() {
        val out = SessionHistoryPager.truncate("abcdefghij", max = 4)
        assertTrue(out.startsWith("abcd"))
        assertTrue(out.contains("已截断"))
    }

    @Test
    fun `clampLimit 缺省与越界都被夹紧`() {
        assertEquals(SessionHistoryPager.DEFAULT_LIMIT, SessionHistoryPager.clampLimit(null))
        assertEquals(1, SessionHistoryPager.clampLimit(0))
        assertEquals(1, SessionHistoryPager.clampLimit(-5))
        assertEquals(SessionHistoryPager.MAX_LIMIT, SessionHistoryPager.clampLimit(9999))
    }

    @Test
    fun `escapeLike 转义通配符避免全表匹配`() {
        // 不转义时 "%" 会匹配一切，等于绕过关键词筛选
        assertEquals("!%", SessionHistoryPager.escapeLike("%"))
        assertEquals("!_", SessionHistoryPager.escapeLike("_"))
        assertEquals("!!", SessionHistoryPager.escapeLike("!"))
        assertEquals("a!%b", SessionHistoryPager.escapeLike("a%b"))
    }

    @Test
    fun `纯文本关键词不被转义`() {
        assertEquals("普通关键词", SessionHistoryPager.escapeLike("普通关键词"))
    }
}
