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

    /** 按 id 读一条任务；供恢复入口回取任务入账时刻。 */
    suspend fun getById(taskId: String): DurableTaskEntity? =
        guarded { dao.getById(taskId) }.getOrNull()

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
     * - 可恢复 → 经状态机置 RECOVERABLE 并返回给调用方提示用户；
     * - 不可判定/白名单外 → 保守置 FAILED（fail-closed，不复活）。
     *
     * 此处一律走 [transition]，不直接写 state 字符串：状态机是状态的唯一入口，
     * 绕过它会让「谁能迁到 RECOVERABLE」这条不变量只存在于注释里。
     * 扫描后再串一次 [prune]：历史实现对冷启动置位的记录从不清理，
     * 用户不处理的 RECOVERABLE 会长期堆积（原先只有 finish 路径会 prune）。
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
                is RecoveryVerdict.Recoverable -> transition(task.id, TaskEvent.CRASH_DETECTED)
                is RecoveryVerdict.FailClosed -> {
                    FileLogger.w(TAG, "任务 ${task.id} 保守置失败：${verdict.reason}")
                    // 状态字符串本身无法解析时 transition 会静默 return，这类记录将永远
                    // 滞在非终态、每次冷启动被反复扫到。故解析失败时直接写 FAILED 强收尾。
                    if (TaskStateMachine.parse(task.state) == null) {
                        guarded {
                            dao.upsert(task.copy(state = TaskState.FAILED.name, updatedAt = System.currentTimeMillis()))
                        }
                    } else {
                        transition(task.id, TaskEvent.FAIL)
                    }
                }
                RecoveryVerdict.Skip -> Unit
            }
        }
        prune()
        return verdicts
    }

    /** 清理超期任务记录。 */
    suspend fun prune() {
        guarded { dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS) }
            .onFailure { FileLogger.w(TAG, "清理 durable 任务失败: ${it.message}") }
    }
}
