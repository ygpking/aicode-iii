package com.aicode.feature.agent.domain.workflow

/**
 * 崩溃恢复的判定结果。
 */
sealed interface RecoveryVerdict {
    /** 可恢复：上次进程被杀时任务仍在进行，应提示用户可继续。 */
    data class Recoverable(val taskId: String, val sessionId: String, val promptSnippet: String, val round: Int) : RecoveryVerdict

    /** 应直接置为失败：状态异常（如未知状态字符串），保守收尾，不复活。 */
    data class FailClosed(val taskId: String, val reason: String) : RecoveryVerdict

    /** 无需处理：已是终态。 */
    data object Skip : RecoveryVerdict
}

/**
 * 崩溃恢复判定（纯函数、无 IO）。
 *
 * fail-closed 原则：只有**明确处于进行中状态**（RUNNING / WAITING_APPROVAL / PAUSED）的任务
 * 才判为可恢复；状态字符串无法解析时判为 [RecoveryVerdict.FailClosed]（保守置失败），
 * 绝不因「读不懂状态」而复活任务。已终态直接 [RecoveryVerdict.Skip]。
 *
 * 注意：判定为「可恢复」不等于「自动重跑」——只是标记出来供用户选择继续。
 */
object CrashRecoveryPlanner {

    /** 崩溃时仍在进行、可恢复的状态。 */
    private val IN_PROGRESS_STATES = setOf(
        TaskState.RUNNING,
        TaskState.WAITING_APPROVAL,
        TaskState.PAUSED,
    )

    fun plan(taskId: String, sessionId: String, rawState: String, promptSnippet: String, round: Int): RecoveryVerdict {
        val state = TaskStateMachine.parse(rawState)
            ?: return RecoveryVerdict.FailClosed(taskId, "未知任务状态：$rawState")
        if (TaskStateMachine.isTerminal(state)) return RecoveryVerdict.Skip
        return if (state in IN_PROGRESS_STATES) {
            RecoveryVerdict.Recoverable(taskId, sessionId, promptSnippet, round)
        } else {
            // 非终态但不在「进行中」白名单（如 RECOVERABLE 自身、PENDING）：保守置失败，避免二次复活。
            RecoveryVerdict.FailClosed(taskId, "状态 $state 不在可恢复白名单")
        }
    }
}
