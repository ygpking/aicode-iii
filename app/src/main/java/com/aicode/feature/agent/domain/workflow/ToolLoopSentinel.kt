package com.aicode.feature.agent.domain.workflow

/**
 * 死循环检测裁决。
 */
internal sealed interface LoopVerdict {
    data object Ok : LoopVerdict
    data class SuspectedLoop(val reason: LoopReason, val detail: String) : LoopVerdict
    data class Blocked(val reason: LoopReason) : LoopVerdict
}

internal enum class LoopReason { REPEATED_FAILURE, NO_PROGRESS_IDLE, PERIODIC_OSCILLATION }

/**
 * 工具调用死循环哨兵。
 *
 * 三层判定：
 * 1. **同工具同参连续失败** ≥ [repeatFailureLimit] → SuspectedLoop；
 * 2. **无进展空转**：连续调用（无论成功失败）的指纹完全重复 ≥ [idleLimit] → SuspectedLoop；
 * 3. **周期震荡**：最近 [oscillationWindow] 次调用呈 A,B,A,B… 周期 ≤ [maxPeriod] 重复 ≥ 3 周期 → SuspectedLoop。
 *
 * 参数指纹由调用方提供（应已做键排序等规范化，保证「同集合不同顺序」得到同一指纹）。
 * 达到 [blockThreshold] 次 SuspectedLoop 后升级为 [LoopVerdict.Blocked]。
 *
 * 与 [FailureCircuitBreaker] 的分工：熔断看「全失败」，哨兵看「重复模式」（含成功但不推进的空转）。
 */
internal class ToolLoopSentinel(
    private val repeatFailureLimit: Int = 3,
    private val idleLimit: Int = 4,
    private val oscillationWindow: Int = 6,
    private val maxPeriod: Int = 2,
    private val blockThreshold: Int = 2,
) {
    private data class Step(val tool: String, val fingerprint: String, val failed: Boolean)

    private val history = ArrayDeque<Step>()
    private var suspicionCount = 0

    fun observe(toolName: String, argsFingerprint: String, failed: Boolean): LoopVerdict {
        history.addLast(Step(toolName, argsFingerprint, failed))
        while (history.size > oscillationWindow * 2 + 8) history.removeFirst()

        val reason = detectRepeatedFailure()
            ?: detectIdle()
            ?: detectOscillation()

        return if (reason == null) {
            LoopVerdict.Ok
        } else {
            suspicionCount++
            if (suspicionCount >= blockThreshold) {
                LoopVerdict.Blocked(reason)
            } else {
                LoopVerdict.SuspectedLoop(reason, "tool=$toolName fp=$argsFingerprint")
            }
        }
    }

    fun reset() {
        history.clear()
        suspicionCount = 0
    }

    private fun detectRepeatedFailure(): LoopReason? {
        if (history.size < repeatFailureLimit) return null
        val tail = history.toList().takeLast(repeatFailureLimit)
        val allFailed = tail.all { it.failed }
        val sameTarget = tail.map { it.tool to it.fingerprint }.distinct().size == 1
        return if (allFailed && sameTarget) LoopReason.REPEATED_FAILURE else null
    }

    private fun detectIdle(): LoopReason? {
        if (history.size < idleLimit) return null
        val tail = history.toList().takeLast(idleLimit)
        val distinct = tail.map { it.tool to it.fingerprint }.distinct().size
        return if (distinct == 1) LoopReason.NO_PROGRESS_IDLE else null
    }

    private fun detectOscillation(): LoopReason? {
        if (history.size < oscillationWindow) return null
        val window = history.toList().takeLast(oscillationWindow)
        for (period in 1..maxPeriod) {
            if (window.size < period * 3) continue
            val repeats = window.size / period
            var periodic = true
            for (i in period until window.size) {
                val a = window[i]
                val b = window[i - period]
                if (a.tool != b.tool || a.fingerprint != b.fingerprint) {
                    periodic = false
                    break
                }
            }
            if (periodic && repeats >= 3) return LoopReason.PERIODIC_OSCILLATION
        }
        return null
    }
}
