package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.core.util.runCatchingCancellable
import com.aicode.feature.agent.data.local.dao.DurableTaskDao
import com.aicode.feature.agent.data.local.entity.DurableTaskEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本仓库的写入侧一律「尽力而为、失败只记日志」，但必须让协程取消照常传播。
 * 实现已提升为公共工具 [runCatchingCancellable]（全仓唯一答案，见 core/util/SafeCall.kt）；
 * 此处保留本地名 `guarded` 仅为不惊动下列调用点。
 */
private inline fun <T> guarded(block: () -> T): Result<T> = runCatchingCancellable(block)

/**
 * durable 任务账本：记录每次长任务的生命周期状态，供崩溃恢复判定。
 *
 * 写入侧尽力而为（失败只记日志，不影响主流程）；读取侧在冷启动时扫描非终态任务。
 */
@Singleton
class DurableTaskRepository @Inject constructor(
    private val dao: DurableTaskDao
) {
    private companion object {
        const val TAG = "DurableTaskRepo"

        /** 任务记录保留时长：超过则清理（防表无限增长）。 */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** 开始一次任务（RUNNING），返回任务 id。 */
    suspend fun begin(sessionId: String, prompt: String): String {
        val id = UUID.randomUUID().toString()
        val snippet = prompt.trim().take(80)
        guarded {
            dao.upsert(
                DurableTaskEntity(
                    id = id,
                    sessionId = sessionId,
                    state = TaskState.RUNNING.name,
                    promptSnippet = snippet,
                    round = 0
                )
            )
        }.onFailure { FileLogger.w(TAG, "写入 durable 任务失败: ${it.message}") }
        return id
    }

    /** 更新任务的轮次（每轮 LLM 完成后）。 */
    suspend fun updateRound(taskId: String, round: Int) {
        val current = guarded { dao.getById(taskId) }.getOrNull() ?: return
        guarded {
            dao.upsert(current.copy(round = round, updatedAt = System.currentTimeMillis()))
        }.onFailure { FileLogger.w(TAG, "更新任务轮次失败: ${it.message}") }
    }

    /** 迁移状态（经状态机；非法转移记录日志并忽略）。 */
    suspend fun transition(taskId: String, event: TaskEvent) {
        val current = guarded { dao.getById(taskId) }.getOrNull() ?: return
        val state = TaskStateMachine.parse(current.state) ?: return
        when (val result = TaskStateMachine.transition(state, event)) {
            is TransitionResult.Moved -> guarded {
                dao.upsert(current.copy(state = result.to.name, updatedAt = System.currentTimeMillis()))
            }.onFailure { FileLogger.w(TAG, "迁移任务状态失败: ${it.message}") }
            is TransitionResult.Rejected ->
                FileLogger.w(TAG, "非法任务转移被拒：${result.reason}")
        }
    }

    /** 结束任务（置终态）。 */
    suspend fun finish(taskId: String, event: TaskEvent) {
        transition(taskId, event)
        prune()
    }

    /**
     * 冷启动恢复扫描：把非终态任务按 [CrashRecoveryPlanner] 判定。
     * - 可恢复 → 置 RECOVERABLE 并返回给调用方提示用户；
     * - 不可判定/白名单外 → 保守置 FAILED（fail-closed，不复活）。
     */
    suspend fun scanForRecovery(): List<RecoveryVerdict> {
        val nonTerminal = guarded {
            dao.getByStates(TaskStateMachine.NON_TERMINAL_STATES.map { it.name })
        }.getOrNull() ?: return emptyList()

        val verdicts = ArrayList<RecoveryVerdict>(nonTerminal.size)
        for (task in nonTerminal) {
            val verdict = CrashRecoveryPlanner.plan(
                taskId = task.id,
                sessionId = task.sessionId,
                rawState = task.state,
                promptSnippet = task.promptSnippet,
                round = task.round
            )
            verdicts += verdict
            when (verdict) {
                is RecoveryVerdict.Recoverable -> guarded {
                    dao.upsert(task.copy(state = TaskState.RECOVERABLE.name, updatedAt = System.currentTimeMillis()))
                }
                is RecoveryVerdict.FailClosed -> guarded {
                    FileLogger.w(TAG, "任务 ${task.id} 保守置失败：${verdict.reason}")
                    dao.upsert(task.copy(state = TaskState.FAILED.name, updatedAt = System.currentTimeMillis()))
                }
                RecoveryVerdict.Skip -> Unit
            }
        }
        return verdicts
    }

    /** 清理超期任务记录。 */
    suspend fun prune() {
        guarded { dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS) }
            .onFailure { FileLogger.w(TAG, "清理 durable 任务失败: ${it.message}") }
    }
}
