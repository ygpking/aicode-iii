package com.aicode.feature.agent.domain.workflow

/**
 * durable 任务的生命周期状态。
 *
 * 终态：COMPLETED / FAILED / CANCELLED / EXHAUSTED（见 [TaskStateMachine.isTerminal]）。
 */
enum class TaskState {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    PAUSED,
    RECOVERABLE,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXHAUSTED,
}

/**
 * 驱动 [TaskState] 迁移的事件。
 */
enum class TaskEvent {
    START,
    REQUEST_APPROVAL,
    APPROVAL_GRANTED,
    APPROVAL_DENIED,
    PAUSE,
    RESUME,
    CRASH_DETECTED,
    SUCCEED,
    FAIL,
    CANCEL,
    EXHAUST_RETRIES,
}

/**
 * 一次转移请求的裁决结果。
 */
sealed interface TransitionResult {
    data class Moved(val from: TaskState, val to: TaskState) : TransitionResult

    data class Rejected(val from: TaskState, val event: TaskEvent, val reason: String) : TransitionResult
}

/**
 * durable 任务状态机（纯函数、无状态）。
 *
 * 穷尽转移表 + CAS 语义：未在表中的 (状态, 事件) 组合返回 [TransitionResult.Rejected]，不抛异常。
 * 崩溃恢复关键不变量：只有 [TaskState.RUNNING] 可因 [TaskEvent.CRASH_DETECTED] 进入
 * [TaskState.RECOVERABLE]——已结束的任务不会因进程被杀而「复活」。
 */
object TaskStateMachine {

    private val TERMINAL_STATES = setOf(
        TaskState.COMPLETED,
        TaskState.FAILED,
        TaskState.CANCELLED,
        TaskState.EXHAUSTED,
    )

    /** 非终态：崩溃恢复时需扫描并判定的状态集合。 */
    val NON_TERMINAL_STATES: Set<TaskState> = TaskState.values().toSet() - TERMINAL_STATES

    private val TABLE: Map<TaskState, Map<TaskEvent, TaskState>> = mapOf(
        TaskState.PENDING to mapOf(
            TaskEvent.START to TaskState.RUNNING,
        ),
        TaskState.RUNNING to mapOf(
            TaskEvent.REQUEST_APPROVAL to TaskState.WAITING_APPROVAL,
            TaskEvent.PAUSE to TaskState.PAUSED,
            TaskEvent.CRASH_DETECTED to TaskState.RECOVERABLE,
            TaskEvent.SUCCEED to TaskState.COMPLETED,
            TaskEvent.FAIL to TaskState.FAILED,
            TaskEvent.CANCEL to TaskState.CANCELLED,
        ),
        TaskState.WAITING_APPROVAL to mapOf(
            TaskEvent.APPROVAL_GRANTED to TaskState.RUNNING,
            TaskEvent.APPROVAL_DENIED to TaskState.FAILED,
            TaskEvent.PAUSE to TaskState.PAUSED,
        ),
        TaskState.PAUSED to mapOf(
            TaskEvent.RESUME to TaskState.RUNNING,
            TaskEvent.CANCEL to TaskState.CANCELLED,
        ),
        TaskState.RECOVERABLE to mapOf(
            TaskEvent.START to TaskState.RUNNING,
            TaskEvent.EXHAUST_RETRIES to TaskState.EXHAUSTED,
            TaskEvent.CANCEL to TaskState.CANCELLED,
        ),
    )

    fun transition(from: TaskState, event: TaskEvent): TransitionResult {
        val to = TABLE[from]?.get(event)
            ?: return TransitionResult.Rejected(from, event, rejectReason(from, event))
        return TransitionResult.Moved(from, to)
    }

    fun isTerminal(state: TaskState): Boolean = state in TERMINAL_STATES

    /** 从持久化字符串解析状态；非法值返回 null（视为无法判定，调用方按保守策略处理）。 */
    fun parse(raw: String): TaskState? = runCatching { TaskState.valueOf(raw) }.getOrNull()

    private fun rejectReason(from: TaskState, event: TaskEvent): String =
        if (isTerminal(from)) {
            "终态 $from 不接受事件 $event"
        } else {
            "非法转移：$from 不接受事件 $event"
        }
}
