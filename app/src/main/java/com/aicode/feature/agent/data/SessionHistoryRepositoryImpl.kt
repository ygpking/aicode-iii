package com.aicode.feature.agent.data

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
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
}
