package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 按需回取已落盘的工具输出（分页）。
 *
 * 移植自 OpenSquilla `tools/builtin/tool_results.py` + `engine/tool_result_query.py`（Apache-2.0）。
 *
 * 补上 AiCode 落盘策略的最后一块：`ToolOutputStore` 把超长输出落盘后只在内联里留
 * 头尾预览 + 一个 `output_path`，此前模型只能自己用 `readFile` 去读那个路径——既浪费一轮
 * 工具调用，又可能因 readFile 的行窗口语义不同而读错。本工具让模型直接按行分页取回落盘原文。
 *
 * 两条硬约束：
 * 1. **只读 `outputDir` 下的文件**（只取 basename 再解析），杜绝路径穿越。
 * 2. **若原文来自不可信来源**（如 webfetch），回取时**必须重新套上不可信信封**——
 *    落盘的是原始证据（未加信封），若原样喂回模型等于绕过了 [UntrustedEnvelope] 这道防线。
 *    来源工具由 [ToolOutputStore.sourceToolOf] 提供（仅同进程有效，取不到时保守地不补信封）。
 */
class RetrieveToolResultTool @Inject constructor(
    private val toolOutputStore: ToolOutputStore
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "RetrieveToolResultTool"
    }

    override val name = "retrieveToolResult"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities: Set<ToolCapability> = emptySet()
    override val description =
        "按需回取此前被落盘的超长工具输出。当某个工具结果里出现 output_path、且你只需要其中" +
            "某一段时，用本工具按行分页读取，比 readFile 更省上下文。默认每页 ${ToolResultPager.DEFAULT_MAX_LINES} 行。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "落盘文件路径（工具结果里的 output_path，如 /root/.aicode/tool-output/xxx.log），也可只给文件名。",
            required = true
        ),
        "start_line" to ToolParameter(
            name = "start_line",
            type = ParameterType.INTEGER,
            description = "开始行号（从 1 计），默认 1。",
            required = false
        ),
        "max_lines" to ToolParameter(
            name = "max_lines",
            type = ParameterType.INTEGER,
            description = "本页最多返回多少行，默认 ${ToolResultPager.DEFAULT_MAX_LINES}，上限 ${ToolResultPager.HARD_MAX_LINES}。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
        if (path.isNullOrEmpty()) return ToolResult.Error("缺少必需参数: path", "MISSING_PATH")

        val full = toolOutputStore.readBack(path)
            ?: return ToolResult.Error(
                "回取失败：找不到落盘文件「$path」，或它已被清理（落盘输出有 TTL/容量上限）。" +
                    "请改为重新执行原始工具，或直接用 readFile 读取工作区文件。",
                "SPILL_NOT_FOUND"
            )

        val startLine = args["start_line"]?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = args["max_lines"]?.jsonPrimitive?.intOrNull ?: ToolResultPager.DEFAULT_MAX_LINES
        val page = ToolResultPager.page(full, startLine, maxLines)

        // 回取的内容若源自不可信工具，必须重新套信封，否则等于绕过注入隔离。
        val sourceTool = toolOutputStore.sourceToolOf(path)
        val sourceLabel = UntrustedEnvelope.sourceFor(sourceTool ?: "")
        val body = if (sourceLabel != null) UntrustedEnvelope.wrap(sourceLabel, page.text) else page.text

        val resultMap = mutableMapOf<String, JsonElement>(
            "content" to JsonPrimitive(body),
            "total_lines" to JsonPrimitive(page.totalLines),
            "start_line" to JsonPrimitive(page.startLine),
            "end_line" to JsonPrimitive(page.endLine),
            "has_more" to JsonPrimitive(page.hasMore),
            "source_tool" to JsonPrimitive(sourceTool ?: "unknown")
        )
        val note = when {
            page.truncatedByChars ->
                "本页因字符上限（${ToolResultPager.MAX_PAGE_CHARS}）被截断，未显示完整行；请减小 max_lines 后从第 ${page.startLine} 行重新读取。"
            page.hasMore -> "还有后续内容；从第 ${page.endLine + 1} 行起用 start_line 继续读取。"
            else -> null
        }
        if (note != null) resultMap["note"] = JsonPrimitive(note)

        FileLogger.v(
            TAG,
            "回取落盘输出 path=$path source=${sourceTool ?: "unknown"} " +
                "page=[${page.startLine},${page.endLine}]/${page.totalLines} hasMore=${page.hasMore}"
        )
        return ToolResult.Success(JsonObject(resultMap))
    }
}
