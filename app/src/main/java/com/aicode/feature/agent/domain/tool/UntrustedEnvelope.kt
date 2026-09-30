package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 不可信来源信封：把外部内容包进 `<untrusted source="...">…</untrusted>`，并转义其中的
 * XML 元字符，使内容里夹带的「忽略以上指令…」之类文本无法伪造信封边界、也不能与平台自身的
 * 指令混为一谈。
 *
 * 来源判定只覆盖**外部 / 第三方内容**：网页抓取与搜索、浏览器页面、虚拟屏读取、MCP 服务器输出。
 * **本地工作区文件读取（readFile 等）不加信封**——那是用户自己的内容，加了会把正常代码里的
 * `&`/`<` 一并转义，反而污染上下文。
 *
 * 转义只针对 `&` 与 `<`：这两个字符足以阻止伪造闭合标签；`>` 在 XML 文本内容中无需转义，
 * 保留原文可减少对代码类输出（`MCP` 常回代码片段）的可读性损伤。
 *
 * 移植自 OpenSquilla `safety/injection_guard.py::wrap_untrusted`（Apache-2.0），Kotlin 重写并收窄转义集。
 */
object UntrustedEnvelope {

    /** 信封开标签前缀，用于判定「是否已包裹」，保证重复应用幂等。 */
    private const val OPEN_PREFIX = "<untrusted source=\""

    /** MCP 工具统一命名空间前缀（见 McpTool：`mcp__<server>__<tool>`）。 */
    private const val MCP_PREFIX = "mcp__"

    /** 明确的外部内容工具（按小写比较）。 */
    private val EXTERNAL_TOOLS: Set<String> = setOf(
        "webfetch",
        "websearch",
        "web_search",
        "web_fetch",
        "browser",
        "virtualscreen",
    )

    /**
     * 结果对象里属于**结构元数据**的键：不参与包裹。
     * 否则 `output_path` 也会被裹进信封，模型就无法按路径回读落盘原文了。
     */
    private val METADATA_KEYS: Set<String> = setOf(
        "output_truncated",
        "output_total_chars",
        "output_path",
        "output_storage_error",
    )

    /**
     * 该工具的输出是否属于不可信来源。是则返回用作 `source` 属性的稳定名，否则返回 null。
     * MCP 工具名本身即来源标识，原样使用（保留 `mcp__server__tool` 便于定位到具体服务器）。
     */
    fun sourceFor(toolName: String): String? {
        val name = toolName.trim()
        if (name.isEmpty()) return null
        if (name.startsWith(MCP_PREFIX)) return name
        val lower = name.lowercase()
        return if (lower in EXTERNAL_TOOLS) lower else null
    }

    /** 转义 XML 元字符（只处理足以伪造标签的 `&` 与 `<`），防止内容逃逸出信封。 */
    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")

    /** 属性值转义：先做 XML 转义，再处理引号。 */
    private fun escapeAttribute(text: String): String =
        escapeXml(text).replace("\"", "&quot;")

    /**
     * 包裹一段正文。空串原样返回（不值得为空内容加信封）；
     * 已包裹的内容原样返回（幂等，避免嵌套信封）。
     */
    fun wrap(source: String, body: String): String {
        if (body.isEmpty()) return body
        if (body.startsWith(OPEN_PREFIX)) return body
        return "$OPEN_PREFIX${escapeAttribute(source)}\">${escapeXml(body)}</untrusted>"
    }

    /**
     * 对工具结果应用信封。只处理 [ToolResult.Success] / [ToolResult.Partial] 的正文数据；
     * [ToolResult.Error] 是平台自产错误文案（不含外部正文），不包裹。
     *
     * 调用时机：应放在输出已被截断/落盘**之后**（见 [ToolOutputStore.process]）——
     * 否则截断可能切断闭合标签，留下未闭合的信封。
     */
    fun apply(toolName: String, result: ToolResult): ToolResult {
        val source = sourceFor(toolName) ?: return result
        return when (result) {
            is ToolResult.Success -> ToolResult.Success(wrapElement(source, result.data), result.images)
            is ToolResult.Partial -> ToolResult.Partial(wrapElement(source, result.data), result.message)
            is ToolResult.Error -> result
        }
    }

    private fun wrapElement(source: String, element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(wrap(source, element.content)) else element

        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (key in METADATA_KEYS) value else wrapElement(source, value)
            }
        )

        is JsonArray -> JsonArray(element.map { wrapElement(source, it) })
    }
}
