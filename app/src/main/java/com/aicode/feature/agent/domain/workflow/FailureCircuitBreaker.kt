package com.aicode.feature.agent.domain.workflow

/**
 * 熔断器状态。
 */
internal sealed interface BreakerState {
    /** 正常。 */
    data object Closed : BreakerState

    /** 触发软收敛：注入一次收束/纠偏提示（本状态只返回一次）。 */
    data object WarnedOnce : BreakerState

    /** 硬熔断：停止本 run。 */
    data class Tripped(val consecutiveFailures: Int) : BreakerState
}

/**
 * 连续失败熔断器。
 *
 * 维度为「轮」：一轮内**全部**工具失败记一次失败，任一成功即清零。
 * - 连续失败达到 [softThreshold]：返回一次 [BreakerState.WarnedOnce]（软收敛提示）；
 * - 连续失败达到 [hardThreshold]：返回 [BreakerState.Tripped]。
 *
 * 与 [TurnGovernor] 的分工：预算管「跑多久」，熔断管「一直失败就别再烧了」。
 */
internal class FailureCircuitBreaker(
    private val softThreshold: Int = 3,
    private val hardThreshold: Int = 8,
) {
    init {
        require(softThreshold > 0 && hardThreshold > softThreshold) {
            "需满足 0 < softThreshold < hardThreshold"
        }
    }

    private var consecutive = 0
    private var warnedThisStreak = false

    val consecutiveFailures: Int get() = consecutive

    fun record(roundAllFailed: Boolean): BreakerState {
        if (!roundAllFailed) {
            consecutive = 0
            warnedThisStreak = false
            return BreakerState.Closed
        }
        consecutive++
        if (consecutive >= hardThreshold) {
            return BreakerState.Tripped(consecutive)
        }
        if (consecutive >= softThreshold && !warnedThisStreak) {
            warnedThisStreak = true
            return BreakerState.WarnedOnce
        }
        return BreakerState.Closed
    }

    fun reset() {
        consecutive = 0
        warnedThisStreak = false
    }
}
