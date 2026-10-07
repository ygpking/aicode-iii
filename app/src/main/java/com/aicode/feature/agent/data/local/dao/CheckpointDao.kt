package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity

@Dao
interface CheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity)

    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity>

    // 撤销必须限定会话：不同会话的 checkpoint 可能挂着同一个 messageId 之外的历史，
    // 全局按 messageId 查会定位到别会话的 checkpoint 并还原它名下的文件。
    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId AND userMessageId = :messageId LIMIT 1")
    suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity?

    @Query("SELECT * FROM session_checkpoints WHERE id = :checkpointId LIMIT 1")
    suspend fun getCheckpointById(checkpointId: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId")
    suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity>

    @Query("SELECT COUNT(*) FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId AND filePath = :filePath")
    suspend fun countSnapshot(checkpointId: String, filePath: String): Int

    /** 本会话快照覆盖的去重文件清单（含 CREATE 型）；供崩溃恢复时提示「重跑前需核对的文件」。 */
    @Query(
        "SELECT DISTINCT filePath FROM checkpoint_file_snapshots " +
            "WHERE checkpointId IN (SELECT id FROM session_checkpoints WHERE sessionId = :sessionId) " +
            "ORDER BY filePath"
    )
    suspend fun listDistinctFilesForSession(sessionId: String): List<String>

    @Query("DELETE FROM session_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpointsForSession(sessionId: String)

    @Query("DELETE FROM checkpoint_file_snapshots WHERE checkpointId IN (SELECT id FROM session_checkpoints WHERE sessionId = :sessionId)")
    suspend fun deleteFileSnapshotsForSession(sessionId: String)

    /** 清空全部：先 [deleteAllFileSnapshots] 再 [deleteAllCheckpoints]，反过来会留下孤儿快照行。 */
    @Query("DELETE FROM checkpoint_file_snapshots")
    suspend fun deleteAllFileSnapshots()

    @Query("DELETE FROM session_checkpoints")
    suspend fun deleteAllCheckpoints()
}
