package com.aicode.feature.agent.domain.session

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已有根会话切到 AUTO」迁移的幂等性——这是硬约束：否则用户手动切回 BUILD 的会话
 * 会在下次启动被再次拉回 AUTO。
 */
class AutoModeDefaultMigratorTest {

    private class Flag(private val applied: Boolean) : AutoModeMigrationFlag {
        var markCalled = false
        override suspend fun isApplied() = applied
        override suspend fun markApplied() {
            markCalled = true
        }
    }

    private fun migrator(useCase: SessionUseCase, flag: Flag) = AutoModeDefaultMigrator(useCase, flag)

    @Test
    fun applyIfNeeded_switchesSessionsAndMarksDone() = runTest {
        val useCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { useCase.switchRootSessionsToAuto() } returns 2
        val flag = Flag(applied = false)

        migrator(useCase, flag).applyIfNeeded()

        coVerify(exactly = 1) { useCase.switchRootSessionsToAuto() }
        assertTrue(flag.markCalled)
    }

    @Test
    fun applyIfNeeded_alreadyApplied_doesNotTouchSessions() = runTest {
        val useCase = mockk<SessionUseCase>(relaxed = true)

        // 标记已置位：即使库里还有 BUILD 会话也不再动它（用户手动切回的场景）
        migrator(useCase, Flag(applied = true)).applyIfNeeded()

        coVerify(exactly = 0) { useCase.switchRootSessionsToAuto() }
    }

    @Test
    fun applyIfNeeded_writeFails_doesNotMarkApplied() = runTest {
        val useCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { useCase.switchRootSessionsToAuto() } throws IllegalStateException("db closed")
        val flag = Flag(applied = false)

        migrator(useCase, flag).applyIfNeeded()

        // 标记未置位 → 下次启动重试；若此处误置位，迁移将永久失败
        assertFalse(flag.markCalled)
    }
}
