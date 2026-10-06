package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionHistoryItem
import com.aicode.feature.agent.domain.session.SessionHistoryPager
import com.aicode.feature.agent.domain.session.SessionHistoryRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 翻阅本会话的完整历史，**包含已被上下文压缩折叠的消息**。
 *
 * 存在的理由：压缩把早期消息标记 `isCompacted=true` 后不再回放，但原文仍在库里。
 * 此前没有入口能读回它们——跨会话搜索 [com.aicode.feature.agent.data.local.dao.AgentMessageDao.searchInWorkspace]
 * 明确排除已压缩消息，AI 手上也没有任何会话记录查询工具，于是「压缩后细节找不回来」。
 *
 * 两条硬约束：
 * 1. **只读当前会话**：不提供 sessionId 参数，拒绝让模型翻阅他人会话。多会话并行时
 *    拿 [AgentContext.sessionId] 精确限定，避免跨会话串味。
 * 2. **双上限守卫**：条数与总字符都设上限，防止一次调用把整段历史灌回上下文、把窗口直接撑爆
 *    （本工具的目的恰恰是缓解窗口压力，不能反过来加剧它）。
 */
class BrowseHistoryTool @Inject constructor(
    private val sessionHistoryRepository: SessionHistoryRepository
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "BrowseHistoryTool"
    }

    override val name = "browseHistory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities: Set<ToolCapability> = emptySet()
    override val description =
        "检索本会话的历史（含已被上下文压缩折叠、当前上下文里看不到的消息），用于定位：判断某事是否发生过、" +
            "大致在哪、涉及哪些文件。**正文是截断的**（单条最多 ${SessionHistoryPager.MAX_CHARS_PER_MESSAGE} 字符、" +
            "单页总字符封顶 ${SessionHistoryPager.MAX_TOTAL_CHARS}），引用报错原文、代码片段或精确措辞时给不出，" +
            "那种情形改用 restoreCompactedRange 把原文放回上下文。" +
            "默认返回最近 ${SessionHistoryPager.DEFAULT_LIMIT} 条，每页最多 ${SessionHistoryPager.MAX_LIMIT} 条。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "keyword" to ToolParameter(
            name = "keyword",
            type = ParameterType.STRING,
            description = "可选的检索词，在消息正文里做子串匹配。为空则按时间倒序返回最近的消息。",
            required = false
        ),
        "before_timestamp" to ToolParameter(
            name = "before_timestamp",
            type = ParameterType.INTEGER,
            description = "可选的时间游标：只返回早于该毫秒时间戳的消息，用于取上一页。" +
                "结果里会回传 next_before_timestamp。",
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "本页最多返回多少条，默认 ${SessionHistoryPager.DEFAULT_LIMIT}，上限 ${SessionHistoryPager.MAX_LIMIT}。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
        if (sessionId.isNullOrBlank()) {
            return ToolResult.Error(
                "翻阅历史失败：当前会话没有 sessionId，无法定位历史记录。" +
                    "本工具只能查当前会话，请勿尝试指定其他会话来绕过。",
                "MISSING_SESSION"
            )
        }

        val rawKeyword = args["keyword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val keyword = SessionHistoryPager.escapeLike(rawKeyword)
        val beforeTimestamp = args["before_timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
        val limit = SessionHistoryPager.clampLimit(args["limit"]?.jsonPrimitive?.intOrNull)

        val total = sessionHistoryRepository.count(sessionId, keyword)
        if (total == 0) {
            // 空结果也算「翻过了」：记一条轨迹，事后能分辨「没查」与「查了但没有」。
            EventTrace.snapshot(
                sessionId,
                "HISTORY",
                "翻阅历史：keyword=${rawKeyword.ifEmpty { "（空）" }} 命中 0 条"
            )
            return ToolResult.Success(
                resultMap(
                    messages = emptyList(),
                    total = 0,
                    hasMore = false,
                    nextBefore = null,
                    charBudgetHit = false
                )
            )
        }

        val rows = sessionHistoryRepository.browse(sessionId, keyword, beforeTimestamp, limit)

        // 字符预算守卫：按时间倒序（最新在前）逐条装入，超预算即停止并标记——
        // 这样任何单次调用都不可能撑爆窗口，剩余的靠游标继续翻。
        val page = SessionHistoryPager.pack(rows)
        val packed = page.items
        val charBudgetHit = page.truncatedByChars

        val nextBefore = if (packed.size < total) packed.lastOrNull()?.timestamp else null

        FileLogger.i(
            TAG,
            "翻阅历史 会话=$sessionId keyword=${rawKeyword.ifEmpty { "（空）" }} " +
                "返回 ${packed.size}/$total 条 游标=$beforeTimestamp 字符预算触顶=$charBudgetHit"
        )
        // 成功路径的过程事实：轨迹不会自动记成功调用，这里补一次，
        // 让「压缩后模型是否真的回来捞过历史」在日志里可查。
        EventTrace.snapshot(
            sessionId,
            "HISTORY",
            "翻阅历史：keyword=${rawKeyword.ifEmpty { "（空）" }} 返回 ${packed.size}/$total 条" +
                (if (charBudgetHit) " 字符预算触顶" else "") +
                (if (nextBefore != null) " 可继续翻" else " 已到底")
        )

        return ToolResult.Success(
            resultMap(
                messages = packed,
                total = total,
                hasMore = nextBefore != null,
                nextBefore = nextBefore,
                charBudgetHit = charBudgetHit
            )
        )
    }

    private fun resultMap(
        messages: List<SessionHistoryItem>,
        total: Int,
        hasMore: Boolean,
        nextBefore: Long?,
        charBudgetHit: Boolean
    ): JsonObject {
        val items = messages.map { row ->
            JsonObject(
                buildMap {
                    put("timestamp", JsonPrimitive(row.timestamp))
                    put("role", JsonPrimitive(row.role))
                    put("content", JsonPrimitive(SessionHistoryPager.truncate(row.content)))
                    // 标记是否为已压缩消息：模型据此知道这段来自「被折叠的历史」，
                    // 而非当前上下文里还在的内容，避免重复处理。
                    put("compacted", JsonPrimitive(row.compacted))
                }
            )
        }

        val map = mutableMapOf<String, JsonElement>(
            "messages" to JsonArray(items),
            "returned" to JsonPrimitive(messages.size),
            "total_matched" to JsonPrimitive(total),
            "has_more" to JsonPrimitive(hasMore)
        )
        nextBefore?.let { map["next_before_timestamp"] = JsonPrimitive(it) }

        val notes = mutableListOf<String>()
        if (hasMore) notes += "还有更早的匹配消息；用 next_before_timestamp 作为 before_timestamp 继续翻。"
        if (charBudgetHit) notes += "本页因总字符上限（${SessionHistoryPager.MAX_TOTAL_CHARS}）提前结束，减小 limit 可拿到更完整的单条内容。"
        if (total > messages.size && !hasMore) notes += "部分消息因条数上限未展示。"
        if (messages.any { it.compacted }) notes += "标记 compacted=true 的消息已被上下文压缩折叠，当前上下文里看不到它们。"
        if (notes.isNotEmpty()) map["note"] = JsonPrimitive(notes.joinToString(" "))

        return JsonObject(map)
    }
}
