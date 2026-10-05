package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {

    @Test
    fun happyPath() {
        assertEquals(TransitionResult.Moved(TaskState.PENDING, TaskState.RUNNING), TaskStateMachine.transition(TaskState.PENDING, TaskEvent.START))
        assertEquals(TransitionResult.Moved(TaskState.RUNNING, TaskState.COMPLETED), TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.SUCCEED))
    }

    @Test
    fun approvalRoundTrip() {
        assertEquals(
            TransitionResult.Moved(TaskState.RUNNING, TaskState.WAITING_APPROVAL),
            TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.REQUEST_APPROVAL),
        )
        assertEquals(
            TransitionResult.Moved(TaskState.WAITING_APPROVAL, TaskState.RUNNING),
            TaskStateMachine.transition(TaskState.WAITING_APPROVAL, TaskEvent.APPROVAL_GRANTED),
        )
        assertEquals(
            TransitionResult.Moved(TaskState.WAITING_APPROVAL, TaskState.FAILED),
            TaskStateMachine.transition(TaskState.WAITING_APPROVAL, TaskEvent.APPROVAL_DENIED),
        )
    }

    @Test
    fun crashDetectedFromInProgressStates() {
        assertEquals(
            TransitionResult.Moved(TaskState.RUNNING, TaskState.RECOVERABLE),
            TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.CRASH_DETECTED),
        )
        // 崩溃恢复白名单（CrashRecoveryPlanner.IN_PROGRESS_STATES）里的每个非终态都必须能转入
        // RECOVERABLE，否则冷启动扫描出来的任务会静默卡在原状态、每次重启被反复扫到。
        assertEquals(
            TransitionResult.Moved(TaskState.WAITING_APPROVAL, TaskState.RECOVERABLE),
            TaskStateMachine.transition(TaskState.WAITING_APPROVAL, TaskEvent.CRASH_DETECTED),
        )
        assertEquals(
            TransitionResult.Moved(TaskState.PAUSED, TaskState.RECOVERABLE),
            TaskStateMachine.transition(TaskState.PAUSED, TaskEvent.CRASH_DETECTED),
        )
        // 已是 RECOVERABLE 的任务每次冷启动都会被重新扫到，需幂等自转而不是刷「非法转移」。
        assertEquals(
            TransitionResult.Moved(TaskState.RECOVERABLE, TaskState.RECOVERABLE),
            TaskStateMachine.transition(TaskState.RECOVERABLE, TaskEvent.CRASH_DETECTED),
        )
        // 从 PENDING 不能因崩溃进入 RECOVERABLE（已建账但未开始，无进展可恢复）
        assertTrue(TaskStateMachine.transition(TaskState.PENDING, TaskEvent.CRASH_DETECTED) is TransitionResult.Rejected)
    }

    @Test
    fun pendingCanBeFailedByRecoveryScan() {
        // 冷启动扫描把白名单外的非终态（如 PENDING）判失败收尾，需此转移能落地。
        assertEquals(
            TransitionResult.Moved(TaskState.PENDING, TaskState.FAILED),
            TaskStateMachine.transition(TaskState.PENDING, TaskEvent.FAIL),
        )
    }

    @Test
    fun recoverableCanResumeOrExhaustOrCancel() {
        assertEquals(
            TransitionResult.Moved(TaskState.RECOVERABLE, TaskState.RUNNING),
            TaskStateMachine.transition(TaskState.RECOVERABLE, TaskEvent.START),
        )
        assertEquals(
            TransitionResult.Moved(TaskState.RECOVERABLE, TaskState.EXHAUSTED),
            TaskStateMachine.transition(TaskState.RECOVERABLE, TaskEvent.EXHAUST_RETRIES),
        )
    }

    @Test
    fun terminalStatesRejectEverything() {
        for (terminal in listOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.EXHAUSTED)) {
            assertTrue(TaskStateMachine.isTerminal(terminal))
            for (event in listOf(TaskEvent.START, TaskEvent.CRASH_DETECTED)) {
                val result = TaskStateMachine.transition(terminal, event)
                assertTrue("终态 $terminal 不应接受事件 $event", result is TransitionResult.Rejected)
            }
        }
    }

    @Test
    fun illegalTransitionIsRejectedNotThrown() {
        val result = TaskStateMachine.transition(TaskState.PENDING, TaskEvent.SUCCEED)
        assertTrue(result is TransitionResult.Rejected)
    }

    @Test
    fun nonTerminalStatesExcludeTerminalOnes() {
        assertFalse(TaskState.COMPLETED in TaskStateMachine.NON_TERMINAL_STATES)
        assertTrue(TaskState.RUNNING in TaskStateMachine.NON_TERMINAL_STATES)
        assertTrue(TaskState.RECOVERABLE in TaskStateMachine.NON_TERMINAL_STATES)
    }

    @Test
    fun parseHandlesKnownAndUnknown() {
        assertEquals(TaskState.RUNNING, TaskStateMachine.parse("RUNNING"))
        assertNull(TaskStateMachine.parse("BOGUS"))
    }
}
