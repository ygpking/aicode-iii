package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP 派生的工具名（AiCode 形态 `mcp__<server>__<tool>`）。
 *
 * MCP 工具的参数由远端 server 定义，本地做别名或扁平键改写可能改坏合法参数，
 * 因此一律短路：不规整、不改名。
 */
internal fun isMcpToolName(name: String): Boolean = name.startsWith("mcp_")

/**
 * 参数规整：把常见方言收敛到规格声明的参数名。
 *
 * 只做三件事，且都以「规格已声明的键」为准绳，避免改坏合法参数：
 * - **别名**：`file_path` / `filePath` / `filepath` → `path`；
 * - **单键解包**：整包只有一个未声明键、其值对象的键全部已声明时，解包为内层；
 * - **扁平键还原**：`a__b`、`a.b` → 嵌套的 `a: { b: ... }`（仅当顶层键已声明）。
 *
 * MCP 工具原样返回。
 */
internal object ArgNormalizer {
    private val ALIASES: Map<String, String> = mapOf(
        "file_path" to "path",
        "filePath" to "path",
        "filepath" to "path",
    )

    private val FLAT_KEY_SEPARATOR = Regex("__|\\.")

    fun normalize(spec: ToolSpec, args: Map<String, JsonElement>): Map<String, JsonElement> {
        if (isMcpToolName(spec.name)) return args

        val declared = spec.properties + spec.required
        val unwrapped = unwrapSingleKey(declared, args)
        val out = LinkedHashMap<String, JsonElement>(unwrapped.size)
        for ((rawKey, value) in unwrapped) {
            val key = ALIASES[rawKey] ?: rawKey
            val segments = key.split(FLAT_KEY_SEPARATOR).filter { it.isNotEmpty() }
            if (segments.size <= 1 || segments.first() !in declared) {
                // 顶层键未声明（或本就单段）→ 保留原键，不下钻，避免把合法参数改坏。
                out[key] = value
            } else {
                mergeInto(out, segments, value)
            }
        }
        return out
    }

    private fun unwrapSingleKey(
        declared: Set<String>,
        args: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        if (args.size != 1) return args
        val (key, value) = args.entries.first()
        if (key in declared) return args
        val inner = value as? JsonObject ?: return args
        if (inner.isEmpty()) return args
        return if (inner.keys.all { it in declared }) inner else args
    }

    private fun mergeInto(
        target: MutableMap<String, JsonElement>,
        segments: List<String>,
        value: JsonElement,
    ) {
        val head = segments.first()
        val node = buildNested(segments.drop(1), value)
        val existing = target[head]
        target[head] = if (existing is JsonObject && node is JsonObject) {
            deepMerge(existing, node)
        } else {
            node
        }
    }

    private fun buildNested(segments: List<String>, value: JsonElement): JsonElement {
        var node: JsonElement = value
        for (segment in segments.asReversed()) {
            node = JsonObject(mapOf(segment to node))
        }
        return node
    }

    private fun deepMerge(base: JsonObject, incoming: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in incoming) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) {
                deepMerge(existing, value)
            } else {
                value
            }
        }
        return JsonObject(merged)
    }
}
