package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecoveryPlannerTest {

    @Test
    fun runningIsRecoverable() {
        val v = CrashRecoveryPlanner.plan("t1", "s1", "RUNNING", "写个功能", 3)
        assertTrue(v is RecoveryVerdict.Recoverable)
        v as RecoveryVerdict.Recoverable
        assertEquals("t1", v.taskId)
        assertEquals("s1", v.sessionId)
        assertEquals(3, v.round)
    }

    @Test
    fun waitingApprovalAndPausedAreRecoverable() {
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "WAITING_APPROVAL", "", 0) is RecoveryVerdict.Recoverable)
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "PAUSED", "", 0) is RecoveryVerdict.Recoverable)
    }

    @Test
    fun recoverableStateIsRecoverable() {
        // RECOVERABLE 是「上次崩溃已检测到、等用户决定是否续跑」的状态，必须仍被判可恢复。
        // 曾将它排除在白名单外，导致二次启动时把上次遗留的待恢复任务静默置为 FAILED，
        // 用户永远看不到恢复入口——与「崩溃后可续」的设计意图直接冲突。
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "RECOVERABLE", "", 0) is RecoveryVerdict.Recoverable)
    }

    @Test
    fun terminalStatesAreSkipped() {
        for (state in listOf("COMPLETED", "FAILED", "CANCELLED", "EXHAUSTED")) {
            assertSame(RecoveryVerdict.Skip, CrashRecoveryPlanner.plan("t", "s", state, "", 0))
        }
    }

    @Test
    fun unknownStateFailsClosed() {
        // 读不懂状态 → 保守置失败，绝不复活。
        val v = CrashRecoveryPlanner.plan("t", "s", "SOMETHING_WEIRD", "", 0)
        assertTrue(v is RecoveryVerdict.FailClosed)
        assertTrue((v as RecoveryVerdict.FailClosed).reason.contains("未知"))
    }

    @Test
    fun pendingFailsClosed() {
        // PENDING 从未开始执行（没有可续的进度），保守置失败。
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "PENDING", "", 0) is RecoveryVerdict.FailClosed)
    }
}
