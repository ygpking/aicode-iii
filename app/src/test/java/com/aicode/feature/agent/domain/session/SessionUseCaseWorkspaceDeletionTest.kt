package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.workflow.DurableTaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 删除工作区时按 workspacePath 级联清理会话与消息的行为。 */
class SessionUseCaseWorkspaceDeletionTest {

    private fun session(id: String, workspacePath: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = workspacePath,
        parentId = parentId
    )

    @Test
    fun deleteSessionsByWorkspace_deletesMessagesThenSessions() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val durableRepo = mockk<DurableTaskRepository>(relaxed = true)
        val checkpointMgr = mockk<CheckpointManager>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/a") } returns listOf(
            session("root", "/ws/a"),
            session("sub", "/ws/a", parentId = "root")
        )
        coEvery { chatDao.getSubSessionsByParentOnce("root") } returns listOf(
            session("sub", "/ws/a", parentId = "root")
        )

        val useCase = SessionUseCase(
            chatDao, messageDao,
            dagger.Lazy { durableRepo }, dagger.Lazy { checkpointMgr }
        )
        val deleted = useCase.deleteSessionsByWorkspace("/ws/a")

        // 新实现逐个走 deleteSession：账本/检查点清理下沉在同一出口，级联删的子会话也覆盖。
        assertEquals(2, deleted)
        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(atLeast = 1) { messageDao.deleteBySession("sub") }
        coVerify { durableRepo.clearSession("root") }
        coVerify { durableRepo.clearSession("sub") }
        coVerify { checkpointMgr.clearSessionCheckpoints("root") }
        coVerify { checkpointMgr.clearSessionCheckpoints("sub") }
    }

    @Test
    fun deleteSessionsByWorkspace_noSessions_skipsDeletion() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/empty") } returns emptyList()

        val useCase = SessionUseCase(
            chatDao, messageDao,
            dagger.Lazy { mockk<DurableTaskRepository>(relaxed = true) },
            dagger.Lazy { mockk<CheckpointManager>(relaxed = true) }
        )
        val deleted = useCase.deleteSessionsByWorkspace("/ws/empty")

        assertEquals(0, deleted)
        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { chatDao.deleteByWorkspace(any()) }
    }
}