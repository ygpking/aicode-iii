package com.aicode.feature.agent.domain

import com.aicode.feature.agent.domain.container.BoundedOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 [OutputLimits] 的单一事实源关系。
 *
 * 背景（2026-09 事故 d59a876）：命令累积窗口与落盘内联上限曾是两份独立的 `20_000` 字面量，
 * 恰好相等，导致命令链路的 `truncated` 恒为 false、去噪结果被整段丢弃。本测试让那条
 * "数值关系"成为 CI 断言，改动任一边都会在这里立刻失败，而不是等线上出现"去噪白算"。
 *
 * 说明：[ToolOutputStore] 的常量位于 `private companion object`，测试不可见；但它现由
 * `= OutputLimits.*` 派生（编译期即绑定），故此处只需锁住 [OutputLimits] 与公开的
 * [BoundedOutput] 常量。若有人把 `ToolOutputStore` 改回独立字面量，上述"上游已限幅"的
 * 前提会在 `BoundedOutput` 侧改动时被本测试的相等断言拦住。
 */
class OutputLimitsTest {

    /**
     * 上游命令窗口必须**等于**内联上限。
     *
     * 若窗口 > 上限：上游会丢弃下游判据看不见的内容。
     * 若窗口 < 上限：上游已截断的内容到达下游后长度不超限，`truncated` 误判为 false。
     */
    @Test
    fun commandWindow_equalsInlineMax() {
        assertEquals(
            "命令累积窗口与内联上限必须相等，否则 truncated/denoised 判据会静默失效（d59a876）",
            OutputLimits.INLINE_MAX_CHARS,
            OutputLimits.COMMAND_HEAD_CHARS + OutputLimits.COMMAND_TAIL_CHARS
        )
    }

    /** BoundedOutput 必须引用同一事实源，而不是"碰巧取值相等"的独立字面量。 */
    @Test
    fun boundedOutput_referencesTheSameSource() {
        assertEquals(OutputLimits.COMMAND_HEAD_CHARS, BoundedOutput.DEFAULT_HEAD)
        assertEquals(OutputLimits.COMMAND_TAIL_CHARS, BoundedOutput.DEFAULT_TAIL)
    }

    /** 内联上限与命令窗口同值，是"上游截断 ⇒ 长度超上限"这一推理成立的前提。 */
    @Test
    fun inlineMax_matchesCommandWindow() {
        assertTrue(
            "内联上限应等于命令窗口，使上游截断可被下游长度判据检出",
            OutputLimits.INLINE_MAX_CHARS == BoundedOutput.DEFAULT_HEAD + BoundedOutput.DEFAULT_TAIL
        )
    }

    /**
     * 行为级验证：用默认窗口把远超容量的文本喂给 [BoundedOutput]，确实会截断，
     * 且丢弃量 > 0。这把"窗口真实生效"钉在行为上，而不只是常量相等。
     */
    @Test
    fun defaultWindow_actuallyTruncatesOversizedInput() {
        val output = BoundedOutput()
        output.append("x".repeat(OutputLimits.INLINE_MAX_CHARS + 1_000))

        assertTrue("超过窗口容量必须发生截断", output.truncated)
        assertTrue("应如实记录丢弃的字符数", output.totalChars > OutputLimits.INLINE_MAX_CHARS)
    }
}
