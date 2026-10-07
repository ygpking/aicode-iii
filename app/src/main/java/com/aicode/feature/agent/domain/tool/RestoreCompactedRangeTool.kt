package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionHistoryRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 恢复已压缩的历史区间（把一次上下文压缩整体撤销）。
 *
 * 与 [BrowseHistoryTool] 的分工：翻阅是把被折叠内容当**资料**读回来（有截断，
 * 且读回的结果下一轮可能又被压缩）；恢复是把原文**放回工作集**——下一轮起
 * 这些消息原样参与上下文回放，块内摘要与锚点退场。
 *
 * 两条硬约束（与翻阅工具同一套纪律）：
 * 1. **只操作当前会话**：不提供 sessionId 参数；块归属按会话校验，跨会话的块 id 一律拒绝。
 * 2. **先列后恢复**：block_id 为空时只列块清单（默认路径），让模型看清每块覆盖的范围
 *    与摘要预览再决定；直接带 block_id 调用则立即恢复并返回回灌条数。
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
        "恢复一段已被上下文压缩折叠的历史：原文回到上下文回放，块内摘要退场。" +
            "需要**引用原文**时用本工具——压缩摘要只够定位，browseHistory 只给截断片段，" +
            "要报错原文、代码片段、精确数值或某句话的措辞就得把原文放回上下文。" +
            "不带 block_id 调用时列出全部可恢复的压缩块；确认后带 block_id 恢复。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "block_id" to ToolParameter(
            name = "block_id",
            type = ParameterType.STRING,
            description = "要恢复的压缩块 id（来自列表结果）。缺省时只列块、不恢复。",
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
                "恢复失败：当前会话没有 sessionId，无法定位压缩块。",
                "MISSING_SESSION"
            )
        }

        val blockId = args["block_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val blocks = sessionHistoryRepository.listCompactionBlocks(sessionId)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        if (blockId.isEmpty()) {
            if (blocks.isEmpty()) {
                return ToolResult.Success(JsonObject(emptyMap()))
            }
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
                JsonObject(mapOf("blocks" to kotlinx.serialization.json.JsonArray(items)))
            )
        }

        if (blocks.none { it.blockId == blockId }) {
            return ToolResult.Error(
                "恢复失败：当前会话不存在压缩块 $blockId。" +
                    (if (blocks.isEmpty()) "本会话还没有可恢复的压缩块。"
                     else "可用的块：${blocks.joinToString { it.blockId.take(8) }}…，请先不带参数列出。"),
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
                "可先不带参数重新列块确认。"
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
