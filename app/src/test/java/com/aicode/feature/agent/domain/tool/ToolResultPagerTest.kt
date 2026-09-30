package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘结果分页器测试。
 * 重点：行号语义与 `readFile` 一致；越界不报错而是收敛；字符上限兜底超大行。
 */
class ToolResultPagerTest {

    private fun text(lines: Int) = (1..lines).joinToString("\n") { "line$it" }

    @Test
    fun firstPage_defaults() {
        val page = ToolResultPager.page(text(500))
        assertEquals(1, page.startLine)
        assertEquals(ToolResultPager.DEFAULT_MAX_LINES, page.endLine)
        assertEquals(500, page.totalLines)
        assertTrue("还有后续", page.hasMore)
        assertFalse(page.truncatedByChars)
        assertTrue(page.text.startsWith("line1"))
        assertTrue(page.text.endsWith("line${ToolResultPager.DEFAULT_MAX_LINES}"))
    }

    @Test
    fun middlePage_usesRequestedStart() {
        val page = ToolResultPager.page(text(500), startLine = 201, maxLines = 50)
        assertEquals(201, page.startLine)
        assertEquals(250, page.endLine)
        assertTrue(page.hasMore)
        assertTrue(page.text.startsWith("line201"))
    }

    @Test
    fun lastPage_hasNoMore() {
        val page = ToolResultPager.page(text(100), startLine = 51, maxLines = 100)
        assertEquals(51, page.startLine)
        assertEquals(100, page.endLine)
        assertFalse(page.hasMore)
    }

    @Test
    fun startBeyondEnd_convergesToLastPage_notError() {
        // 越界不应报错：收敛到最后一页，模型可凭 total_lines 自行纠偏。
        val page = ToolResultPager.page(text(3), startLine = 999)
        assertEquals(3, page.totalLines)
        assertEquals(3, page.endLine)
        assertFalse(page.hasMore)
        assertTrue("应返回最后一行", page.text.contains("line3"))
    }

    @Test
    fun startBelowOne_isClampedToOne() {
        val page = ToolResultPager.page(text(10), startLine = 0)
        assertEquals(1, page.startLine)
    }

    @Test
    fun maxLines_overHardCap_isClamped() {
        val page = ToolResultPager.page(text(10_000), maxLines = 999_999)
        assertEquals(ToolResultPager.HARD_MAX_LINES, page.endLine - page.startLine + 1)
    }

    @Test
    fun maxLines_notPositive_usesDefault() {
        val page = ToolResultPager.page(text(1_000), maxLines = 0)
        assertEquals(ToolResultPager.DEFAULT_MAX_LINES, page.endLine - page.startLine + 1)
    }

    @Test
    fun singleHugeLine_truncatedByChars() {
        // 一行就是几万字符：行数没超，但字符必须兜底，否则照样撑爆上下文。
        val huge = "x".repeat(ToolResultPager.MAX_PAGE_CHARS + 5_000)
        val page = ToolResultPager.page(huge, maxLines = 10)
        assertTrue("应标记按字符截断", page.truncatedByChars)
        assertEquals(ToolResultPager.MAX_PAGE_CHARS, page.text.length)
        assertEquals(1, page.totalLines)
    }

    @Test
    fun emptyText_singleEmptyLine() {
        val page = ToolResultPager.page("")
        assertEquals(1, page.totalLines)
        assertEquals("", page.text)
        assertFalse(page.hasMore)
    }
}
