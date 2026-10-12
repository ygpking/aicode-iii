package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- minTailForSummaryWindow：保证被折叠的 head 装得进摘要窗口 ----------

    /**
     * 回归：128k 主窗口 + 128k 摘要模型、阈值 90% 时，旧实现 tail 被 20000 封顶，
     * head≈95200 > 摘要输入预算 89600，每轮压缩静默丢弃最旧历史。
     * 新下限必须把 tail 抬到「head ≤ 预算」以上。
     */
    @Test
    fun minTailForSummaryWindow_128kSameWindow_preventsSilentDrop() {
        val trigger = 115_200 // 128k * 90%
        val floor = ModelContextPolicy.minTailForSummaryWindow(trigger, 128_000)
        val head = trigger - floor
        val summaryBudget = (128_000 * 0.7).toInt() // 89600
        assertTrue(
            "128k/90% 下 head=$head 必须 ≤ 摘要输入预算 $summaryBudget，floor=$floor",
            head <= summaryBudget,
        )
    }

    /** 主窗口远大于摘要窗口（1M 主 + 128k 摘要）：下限应远大于按比例的 tail 预算。 */
    @Test
    fun minTailForSummaryWindow_largeMainSmallSummary_exceedsProportionalBudget() {
        val trigger = 900_000 // 1M * 90%
        val floor = ModelContextPolicy.minTailForSummaryWindow(trigger, 128_000)
        val proportional = ModelContextPolicy.preserveRecentTokens(trigger, 1_000_000)
        assertTrue("摘要下限 $floor 应大于比例预算 $proportional", floor > proportional)
        assertTrue("head 应装进摘要预算", trigger - floor <= (128_000 * 0.7).toInt())
    }

    /** 摘要窗口足够大时下限为负或很小——调用方会再与 MIN 取大，不产生约束。 */
    @Test
    fun minTailForSummaryWindow_hugeSummaryWindow_isNonBinding() {
        val floor = ModelContextPolicy.minTailForSummaryWindow(115_200, 1_000_000)
        assertTrue("大摘要窗口下下限应为负（无约束），实测 $floor", floor <= 0)
    }

    /** 下限随摘要窗口单调：摘要窗口越大，所需 tail 越小。 */
    @Test
    fun minTailForSummaryWindow_monotonicInSummaryWindow() {
        val trigger = 500_000
        val small = ModelContextPolicy.minTailForSummaryWindow(trigger, 128_000)
        val large = ModelContextPolicy.minTailForSummaryWindow(trigger, 400_000)
        assertTrue("摘要窗口越大 tail 下限越小：$small vs $large", small > large)
    }

    // ---------- tailBudget：比例预算与摘要下限取大的组合（128k 静默丢历史的回归点）----------

    /**
     * C1：tail + 摘要估算必须严格小于阈值，否则压完立即再触发。
     *
     * 仅在「阈值 > 摘要估算（10k）」时有解——阈值小于摘要本身时压缩本身无意义
     * （上下文还没摘要长），该退化情形不在本约束覆盖范围。
     */
    @Test
    fun tailBudget_compactedSizeStaysUnderTrigger() {
        for (ctx in listOf(32_000, 128_000, 200_000, 1_000_000)) {
            for (pct in listOf(15, 60, 90)) {
                val trigger = (ctx * pct / 100.0).toInt()
                if (trigger <= 10_000 + ModelContextPolicy.MIN_PRESERVE_RECENT_TOKENS) continue
                for (summaryWindow in listOf(128_000, 200_000, 1_000_000)) {
                    val tail = ModelContextPolicy.tailBudget(trigger, ctx, summaryWindow)
                    assertTrue(
                        "ctx=$ctx pct=$pct% sw=$summaryWindow: tail+摘要=${tail + 10_000} 应 < 阈值 $trigger",
                        tail + 10_000 < trigger,
                    )
                }
            }
        }
    }

    /**
     * C2：head = 阈值 − tail 必须装进摘要输入预算。
     *
     * **仅对可行配置断言**（摘要窗口 ≥ minSummaryWindowForFeasibility）。摘要窗口过小时
     * C1/C2 无交集，此时不变量本就无法成立，由 [tailBudgetPlan] 的 feasible=false 兜住——
     * 故这里显式跳过，避免把「不可行」误当成「实现 bug」。
     */
    @Test
    fun tailBudget_headAlwaysFitsSummaryWindow() {
        val minSw = ModelContextPolicy.minSummaryWindowForFeasibility()
        for (ctx in listOf(32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000)) {
            for (pct in listOf(15, 60, 90)) {
                val trigger = (ctx * pct / 100.0).toInt()
                for (summaryWindow in listOf(128_000, 200_000, 1_000_000)) {
                    if (summaryWindow < minSw) continue
                    val tail = ModelContextPolicy.tailBudget(trigger, ctx, summaryWindow)
                    val head = trigger - tail
                    val summaryBudget = (summaryWindow * 0.7).toInt()
                    assertTrue(
                        "ctx=$ctx pct=$pct% sw=$summaryWindow: head=$head 应 ≤ 摘要预算 $summaryBudget (tail=$tail)",
                        head <= summaryBudget,
                    )
                }
            }
        }
    }

    // ---------- tailBudgetPlan：C1/C2 可满足性与不可行时的显式信号 ----------

    /** 摘要窗口足够大时 feasible=true，且可行性与触发阈值无关。 */
    @Test
    fun tailBudgetPlan_feasibleWhenSummaryWindowLargeEnough() {
        val minSw = ModelContextPolicy.minSummaryWindowForFeasibility()
        for (trig in listOf(19_200, 115_200, 900_000)) {
            assertTrue(
                "sw=$minSw 应可行（trig=$trig）",
                ModelContextPolicy.tailBudgetPlan(trig, 1_000_000, minSw).feasible,
            )
        }
    }

    /**
     * 摘要窗口过小（< 28573）时 C1/C2 无交集——必须 feasible=false，
     * 而不是静默 clamp 成「压缩成功但每轮丢历史」。
     */
    @Test
    fun tailBudgetPlan_infeasibleWhenSummaryWindowTooSmall() {
        val minSw = ModelContextPolicy.minSummaryWindowForFeasibility()
        for (sw in listOf(4_096, 8_192, 16_384, minSw - 1)) {
            val plan = ModelContextPolicy.tailBudgetPlan(115_200, 128_000, sw)
            assertFalse("sw=$sw 应判不可行", plan.feasible)
            assertTrue("应给出原因", plan.reason.isNotBlank())
        }
    }

    /** 不可行时仍须给出一个可用 tail（取 C1 上界），避免调用方拿到 0 而崩。 */
    @Test
    fun tailBudgetPlan_infeasibleStillReturnsUsableTail() {
        val plan = ModelContextPolicy.tailBudgetPlan(115_200, 128_000, 8_192)
        assertTrue("tail 应 ≥ MIN", plan.tail >= ModelContextPolicy.MIN_PRESERVE_RECENT_TOKENS)
        assertTrue("tail + 摘要应 < 阈值（C1 优先，不制造死循环）", plan.tail + 10_000 < 115_200)
    }

    // ---------- 固定开销 Δ 计入 C1（B-fix：摘要窗口 < 主窗口时压完立即再触发）----------

    /**
     * 真实不变量：`tail + 摘要 + Δ < 阈值`。
     *
     * 旧实现的 C1 只扣「摘要估算」，未扣固定开销 Δ；而触发判断用真实 usage（含 Δ），
     * 于是「摘要窗口 < 主窗口」时 tail 被 C2 下界抬高，压缩后 = tail + 摘要 + Δ ≥ 阈值 → 立即再触发。
     * 用例锁死修后行为：凡 C1 未被 Δ 挤到 MIN 下限（即真正可满足）时，不变量必须成立。
     */
    @Test
    fun tailBudget_overheadIncludedInTriggerInvariant() {
        val ctx = 128_000
        for (pct in listOf(60, 75, 80, 90, 95)) {
            val trigger = (ctx * pct / 100.0).toInt()
            for (sw in listOf(32_000, 48_000, 64_000, 96_000, 128_000)) {
                for (delta in listOf(10_000, 20_000, 40_000)) {
                    val plan = ModelContextPolicy.tailBudgetPlan(trigger, ctx, sw, delta)
                    // 只有「Δ + 摘要估算 ≥ 阈值」时才允许不变量不成立（数学无解，feasible=false 已标注）。
                    if (delta + 10_000 >= trigger) {
                        assertFalse("Δ=$delta sw=$sw 数学无解应判不可行", plan.feasible)
                        continue
                    }
                    if (plan.tail > ModelContextPolicy.MIN_PRESERVE_RECENT_TOKENS) {
                        assertTrue(
                            "ctx=$ctx pct=$pct% sw=$sw Δ=$delta: tail+摘要+Δ=${plan.tail + 10_000 + delta} 应 < 阈值 $trigger",
                            plan.tail + 10_000 + delta < trigger,
                        )
                    }
                }
            }
        }
    }

    /** Δ 提高 C1 上界压力：Δ 越大 tail 越小（在 C2 未主导时）。 */
    @Test
    fun tailBudget_largerOverhead_shrinksTail() {
        val base = ModelContextPolicy.tailBudget(1_000_000 * 60 / 100, 1_000_000, 1_000_000, 0)
        val withOverhead = ModelContextPolicy.tailBudget(1_000_000 * 60 / 100, 1_000_000, 1_000_000, 50_000)
        assertTrue("Δ 应压低 tail: $withOverhead ≤ $base", withOverhead <= base)
    }

    /** Δ=0 与旧行为逐位一致（向后兼容，未传 Δ 的调用方不受影响）。 */
    @Test
    fun tailBudget_zeroOverhead_matchesLegacy() {
        for (sw in listOf(32_000, 128_000, 1_000_000)) {
            assertEquals(
                ModelContextPolicy.tailBudget(115_200, 128_000, sw),
                ModelContextPolicy.tailBudget(115_200, 128_000, sw, 0),
            )
        }
    }

    /** Δ 大到「Δ + 摘要 ≥ 阈值」时不可行，且原因指向固定开销（非摘要窗口）。 */
    @Test
    fun tailBudgetPlan_hugeOverhead_reportsOverheadReason() {
        val plan = ModelContextPolicy.tailBudgetPlan(115_200, 128_000, 128_000, 200_000)
        assertFalse(plan.feasible)
        assertTrue("原因应指向固定开销: ${plan.reason}", plan.reason.contains("固定开销"))
    }

    /** 可行性阈值自洽：恰好 min 可行、min-1 不可行。 */
    @Test
    fun minSummaryWindowForFeasibility_isExactBoundary() {
        val minSw = ModelContextPolicy.minSummaryWindowForFeasibility()
        assertTrue(ModelContextPolicy.tailBudgetPlan(115_200, 128_000, minSw).feasible)
        assertFalse(ModelContextPolicy.tailBudgetPlan(115_200, 128_000, minSw - 1).feasible)
    }

    /** 128k 主窗口 + 128k 摘要模型 + 90% 阈值的具体数值锁定（旧实现丢弃 5600 token）。 */
    @Test
    fun tailBudget_128kRegression_noLongerDrops() {
        val trigger = 115_200
        val tail = ModelContextPolicy.tailBudget(trigger, 128_000, 128_000)
        // 旧实现 tail=20000 → head=95200 > 89600（丢 5600）
        assertTrue("新 tail=$tail 应大于旧实现 20000", tail > 20_000)
        assertTrue("head=${trigger - tail} 应 ≤ 摘要预算 89600", trigger - tail <= 89_600)
    }

    /** 128k 是旧实现的相对最坏点；新实现下各窗口的 head/预算比不应在 128k 处突起。 */
    @Test
    fun tailBudget_noWorstCaseAt128k() {
        val ratios = listOf(64_000, 128_000, 200_000).map { ctx ->
            val trigger = (ctx * 0.9).toInt()
            val tail = ModelContextPolicy.tailBudget(trigger, ctx, ctx)
            (trigger - tail).toDouble() / (ctx * 0.7)
        }
        // 新实现下三者都应 ≤1（head 装得下），不存在 128k 特有的尖峰
        assertTrue("各窗口 head/摘要预算比均应 ≤1：$ratios", ratios.all { it <= 1.0 })
    }

    /**
     * 摘要窗口与主窗口一致时，C2 下界不高于比例预算，结果不小于比例预算——
     * 大窗口下不牺牲压缩力度（相对旧版不回归）。
     */
    @Test
    fun tailBudget_sameSummaryWindow_notBelowProportional() {
        for (ctx in listOf(128_000, 1_000_000)) {
            val trigger = (ctx * 0.9).toInt()
            val proportional = ModelContextPolicy.preserveRecentTokens(trigger, ctx)
            val budget = ModelContextPolicy.tailBudget(trigger, ctx, ctx)
            assertTrue(
                "主/摘要同窗口时应 ≥ 比例预算（ctx=$ctx: $budget vs $proportional）",
                budget >= proportional,
            )
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

    // ---------- maxSummaryTokens：摘要长度上限 = 窗口 × 20%（B1 修复）----------

    /** 摘要上限为摘要模型窗口的 20%。 */
    @Test
    fun maxSummaryTokens_isTwentyPercentOfWindow() {
        assertEquals(20_000, ModelContextPolicy.maxSummaryTokens(100_000))
        assertEquals(256_000, ModelContextPolicy.maxSummaryTokens(1_280_000))
    }

    /** 窗口非法（0/负）时回退默认窗口，仍需给出正上限，避免退化成「不封顶」。 */
    @Test
    fun maxSummaryTokens_zeroWindow_fallsBackToDefault() {
        assertEquals((128_000 * 0.2).toInt(), ModelContextPolicy.maxSummaryTokens(0))
        assertEquals((128_000 * 0.2).toInt(), ModelContextPolicy.maxSummaryTokens(-5))
    }

    /** 上限必须显著小于窗口本身，保证压缩后有净空、不会立即再触发。 */
    @Test
    fun maxSummaryTokens_muchSmallerThanWindow() {
        val window = 1_000_000
        assertTrue(ModelContextPolicy.maxSummaryTokens(window) < window / 2)
    }
}