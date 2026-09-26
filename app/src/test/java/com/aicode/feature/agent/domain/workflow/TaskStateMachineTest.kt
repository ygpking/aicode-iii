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
    fun crashOnlyFromRunning() {
        assertEquals(
            TransitionResult.Moved(TaskState.RUNNING, TaskState.RECOVERABLE),
            TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.CRASH_DETECTED),
        )
        // 从 PENDING 不能因崩溃进入 RECOVERABLE
        assertTrue(TaskStateMachine.transition(TaskState.PENDING, TaskEvent.CRASH_DETECTED) is TransitionResult.Rejected)
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
            val result = TaskStateMachine.transition(terminal, TaskEvent.START)
            assertTrue("终态 $terminal 不应接受事件", result is TransitionResult.Rejected)
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
