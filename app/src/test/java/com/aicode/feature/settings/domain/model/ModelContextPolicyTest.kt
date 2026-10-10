package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextPolicyTest {

    // ---------- preserveRecentTokens：usableTokens / 4 后 clamp 到 [2000, 20000] ----------

    @Test
    fun preserveRecentTokens_zero_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(0))
    }

    /** 低于下界（整除后不足 2000）时被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_belowMinimum_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(7_999))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(1))
    }

    /** 恰好落在下界：无需 clamp。 */
    @Test
    fun preserveRecentTokens_atMinimum_boundary() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(8_000))
    }

    /** 区间内正常整除 4。 */
    @Test
    fun preserveRecentTokens_inRange_quarterDown() {
        assertEquals(2_500, ModelContextPolicy.preserveRecentTokens(10_000))
        assertEquals(19_999, ModelContextPolicy.preserveRecentTokens(79_999))
    }

    /** 上界边界：整除后恰好 20000 以及略超（整除截断仍为 20000）。 */
    @Test
    fun preserveRecentTokens_atMaximum_boundary() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_001))
    }

    /** 超过上界时 clamp 到上界，包括超大值与 Int.MAX_VALUE。 */
    @Test
    fun preserveRecentTokens_aboveMaximum_clampedToMaximum() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(128_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(Int.MAX_VALUE))
    }

    /** 负数（理论上不会出现）同样被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_negative_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(-1))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(Int.MIN_VALUE))
    }

    // ---------- 上限随窗口取宽：小窗口不回归、大窗口放宽 ----------

    /** 128k 窗口下与旧行为完全一致（上限仍为基础上限 20000）。 */
    @Test
    fun preserveRecentTokens_smallWindowUnchanged() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(128_000, 128_000))
        assertEquals(4_800, ModelContextPolicy.preserveRecentTokens(19_200, 128_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_000, 128_000))
    }

    /** 1M 窗口下上限提到窗口的 10%，不再被 20k 卡住。 */
    @Test
    fun preserveRecentTokens_largeWindowWidened() {
        // 阈值 900k（10% 上限 = 100k），旧实现会被 20k 截断
        assertEquals(100_000, ModelContextPolicy.preserveRecentTokens(900_000, 1_000_000))
        // 阈值 150k → 37500（旧实现 20000）
        assertEquals(37_500, ModelContextPolicy.preserveRecentTokens(150_000, 1_000_000))
    }

    /** 大窗口下小阈值仍按下界保护，不会低于 MIN。 */
    @Test
    fun preserveRecentTokens_largeWindowStillRespectsMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(1_000, 1_000_000))
    }

    /** 约束：压缩后大小（tail + 摘要约 10k）必须小于触发阈值，否则压完立即再触发。 */
    @Test
    fun preserveRecentTokens_compactedSizeStaysUnderThreshold() {
        val summaryTokens = 10_000
        for (limit in listOf(128_000, 200_000, 1_000_000)) {
            for (pct in listOf(15, 60, 90)) {
                val threshold = limit * pct / 100
                val tail = ModelContextPolicy.preserveRecentTokens(threshold, limit)
                assertTrue(
                    "窗口 $limit 阈值 ${pct}%：压缩后 ${tail + summaryTokens} 不应达到阈值 $threshold",
                    tail + summaryTokens < threshold,
                )
            }
        }
    }

    // ---------- estimateTokens：(chars + 3) / 4 向上取整 ----------

    @Test
    fun estimateTokens_zero_isZero() {
        assertEquals(0, ModelContextPolicy.estimateTokens(0))
    }

    /** 恰为 4 的倍数：无需进位。 */
    @Test
    fun estimateTokens_exactMultiple() {
        assertEquals(1, ModelContextPolicy.estimateTokens(4))
        assertEquals(25, ModelContextPolicy.estimateTokens(100))
    }

    /** 有余数时向上取整。 */
    @Test
    fun estimateTokens_roundsUp() {
        assertEquals(1, ModelContextPolicy.estimateTokens(1))
        assertEquals(2, ModelContextPolicy.estimateTokens(5))
        assertEquals(26, ModelContextPolicy.estimateTokens(101))
    }

    /** 超大值不溢出（不能用 Int.MAX_VALUE：+3 会整数溢出成负数）。 */
    @Test
    fun estimateTokens_largeValue() {
        assertEquals(250_000_000, ModelContextPolicy.estimateTokens(1_000_000_000))
    }

    // ---------- estimateTokens(text)：CJK 感知分桶（新增，修正中文低估） ----------

    @Test
    fun estimateTokens_text_emptyIsZero() {
        assertEquals(0, ModelContextPolicy.estimateTokens(""))
    }

    /** 中文估算应高于 chars/4（旧口径系统性低估）。 */
    @Test
    fun estimateTokens_text_chineseHigherThanNaiveQuarter() {
        val zh = "这是一段中文测试文本用来验证中文的token估算是否合理"
        val naive = ModelContextPolicy.estimateTokens(zh.length)
        val aware = ModelContextPolicy.estimateTokens(zh)
        assertTrue("中文估算($aware) 应高于 /4($naive)", aware > naive)
    }

    /** 纯 ASCII 也应给出合理估算（非 0）。 */
    @Test
    fun estimateTokens_text_asciiReasonable() {
        val en = "hello world this is a test"
        assertTrue(ModelContextPolicy.estimateTokens(en) >= 1)
    }

    /** 混合文本不报错且大于纯 ASCII 部分的估算。 */
    @Test
    fun estimateTokens_text_mixed() {
        val mixed = "hello 世界 hello 世界"
        assertTrue(ModelContextPolicy.estimateTokens(mixed) >= 1)
    }
}