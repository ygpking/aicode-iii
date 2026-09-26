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
    fun nonWhitelistedNonTerminalFailsClosed() {
        // RECOVERABLE / PENDING 属非终态但不在「进行中」白名单 → 保守失败，避免二次复活。
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "RECOVERABLE", "", 0) is RecoveryVerdict.FailClosed)
        assertTrue(CrashRecoveryPlanner.plan("t", "s", "PENDING", "", 0) is RecoveryVerdict.FailClosed)
    }
}
