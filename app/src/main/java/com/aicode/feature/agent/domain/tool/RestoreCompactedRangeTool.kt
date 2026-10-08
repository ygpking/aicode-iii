package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.model.AgentContext
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 会话历史检索与压缩块管理（一个工具三个模式）。
 *
 * 三个 action 的语义差异：
 * - `preview`（默认）：把被折叠内容当**资料**读回来——截断预览，有双上限守卫，
 *   读回的结果下一轮可能又被压缩。只用于定位（判断某事是否发生过、在哪、涉及哪些文件）。
 * - `blocks`：列出可恢复的压缩块（块 id、条数、时间范围、摘要首行），供模型决定要不要恢复。
 * - `restore`：把原文**放回工作集**——下一轮起这些消息原样参与上下文回放，块内摘要与锚点退场。
 *   这是拿到完整原文（报错原文、代码片段、精确措辞）的唯一路子，但回灌量等于块原文条数，
 *   恢复段过大时下一轮可能立即再次压缩，属重操作，非必要不用。
 *
 * 两条硬约束（与翻阅同一套纪律）：
 * 1. **只操作当前会话**：不提供 sessionId 参数；块归属按会话校验，跨会话的块 id 一律拒绝。
 * 2. **双上限守卫**：preview 的条数与总字符都设上限，防止一次调用把整段历史灌回上下文。
 */
