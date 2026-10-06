package com.aicode.feature.agent.domain.workflow

/**
 * 单轮裁决结果。
 */
internal sealed interface TurnVerdict {
    /** 继续下一轮。 */
    data object Continue : TurnVerdict

    /** 到达段边界：注入一次「收束提示」（每边界仅一次），随后进入下一段。 */
    data object InjectWrapUpNotice : TurnVerdict

    /** 预算彻底耗尽：停止并给出原因。 */
    data class HardStop(val reason: StopReason) : TurnVerdict
}

internal enum class StopReason { ROUNDS_EXHAUSTED, SEGMENTS_EXHAUSTED }

/**
 * 轮次预算治理器（纯状态机，零依赖）。
 *
 * 把总预算拆成若干「段」，每段含 [roundsPerSegment] 轮；段与段之间允许最多
 * [maxContinuations] 次续跑。每个段边界（新段开始前）只返回一次 [TurnVerdict.InjectWrapUpNotice]，
 * 让调用方先提示「收束」；最后一段结束才 [TurnVerdict.HardStop]。
 *
 * 注意：旧实现在段尾与跨段各注入一次，导致连续两条收束提示背靠背入历史（每边界双倍）；
 * 现改为每边界仅一次——段尾那轮照常执行，提示只在新段开始前注入。总预算不变。
 *
 * 这是对原先「单个硬上限 [StatefulAgentWorkflow] 越过即停」的收敛：预算未耗尽前先软收敛，
 * 而不是一声不响地跑到顶。
 */
internal class TurnGovernor(
    private val roundsPerSegment: Int,
    private val maxContinuations: Int,
) {
    init {
        require(roundsPerSegment > 0) { "roundsPerSegment 必须为正" }
        require(maxContinuations >= 0) { "maxContinuations 不能为负" }
    }

    private var roundInSegment = 0
    private var continuationsUsed = 0

    val segmentsUsed: Int get() = continuationsUsed

    /** 剩余段内轮数（下限保护，不为负）。 */
    val remainingInSegment: Int get() = (roundsPerSegment - roundInSegment).coerceAtLeast(0)

    /**
     * 开始新一轮。返回对「是否还能继续 / 是否需要收束」的裁决。
     */
    fun beginTurn(): TurnVerdict {
        if (roundInSegment >= roundsPerSegment) {
            if (continuationsUsed >= maxContinuations) {
                return TurnVerdict.HardStop(StopReason.SEGMENTS_EXHAUSTED)
            }
            continuationsUsed++
            roundInSegment = 0
            return TurnVerdict.InjectWrapUpNotice
        }
        roundInSegment++
        if (roundInSegment == roundsPerSegment) {
            if (continuationsUsed >= maxContinuations) {
                return TurnVerdict.HardStop(StopReason.ROUNDS_EXHAUSTED)
            }
            // 段尾这轮照常执行；收束提示只在下一段开始前注入（避免同一边界双倍提示）。
            return TurnVerdict.Continue
        }
        return TurnVerdict.Continue
    }

    /** 显式查询剩余总轮数（含未启用的续跑段），供对外展示。 */
    fun totalRemaining(): Int {
        val remainingSegments = (maxContinuations - continuationsUsed).coerceAtLeast(0)
        return remainingInSegment + remainingSegments * roundsPerSegment
    }
}
