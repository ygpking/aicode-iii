package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.DurableTaskEntity

@Dao
interface DurableTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DurableTaskEntity)

    @Query("SELECT * FROM durable_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DurableTaskEntity?

    /** 处于指定状态集合的任务（崩溃恢复时查非终态任务）。 */
    @Query("SELECT * FROM durable_tasks WHERE state IN (:states) ORDER BY updatedAt DESC")
    suspend fun getByStates(states: List<String>): List<DurableTaskEntity>

    @Query("SELECT * FROM durable_tasks WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun getBySession(sessionId: String): List<DurableTaskEntity>

    /** 清理超过 [cutoffTimestamp] 未更新的任务记录（防表无限增长）。 */
    @Query("DELETE FROM durable_tasks WHERE updatedAt < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM durable_tasks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM durable_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM durable_tasks")
    suspend fun deleteAll()
}
