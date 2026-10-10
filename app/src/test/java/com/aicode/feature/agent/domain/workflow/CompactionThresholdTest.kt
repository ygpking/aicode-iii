package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩阈值策略的回归用例。
 *
 * 抽出这段策略的直接动机就是「零测试覆盖导致反向调整长期未被发现」，
 * 故用例尤重**用户设低阈值**这一档（真机踩坑场景）。
 */
class CompactionThresholdTest {

    private val WINDOW = 1_000_000

    // ── 增长过快判定 ──────────────────────────────────────────────

    @Test
    fun `本进程内尚未压缩过时不算增长过快`() {
        // lastCompactedSize = 0 表示没有基准，不该据此判定增长（否则首轮必然误判）
        assertFalse(CompactionThreshold.isFastGrowth(0, 500_000, WINDOW))
    }

    @Test
    fun `增长未超窗口一成五不算过快`() {
        assertFalse(CompactionThreshold.isFastGrowth(100_000, 200_000, WINDOW))
    }

    @Test
    fun `增长超窗口一成五算过快`() {
        assertTrue(CompactionThreshold.isFastGrowth(100_000, 300_000, WINDOW))
    }

    @Test
    fun `窗口为零时不算过快`() {
        assertFalse(CompactionThreshold.isFastGrowth(1000, 5000, 0))
    }

    // ── 阈值百分比：只降不升（本次修复的核心）────────────────────

    @Test
    fun `非增长过快时阈值等于用户设置`() {
        assertEquals(15, CompactionThreshold.effectivePercent(15, fastGrowth = false))
        assertEquals(90, CompactionThreshold.effectivePercent(90, fastGrowth = false))
    }

    @Test
    fun `用户设低于下限时下调不改变阈值`() {
        // 真机踩坑：用户设 15%（1M 窗口期望 150k 触发）。
        // 旧实现 (15-15).coerceAtLeast(60) = 60 → 阈值被抬到 600k，会话此后再也压不动。
        // 修复后：已低于下限的设置不再下探（继续按 150k 触发），但**绝不升高**。
        assertEquals(15, CompactionThreshold.effectivePercent(15, fastGrowth = true))
    }

    @Test
    fun `用户设等于下限时下调到下限`() {
        // 设 60：60-15=45 低于 floor(60)，取 60（与设置相同，至少不升高）
        assertEquals(60, CompactionThreshold.effectivePercent(60, fastGrowth = true))
    }

    @Test
    fun `用户设高于下限时按步长下调到不低于下限`() {
        assertEquals(60, CompactionThreshold.effectivePercent(75, fastGrowth = true))
        assertEquals(75, CompactionThreshold.effectivePercent(90, fastGrowth = true))
    }

    @Test
    fun `旧实现的反向抬高不再出现`() {
        // 回归锁：旧实现 (base-15).coerceAtLeast(60) 在 base=15 时给出 60（=600k 阈值），
        // 在 base=59 时给出 60（> 59，同样被抬高）。修复后下调结果必须 <= base。
        assertEquals(15, CompactionThreshold.effectivePercent(15, fastGrowth = true))
        assertEquals(59, CompactionThreshold.effectivePercent(59, fastGrowth = true))
        // 守住边界：刚等于下限时不再下探
        assertEquals(60, CompactionThreshold.effectivePercent(60, fastGrowth = true))
    }

    @Test
    fun `任意用户设置下调后都不升高`() {
        // 不变式：对 1..100 全量合法输入，下调结果必须 <= 原值
        for (base in 1..100) {
            val got = CompactionThreshold.effectivePercent(base, fastGrowth = true)
            assertTrue("base=$base 下调后变成了 $got（不应升高）", got <= base)
            assertTrue("base=$base 下调后为 $got（应 >= 1）", got >= 1)
        }
    }

    // ── 触发 token 数 ─────────────────────────────────────────────

    @Test
    fun `用户设一成五时首轮阈值为一成五窗口`() {
        assertEquals(150_000, CompactionThreshold.triggerTokens(WINDOW, 15, fastGrowth = false))
    }

    @Test
    fun `用户设一成五且增长过快时阈值不升高`() {
        // 修复前该值是 600000（被抬到 60%），导致会话此后再也无法触发压缩
        val thr = CompactionThreshold.triggerTokens(WINDOW, 15, fastGrowth = true)
        assertEquals(150_000, thr)
    }

    @Test
    fun `默认九成设置在增长过快时提前到七成五`() {
        assertEquals(750_000, CompactionThreshold.triggerTokens(WINDOW, 90, fastGrowth = true))
    }

    @Test
    fun `小窗口下阈值不虚高`() {
        // 128k 窗口 + 设 15% → 19200
        assertEquals(19_200, CompactionThreshold.triggerTokens(128_000, 15, fastGrowth = false))
    }
}
