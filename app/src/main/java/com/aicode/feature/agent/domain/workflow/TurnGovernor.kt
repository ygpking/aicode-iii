package com.aicode.feature.agent.domain.workflow

/**
 * 单轮裁决结果。
 */
internal sealed interface TurnVerdict {
    /** 继续下一轮。 */
    data object Continue : TurnVerdict

    /** 到达段尾：建议注入「收束提示」（提示模型总结并落进度），随后进入下一段。 */
    data object InjectWrapUpNotice : TurnVerdict

    /** 预算彻底耗尽：停止并给出原因。 */
    data class HardStop(val reason: StopReason) : TurnVerdict
}

internal enum class StopReason { ROUNDS_EXHAUSTED, SEGMENTS_EXHAUSTED }

/**
 * 轮次预算治理器（纯状态机，零依赖）。
 *
 * 把总预算拆成若干「段」，每段含 [roundsPerSegment] 轮；段与段之间允许最多
 * [maxContinuations] 次续跑。段尾（本段最后一轮结束）返回 [TurnVerdict.InjectWrapUpNotice]，
 * 让调用方先提示「收束」；最后一段结束才 [TurnVerdict.HardStop]。
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
            return TurnVerdict.InjectWrapUpNotice
        }
        return TurnVerdict.Continue
    }

    /** 显式查询剩余总轮数（含未启用的续跑段），供对外展示。 */
    fun totalRemaining(): Int {
        val remainingSegments = (maxContinuations - continuationsUsed).coerceAtLeast(0)
        return remainingInSegment + remainingSegments * roundsPerSegment
    }
}