class RestoreCompactedRangeTool @Inject constructor(
    private val sessionHistoryRepository: SessionHistoryRepository,
    private val checkpointManager: CheckpointManager
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "RestoreCompactedRange"
    }

    override val name = "restoreCompactedRange"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities: Set<ToolCapability> = emptySet()
    override val description =
        "会话历史检索与压缩块管理（一个工具三个模式，action 必看）：\n" +
            "1) preview（默认）：检索本会话历史（含已被压缩折叠、当前上下文里看不到的消息），" +
            "返回**截断预览**（单条最多 ${SessionHistoryPager.MAX_CHARS_PER_MESSAGE} 字符、整页封顶 ${SessionHistoryPager.MAX_TOTAL_CHARS} 字符）。" +
            "按 keyword 子串匹配或时间翻页。用于定位：判断某事是否发生过、大致在哪、涉及哪些文件。" +
            "报错原文、代码片段、精确措辞给不出（已截断），要完整原文用 action=restore 恢复整块。" +
            "压缩内务消息（marker/摘要）不会出现在结果里。\n" +
            "2) blocks：列出本会话全部可恢复压缩块（块 id、条数、时间范围、摘要首行），不带 block_id 调 restore 等同此模式。\n" +
            "3) restore：把指定压缩块的完整原文放回上下文回放（块内摘要退场）。" +
            "需要**引用原文**时用——压缩摘要只够定位，preview 只给截断片段，要报错原文、代码片段、" +
            "精确数值或某句话的措辞就得把原文放回上下文。回灌量约等于该块原文条数，慎用于刚压缩的窗口，无必要时别用。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作模式：preview=检索预览（默认）、blocks=列压缩块、restore=恢复整块原文。",
            required = false,
            enum = listOf("preview", "blocks", "restore")
        ),
        "keyword" to ToolParameter(
            name = "keyword",
            type = ParameterType.STRING,
            description = "preview 模式：可选的检索词，在消息正文里做子串匹配。为空则按时间倒序返回最近的消息。",
            required = false
        ),
        "before_timestamp" to ToolParameter(
            name = "before_timestamp",
            type = ParameterType.INTEGER,
            description = "preview 模式：可选的时间游标，只返回早于该毫秒时间戳的消息，用于取上一页。结果里会回传 next_before_timestamp。",
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "preview 模式：本页最多返回多少条，默认 ${SessionHistoryPager.DEFAULT_LIMIT}，上限 ${SessionHistoryPager.MAX_LIMIT}。",
            required = false
        ),
        "block_id" to ToolParameter(
            name = "block_id",
            type = ParameterType.STRING,
            description = "restore 模式：要恢复的压缩块 id（来自 blocks 模式）。缺省时等同 blocks 模式只列块、不恢复。",
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
                "操作失败：当前会话没有 sessionId，无法定位历史记录或压缩块。本工具只能查当前会话。",
                "MISSING_SESSION"
            )
        }

        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            .ifEmpty { "preview" }
        return when (action) {
            "preview" -> preview(sessionId, args)
            "blocks" -> listBlocks(sessionId)
            "restore" -> restore(sessionId, args)
            else -> ToolResult.Error(
                "未知 action=$action，可用值：preview / blocks / restore。",
                "BAD_ACTION"
            )
        }
    }

    private suspend fun preview(sessionId: String, args: Map<String, JsonElement>): ToolResult {
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
                JsonObject(
                    mapOf(
                        "messages" to JsonArray(emptyList()),
                        "total_matched" to JsonPrimitive(0),
                        "has_more" to JsonPrimitive(false),
                        "note" to JsonPrimitive("没有匹配的历史消息。")
                    )
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

        val items = packed.map { row ->
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
            "returned" to JsonPrimitive(items.size),
            "total_matched" to JsonPrimitive(total),
            "has_more" to JsonPrimitive(nextBefore != null)
        )
        nextBefore?.let { map["next_before_timestamp"] = JsonPrimitive(it) }

        val notes = mutableListOf<String>()
        if (nextBefore != null) notes += "还有更早的匹配消息；用 next_before_timestamp 作为 before_timestamp 继续翻。"
        if (charBudgetHit) notes += "本页因总字符上限（${SessionHistoryPager.MAX_TOTAL_CHARS}）提前结束；单条正文也被截断，要引用原文请用 action=restore 恢复整块。"
        if (total > items.size && nextBefore == null) notes += "部分消息因条数上限未展示。"
        if (items.any { it["compacted"] == JsonPrimitive(true) }) notes += "标记 compacted=true 的消息已被上下文压缩折叠，当前上下文里看不到它们。"
        if (notes.isNotEmpty()) map["note"] = JsonPrimitive(notes.joinToString(" "))

        return ToolResult.Success(JsonObject(map))
    }

    private suspend fun listBlocks(sessionId: String): ToolResult {
        val blocks = sessionHistoryRepository.listCompactionBlocks(sessionId)
        if (blocks.isEmpty()) {
            return ToolResult.Success(JsonObject(emptyMap()))
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val items = blocks.map { b ->
            JsonObject(
                mapOf(
                    "block_id" to JsonPrimitive(b.blockId),
                    "original_count" to JsonPrimitive(b.originalCount),
                    "compacted_count" to JsonPrimitive(b.compactedCount),
                    "time_range" to JsonPrimitive(
                        "${fmt.format(Date(b.minTimestamp))} ~ ${fmt.format(Date(b.maxTimestamp))}"
                    ),
                    "summary_preview" to JsonPrimitive(b.summaryPreview)
                )
            )
        }
        EventTrace.snapshot(sessionId, "COMPACTION", "列出压缩块：共 ${blocks.size} 个")
        return ToolResult.Success(
            JsonObject(mapOf("blocks" to JsonArray(items)))
        )
    }

    private suspend fun restore(sessionId: String, args: Map<String, JsonElement>): ToolResult {
        val blockId = args["block_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (blockId.isEmpty()) {
            // 没带 block_id 等同 blocks 模式：先让模型看清每块覆盖的范围与摘要再决定。
            return listBlocks(sessionId)
        }
        val blocks = sessionHistoryRepository.listCompactionBlocks(sessionId)
        if (blocks.none { it.blockId == blockId }) {
            return ToolResult.Error(
                "恢复失败：当前会话不存在压缩块 $blockId。" +
                    (if (blocks.isEmpty()) "本会话还没有可恢复的压缩块。"
                     else "可用的块：${blocks.joinToString { it.blockId.take(8) }}…，请先用 action=blocks 列出。"),
                "BLOCK_NOT_FOUND"
            )
        }

        val restored = sessionHistoryRepository.restoreBlock(sessionId, blockId)
        if (restored == null) {
            return ToolResult.Error("恢复失败：块 $blockId 校验未通过。", "BLOCK_NOT_FOUND")
        }
        FileLogger.i(TAG, "恢复压缩块 会话=$sessionId 块=$blockId 回灌 $restored 条原文")
        EventTrace.snapshot(sessionId, "COMPACTION", "恢复压缩块 ${blockId.take(8)}：回灌 $restored 条原文")
        // note 必须按 restored（恢复前该块仍持有的折叠行数）报告：后续压缩可能接管了旧块部分
        // 原文（assignCompactionBlock 覆盖式归属），此时 compactedCount 快照数与实际回灌不符。
        val note = if (restored == 0) {
            "实际回灌 0 条：该块原文可能已被后续压缩接管、或此前已恢复过。" +
                "可先 action=blocks 重新列块确认。"
        } else {
            // 大块提示：恢复段直接进 tail 保护区，restored 过大时下一轮可能立即再次压缩。
            "已恢复：原文回到上下文回放，本块的旧摘要已退场。实际回灌 $restored 条原文，下轮请求生效。" +
                (if (restored > 50) " 恢复段较大，下一轮请求可能立即再次触发压缩。" else "")
        }
        // 崩溃恢复场景的关键提示：崩溃前工具已写的文件不随恢复回滚，重跑会重复副作用。
        // 这里只列清单让模型/用户显式决定（不自动回滚——回滚是改用户文件的副作用）。
        val modifiedFiles = runCatching {
            checkpointManager.listSessionModifiedFiles(sessionId)
        }.getOrDefault(emptyList())
        val resultMap = mutableMapOf<String, JsonElement>(
            "restored_messages" to JsonPrimitive(restored),
            "note" to JsonPrimitive(note)
        )
        if (modifiedFiles.isNotEmpty()) {
            resultMap["files_modified_before_crash"] = JsonArray(modifiedFiles.map { JsonPrimitive(it) })
            resultMap["note"] = JsonPrimitive(
                note + " 注意：本会话崩溃前工具已修改过 ${modifiedFiles.size} 个文件（见 " +
                    "files_modified_before_crash），重跑任务前先核对这些文件是否需要回滚。"
            )
        }
        return ToolResult.Success(JsonObject(resultMap))
    }
}
