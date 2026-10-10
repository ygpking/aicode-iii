package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要窗口截断的回归用例。
 *
 * 背景：主模型窗口常远大于压缩模型窗口（实测 1M vs 128k），预算内装不下的最旧历史
 * 会被丢掉且不进摘要。用例锁死「丢弃量必须被算出并可见」——旧实现只打一行 INFO，
 * 用户与接手方无从得知，会把「摘要里没有」当成「从未发生」。
 */
class CompactionSummaryWindowTest {

    /** 每条消息按固定 token 计，便于精确断言。 */
    private fun msgs(vararg tokens: Int) = tokens.toList()

    private val estimate: (Int) -> Int = { it }

    @Test
    fun `空输入返回空且无丢弃`() {
        val r = CompactionSummaryWindow.selectTail(emptyList<Int>(), 100, estimate)
        assertEquals(emptyList<Int>(), r.kept)
        assertFalse(r.hasDrop)
        assertEquals("", r.notice())
    }

    @Test
    fun `预算足够时全部保留且无丢弃`() {
        val r = CompactionSummaryWindow.selectTail(msgs(10, 20, 30), 100, estimate)
        assertEquals(listOf(10, 20, 30), r.kept)
        assertFalse(r.hasDrop)
        assertEquals("", r.notice())
    }

    @Test
    fun `超预算时保留最近的并从尾部取`() {
        // 预算 50：从新（右）往旧取，30+20=50 收下，10 丢弃
        val r = CompactionSummaryWindow.selectTail(msgs(10, 20, 30), 50, estimate)
        assertEquals(listOf(20, 30), r.kept)
        assertTrue(r.hasDrop)
        assertEquals(1, r.droppedCount)
        assertEquals(10, r.droppedTokens)
    }

    @Test
    fun `丢弃量被计入产物说明`() {
        val r = CompactionSummaryWindow.selectTail(msgs(10, 20, 30), 50, estimate)
        val notice = r.notice()
        assertTrue("应写明丢了 1 条，实测=$notice", notice.contains("1 条"))
        assertTrue("应写明丢了 10 token，实测=$notice", notice.contains("10 token"))
        assertTrue("应提示不要臆测，实测=$notice", notice.contains("不要臆测"))
    }

    @Test
    fun `单条超预算时仍保留该条`() {
        // 最新一条就超预算：至少留一条，否则产出空材料
        val r = CompactionSummaryWindow.selectTail(msgs(10, 999), 50, estimate)
        assertEquals(listOf(999), r.kept)
        assertTrue(r.hasDrop)
    }

    @Test
    fun `顺序保持为先旧后新`() {
        val r = CompactionSummaryWindow.selectTail(msgs(1, 2, 3, 4), 100, estimate)
        assertEquals(listOf(1, 2, 3, 4), r.kept)
    }

    @Test
    fun `丢弃 token 数与实际被丢之和一致`() {
        // 从新（右）往旧取：50 收下（总 50），再收一个 5（总 55）；下一个 5 会超预算而停。
        // 故保留 [5, 50]，丢弃 2 条（两个 5，共 10 token）。
        val r = CompactionSummaryWindow.selectTail(msgs(5, 5, 5, 50), 55, estimate)
        assertEquals(listOf(5, 50), r.kept)
        assertEquals(2, r.droppedCount)
        assertEquals(10, r.droppedTokens)
    }

    @Test
    fun `无丢弃时说明为空串而非 null`() {
        val r = CompactionSummaryWindow.selectTail(msgs(1), 100, estimate)
        assertEquals("", r.notice())
    }
}
