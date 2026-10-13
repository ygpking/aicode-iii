package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.workflow.DurableTaskRepository
import com.aicode.feature.agent.domain.workflow.EvidenceLedgerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新建会话默认 AUTO，以及一次性把已有根会话切到 AUTO 的选择范围。 */
class SessionUseCaseDefaultModeTest {

    private fun session(id: String, mode: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = "/root/workspace",
        mode = mode,
        parentId = parentId
    )

    private fun useCase(chatDao: ChatSessionDao) = SessionUseCase(
        chatDao,
        mockk<AgentMessageDao>(relaxed = true),
        dagger.Lazy { mockk<DurableTaskRepository>(relaxed = true) },
        dagger.Lazy { mockk<CheckpointManager>(relaxed = true) },
        dagger.Lazy { mockk<EvidenceLedgerRepository>(relaxed = true) }
    )

    @Test
    fun newSessionEntity_defaultsToAuto() {
        val entity = useCase(mockk(relaxed = true)).newSessionEntity("/root/workspace")
        assertEquals(AgentMode.AUTO.name, entity.mode)
        assertEquals(null, entity.modeBeforePlan)
    }

    @Test
    fun switchRootSessionsToAuto_rewritesOnlyRootSessionsNotYetAuto() = runTest {
        val dao = mockk<ChatSessionDao>(relaxed = true)
        coEvery { dao.getAllOnce() } returns listOf(
            session("root-build", AgentMode.BUILD.name),
            session("root-plan", AgentMode.PLAN.name),
            session("root-auto", AgentMode.AUTO.name),
            session("sub-build", AgentMode.BUILD.name, parentId = "root-build")
        )

        val changed = useCase(dao).switchRootSessionsToAuto()

        val saved = slot<List<ChatSessionEntity>>()
        coVerify(exactly = 1) { dao.upsertAll(capture(saved)) }
        // 子代理会话、停在 PLAN 的会话、已是 AUTO 的根会话都不写
        assertEquals(listOf("root-build"), saved.captured.map { it.id })
        // 写回的模式必须逐条改成 AUTO（不能只看第一条）
        assertEquals(listOf(AgentMode.AUTO.name), saved.captured.map { it.mode })
        assertEquals(1, changed)
    }

    @Test
    fun switchRootSessionsToAuto_skipsPlanSessions() = runTest {
        val dao = mockk<ChatSessionDao>(relaxed = true)
        coEvery { dao.getAllOnce() } returns listOf(
            session("only-plan", AgentMode.PLAN.name)
        )

        val changed = useCase(dao).switchRootSessionsToAuto()

        // PLAN 被用户当作「临时禁写」，迁移不得把它推成 AUTO
        coVerify(exactly = 0) { dao.upsertAll(any()) }
        assertEquals(0, changed)
    }

    @Test
    fun switchRootSessionsToAuto_allAlreadyAuto_writesNothing() = runTest {
        val dao = mockk<ChatSessionDao>(relaxed = true)
        coEvery { dao.getAllOnce() } returns listOf(session("root-a", AgentMode.AUTO.name))

        val changed = useCase(dao).switchRootSessionsToAuto()

        coVerify(exactly = 0) { dao.upsertAll(any()) }
        assertEquals(0, changed)
    }
}
