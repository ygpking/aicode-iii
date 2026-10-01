package com.aicode.feature.agent.domain.memory

import com.aicode.core.text.FrontmatterCodec
import com.aicode.core.util.FileLogger
import java.io.File

object MemoryParser {
    private const val TAG = "MemoryParser"
    private const val MAX_DESC_CHARS = 500

    fun parse(file: File, scope: MemoryScope): Memory? {
        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取 Memory 文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body, malformed) = splitAndParseFrontmatter(text)
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val pinned = frontmatter["pinned"]?.let { it == true || it.toString().equals("true", ignoreCase = true) } ?: false
        // triggers：YAML 列表或单个标量都接受；缺失/非法时为空（旧文件零影响）。
        // 拉丁项小写化，与 tokenizeForRecall 的拉丁分词口径一致，保证匹配时不会因大小写失配。
        val triggers = parseTriggers(frontmatter["triggers"])
        // 内容派生的更新时间；缺失（存量文件）为 0，由 [Memory.effectiveUpdatedAtMs] 回退到 mtime。
        val updatedAtMs = (frontmatter["updated"] as? Number)?.toLong()?.takeIf { it > 0L } ?: 0L

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = body.trim(),
            pinned = pinned,
            triggers = triggers,
            malformed = malformed,
            updatedAtMs = updatedAtMs,
        )
    }

    /**
     * 解析 frontmatter 的 `triggers`：接受 YAML 列表（SnakeYAML 给 `List<*>`）或单个标量。
     * 去空白/去重/丢弃空白项；拉丁项统一小写。任何非预期类型都退回空表，不抛异常——
     * 一条坏记忆不应连带整表失败（与扫描器逐项兜底同一原则）。
     */
    private fun parseTriggers(raw: Any?): List<String> {
        val items = when (raw) {
            null -> return emptyList()
            is List<*> -> raw
            else -> listOf(raw)
        }
        return items
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.any { c -> c.code < 128 }) it.lowercase() else it }
            .distinct()
    }

    fun format(
        name: String,
        description: String,
        content: String,
        pinned: Boolean = false,
        triggers: List<String> = emptyList(),
        updatedAtMs: Long = 0L,
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val pinnedLine = if (pinned) "\npinned: true" else ""
        // triggers 仅在非空时输出——默认参数下字节与旧实现完全一致，存量测试不受影响。
        val triggersLine = if (triggers.isEmpty()) "" else
            "\ntriggers: [" + triggers.joinToString(", ") { yamlScalar(it) } + "]"
        // updated 同理仅在已知时输出：存量文件没有该字段，字节不变。
        val updatedLine = if (updatedAtMs > 0L) "\nupdated: $updatedAtMs" else ""
        return "---\nname: $safeName\ndescription: $safeDesc$pinnedLine$triggersLine$updatedLine\n---\n$content"
    }

    /** 把任意字符串转成安全的 YAML 标量，避免冒号/引号/换行破坏 frontmatter。 */
    private fun yamlScalar(value: String): String {
        val needsQuote = value.contains(':') || value.contains('#') ||
            value.contains('"') || value.contains('\'') ||
            value.startsWith('-') || value.startsWith(' ') || value.endsWith(' ') ||
            value.contains('\n') || value.isBlank()
        return if (needsQuote) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
    }

    /**
     * 切分并解析 frontmatter。切分/空 frontmatter 边界由 [FrontmatterCodec] 唯一实现，
     * 与 SkillParser/AgentDefinitionParser 不再各持一份副本。
     * 不启用 `repair`（本类原先无裸标量补引号能力，保持行为不变）。
     *
     * @return 三元组 `(元数据, 正文, 是否畸形)`；畸形指起始符缺闭合符（[FrontmatterCodec.Status.UNCLOSED]），
     *   已尽力恢复元数据，但仍标记出来让 [MemorySource.editMemory] 拒绝回写（避免把畸形固化）。
     */
    private fun splitAndParseFrontmatter(text: String): Triple<Map<String, Any>, String, Boolean> {
        val (block, body, status) = FrontmatterCodec.split(text) { kind ->
            FileLogger.w(TAG, "$kind（记忆）" + if (kind == FrontmatterCodec.UNCLOSED) "——description/triggers 可能失效，建议重写该记忆文件" else "")
        }
        if (block == null) {
            return Triple(emptyMap(), body, status == FrontmatterCodec.Status.UNCLOSED)
        }
        val meta = FrontmatterCodec.parse(block, repair = false) { kind, e ->
            FileLogger.w(TAG, "$kind（记忆）", e)
        }
        return Triple(meta, body, status == FrontmatterCodec.Status.UNCLOSED)
    }
}
