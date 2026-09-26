package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * durable 任务记录：一次「长任务」的生命周期状态，供崩溃恢复判定。
 *
 * 每次用户请求开始建一条（RUNNING），正常结束置终态；进程被杀时状态仍停留在非终态，
 * 重启时据此判定「崩溃残留」。**不自动重跑**——仅标记为可恢复并提示用户，
 * 避免在用户未预期时自动执行有副作用的工具。
 */
@Entity(
    tableName = "durable_tasks",
    indices = [Index(value = ["sessionId"]), Index(value = ["state"])]
)
data class DurableTaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** [com.aicode.feature.agent.domain.workflow.TaskState] 的名称。 */
    val state: String,
    /** 用户请求摘要（用于恢复提示文案）。 */
    val promptSnippet: String = "",
    /** 已走到的 LLM 轮次（供诊断/展示进度）。 */
    val round: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
