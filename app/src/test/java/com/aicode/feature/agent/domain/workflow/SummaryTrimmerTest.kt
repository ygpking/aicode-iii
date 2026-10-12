package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要长度封顶的回归用例（B1 修复）。
 *
 * 背景（实测 2026-10-11 会话 22ddb225）：单日压缩 42+ 次，摘要从 13025 单调膨胀到 104795 字符，
 * 直至摘要自身 ≈ 触发阈值、压缩后立即再触发（重触发间隔 12.2min→2.9min，一阶自相关 r1=0.889）。
 * 根因是摘要无任何长度上限。
 *
 * 关键约束：接手者最需要摘要**头**（任务状态）与**尾**（下一步动作）。朴素尾部截断会先丢这两节，
 * 属严重回归。故用例锁死「保头 + 保尾、折叠中段」。
 */
class SummaryTrimmerTest {

    private fun body(mid:String): String = "## 任务状态\n进行中。\n$mid## 下一步动作\n先跑 git status。"

    @Test
    fun `未超上限时原样返回`() {
        val text = "短摘要"
        assertEquals(text, SummaryTrimmer.trim(text, 1000))
    }

    @Test
    fun `超上限时裁剪且长度不超上限`() {
        val text = body("中间明细。".repeat(5000))
        val out = SummaryTrimmer.trim(text, 400)
        assertTrue("裁剪后应 ≤ 上限: ${out.length}", out.length <= 400)
    }

    @Test
    fun `保留头部（任务状态）`() {
        val text = body("中间明细。".repeat(5000))
        val out = SummaryTrimmer.trim(text, 400)
        assertTrue("头部应保留", out.startsWith("## 任务状态"))
    }

    @Test
    fun `保留尾部（下一步动作）——防止朴素尾部截断回归`() {
        val text = body("中间明细。".repeat(5000))
        val out = SummaryTrimmer.trim(text, 400)
        assertTrue("尾部应保留: ...${out.takeLast(20)}", out.trimEnd().endsWith("先跑 git status。"))
    }

    @Test
    fun `中段插入折叠标记`() {
        val text = body("中间明细。".repeat(5000))
        val out = SummaryTrimmer.trim(text, 400)
        assertTrue("应含折叠标记", out.contains(SummaryTrimmer.ELISION))
    }

    @Test
    fun `上限过小时不制造更差结果（原样返回）`() {
        val text = "abc"
        // maxChars 小于 2×最短可保留量时，宁可不裁也不产出「只剩标记」的退化文本。
        assertEquals(text, SummaryTrimmer.trim(text, 3))
    }
}
