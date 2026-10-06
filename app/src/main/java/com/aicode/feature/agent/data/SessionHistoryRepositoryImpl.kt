package com.aicode.feature.agent.data

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.session.CompactionBlockOverview
import com.aicode.feature.agent.domain.session.SessionHistoryItem
import com.aicode.feature.agent.domain.session.SessionHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SessionHistoryRepository] 的 Room 实现。
 *
 * 存在的意义是把 DAO 挡在领域层之外（架构约定 R3：domain 层不得直连 Room DAO）。
 */
@Singleton
class SessionHistoryRepositoryImpl @Inject constructor(
    private val agentMessageDao: AgentMessageDao
) : SessionHistoryRepository {

    override suspend fun browse(
        sessionId: String,
        keyword: String,
        beforeTimestamp: Long,
        limit: Int
    ): List<SessionHistoryItem> =
        agentMessageDao.browseSessionHistory(sessionId, keyword, beforeTimestamp, limit)
            .map { row ->
                SessionHistoryItem(
                    timestamp = row.timestamp,
                    role = row.role,
                    content = row.content,
                    compacted = row.isCompacted
                )
            }

    override suspend fun count(sessionId: String, keyword: String): Int =
        agentMessageDao.countSessionHistory(sessionId, keyword)

    override suspend fun listCompactionBlocks(sessionId: String): List<CompactionBlockOverview> =
        agentMessageDao.listCompactionBlocks(sessionId).map { row ->
            // 该块仍持有的原文条数：总行数减去 marker/summary 各一行（覆盖式语义下
            // 老块可能只剩部分原文，marker/summary 仍各占一行，减法恒成立）。
            val originalCount = (row.messageCount - 2).coerceAtLeast(0)
            CompactionBlockOverview(
                blockId = row.blockId,
                originalCount = originalCount,
                compactedCount = row.compactedCount,
                minTimestamp = row.minTimestamp,
                maxTimestamp = row.maxTimestamp,
                summaryPreview = agentMessageDao.getCompactionBlockSummary(sessionId, row.blockId)
                    .orEmpty()
                    .lineSequence().firstOrNull { it.isNotBlank() }
                    ?.take(CompactionBlockOverview.SUMMARY_PREVIEW_CHARS)
                    .orEmpty()
            )
        }

    override suspend fun restoreBlock(sessionId: String, blockId: String): Int? {
        // 块归属校验：本会话内不存在该块 id 则拒绝，防止跨会话 id 猜测。
        val blocks = agentMessageDao.listCompactionBlocks(sessionId)
        if (blocks.none { it.blockId == blockId }) return null
        val toRestore = agentMessageDao.countCompactedByBlock(sessionId, blockId)
        agentMessageDao.restoreCompactionBlock(sessionId, blockId)
        return toRestore
    }
}
