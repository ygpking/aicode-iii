package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.DurableTaskDao
import com.aicode.feature.agent.data.local.entity.DurableTaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DurableTaskRepository] 的恢复提示生命周期：能被冷启动反复扫出的行必须能真正收尾。
 *
 * 这组用例守护三类「用户可见的反复打扰」：
 * 1. 忽略后仍被下次冷启动扫出（只清内存没落库）；
 * 2. 记录因自转移反复刷新 `updatedAt`，永远够不到 prune 的保留期而堆积；
 * 3. 续跑后旧条目悬在 RECOVERABLE，同一件事被提示第二遍。
 */
class DurableTaskRecoveryLifecycleTest {

    private class FakeDurableTaskDao : DurableTaskDao {
        val rows = linkedMapOf<String, DurableTaskEntity>()

        override suspend fun upsert(task: DurableTaskEntity) {
            rows[task.id] = task
        }

        override suspend fun getById(id: String): DurableTaskEntity? = rows[id]

        override suspend fun getByStates(states: List<String>): List<DurableTaskEntity> =
            rows.values.filter { it.state in states }.sortedByDescending { it.updatedAt }

        override suspend fun getBySession(sessionId: String): List<DurableTaskEntity> =
            rows.values.filter { it.sessionId == sessionId }.sortedByDescending { it.updatedAt }

        override suspend fun deleteOlderThan(cutoffTimestamp: Long): Int {
            val stale = rows.values.filter { it.updatedAt < cutoffTimestamp }.map { it.id }
            stale.forEach { rows.remove(it) }
            return stale.size
        }

        override suspend fun deleteBySession(sessionId: String) {
            rows.values.filter { it.sessionId == sessionId }.map { it.id }.forEach { rows.remove(it) }
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private fun repoWith(dao: FakeDurableTaskDao) = DurableTaskRepository(dao)

    /** 崩溃残留的典型形态：一条停在 RECOVERABLE、等待用户决定的记录。 */
    private fun FakeDurableTaskDao.seedRecoverable(
        id: String = "t1",
        sessionId: String = "s1",
        // 默认用当前时间：远古时间戳会被 [DurableTaskRepository.prune] 当超期记录直接删掉，
        // 使后续断言取不到行。只有专测超期清理的用例才显式传旧值。
        updatedAt: Long = System.currentTimeMillis()
    ) {
        rows[id] = DurableTaskEntity(
            id = id,
            sessionId = sessionId,
            state = TaskState.RECOVERABLE.name,
            promptSnippet = "写个功能",
            round = 2,
            createdAt = 900L,
            updatedAt = updatedAt
        )
    }

    @Test
    fun dismiss_marksTaskTerminalSoNextScanDoesNotResurfaceIt() = runTest {
        val dao = FakeDurableTaskDao()
        dao.seedRecoverable()
        val repo = repoWith(dao)

        repo.dismiss("t1")

        assertEquals(TaskState.CANCELLED.name, dao.rows.getValue("t1").state)
        assertTrue(
            "忽略后不得再被冷启动扫出",
            repo.scanForRecovery().none { it is RecoveryVerdict.Recoverable }
        )
    }

    @Test
    fun repeatedScan_doesNotRefreshUpdatedAt() = runTest {
        val dao = FakeDurableTaskDao()
        val seeded = System.currentTimeMillis()
        dao.seedRecoverable(updatedAt = seeded)
        val repo = repoWith(dao)

        // 每次冷启动都会重扫 RECOVERABLE 并走一次幂等自转移。
        repeat(3) { repo.scanForRecovery() }

        assertEquals(
            "自转移不得刷新 updatedAt，否则记录永远够不到 prune 的保留期",
            seeded,
            dao.rows.getValue("t1").updatedAt
        )
    }

    @Test
    fun staleRecoverable_isEventuallyPruned() = runTest {
        val dao = FakeDurableTaskDao()
        // 超过 7 天保留期的记录。
        dao.seedRecoverable(updatedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        val repo = repoWith(dao)

        repo.scanForRecovery()

        assertTrue("超过保留期的记录应被清理，避免长期堆积", dao.rows.isEmpty())
    }

    @Test
    fun scanForRecovery_stillReportsRecoverableTask() = runTest {
        val dao = FakeDurableTaskDao()
        dao.seedRecoverable()
        val repo = repoWith(dao)

        val verdicts = repo.scanForRecovery()

        assertEquals(1, verdicts.count { it is RecoveryVerdict.Recoverable })
        assertEquals(TaskState.RECOVERABLE.name, dao.rows.getValue("t1").state)
    }

    @Test
    fun recoverableCarriesSessionContextFromPlanner() = runTest {
        // 会话归属由 ViewModel 在扫描后补全（domain 层拿不到会话表），规划器只是把它透传，
        // 这里守护透传不在中间环节被丢掉。
        val verdict = CrashRecoveryPlanner.plan(
            taskId = "t1",
            sessionId = "s1",
            rawState = TaskState.RUNNING.name,
            promptSnippet = "写个功能",
            round = 1,
            sessionTitle = "重构登录",
            isSubAgent = true
        ) as RecoveryVerdict.Recoverable

        assertEquals("重构登录", verdict.sessionTitle)
        assertTrue(verdict.isSubAgent)
    }

    /**
     * 用户主动停止任务时，记录必须落到终态。
     *
     * 回归的是「用户明明按了停止，下次启动却报『上次异常退出』」：
     * 停止会让队列下一条在同一会话上立即接管，旧 job 的收尾若拿「当前 job 是否本会话的 job」
     * 当条件就会跳过收尾，记录永远残在 RUNNING，冷启动按非终态判为崩溃残留。
     */
    @Test
    fun cancelIfRunning_marksRunningTaskTerminal() = runTest {
        val dao = FakeDurableTaskDao()
        dao.rows["t1"] = DurableTaskEntity(
            id = "t1",
            sessionId = "s1",
            state = TaskState.RUNNING.name,
            promptSnippet = "写个功能",
            round = 1,
            createdAt = 900L,
            updatedAt = System.currentTimeMillis()
        )
        val repo = repoWith(dao)

        repo.cancelIfRunning("t1")

        assertEquals(TaskState.CANCELLED.name, dao.rows.getValue("t1").state)
        assertTrue(
            "主动停止的任务不得被冷启动报成崩溃残留",
            repo.scanForRecovery().none { it is RecoveryVerdict.Recoverable }
        )
    }

    /**
     * 取消收尾会在「任务已自然跑完」之后被触发（收尾阶段自身被取消），
     * 此时状态已非 RUNNING，不得再改动——否则每次都要刷一条「非法任务转移」告警。
     */
    @Test
    fun cancelIfRunning_leavesSettledTaskUntouched() = runTest {
        val dao = FakeDurableTaskDao()
        dao.rows["t1"] = DurableTaskEntity(
            id = "t1",
            sessionId = "s1",
            state = TaskState.COMPLETED.name,
            promptSnippet = "写个功能",
            round = 1,
            createdAt = 900L,
            updatedAt = System.currentTimeMillis()
        )
        val repo = repoWith(dao)

        repo.cancelIfRunning("t1")

        assertEquals(TaskState.COMPLETED.name, dao.rows.getValue("t1").state)
    }
}
