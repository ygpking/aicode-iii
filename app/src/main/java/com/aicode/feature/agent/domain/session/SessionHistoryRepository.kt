package com.aicode.feature.agent.domain.session

/**
 * 会话历史中的一条消息（翻阅用途的只读投影）。
 *
 * 与 `AgentMessageEntity` 分开：实体带 `toolCallsJson`/`signature`/`thinkingBlocksJson`
 * 等仅回放需要的字段，翻阅只关心「谁在什么时候说了什么、是否已被压缩折叠」，
 * 让领域层不依赖 Room 实体（`domain` 不得直连 DAO / 数据实体）。
 */
data class SessionHistoryItem(
    val timestamp: Long,
    val role: String,
    val content: String,
    /** 该消息是否已被上下文压缩折叠（`isCompacted`）。 */
    val compacted: Boolean
)

/** 一个压缩块的概览（恢复工具的列表行）。 */
data class CompactionBlockOverview(
    val blockId: String,
    /** 该块当前持有的原文条数（不含 marker/summary）。 */
    val originalCount: Int,
    /** 当前处于已折叠状态的条数（恢复后为 0）。 */
    val compactedCount: Int,
    /** 覆盖的时间窗起点/终点。 */
    val minTimestamp: Long,
    val maxTimestamp: Long,
    /** 该块摘要的首行预览（截到 [SUMMARY_PREVIEW_CHARS] 字符）；无摘要为空串。 */
    val summaryPreview: String
) {
    companion object {
        const val SUMMARY_PREVIEW_CHARS = 120
    }
}

/**
 * 会话历史翻阅端口。
 *
 * 只读，且**包含已压缩的消息**——压缩只是把这些消息标记为不再回放，原文仍在库里；
 * 排除它们就等于把被折叠的信息永久对模型隐藏。
 */
interface SessionHistoryRepository {

    /**
     * 按关键词与时间游标翻阅某会话的历史（含已压缩消息），按时间倒序返回最多 [limit] 条。
     *
     * @param keyword 空串表示不筛选；否则在正文上做子串匹配（调用方需已转义 LIKE 通配符）。
     * @param beforeTimestamp 大于 0 时只返回早于该毫秒时间戳的消息，用于翻上一页。
     */
    suspend fun browse(
        sessionId: String,
        keyword: String,
        beforeTimestamp: Long,
        limit: Int
    ): List<SessionHistoryItem>

    /** 与 [browse] 同一筛选口径下的总条数，供调用方判断是否还有更早的内容。 */
    suspend fun count(sessionId: String, keyword: String): Int

    /** 列出会话内的压缩块（按时间升序），含每块摘要预览。 */
    suspend fun listCompactionBlocks(sessionId: String): List<CompactionBlockOverview>

    /**
     * 恢复一个压缩块：原文回到回放，块内 marker/summary 退场。
     * 返回 null 表示块不存在；成功时返回回灌上下文的原文条数。
     */
    suspend fun restoreBlock(sessionId: String, blockId: String): Int?
}
