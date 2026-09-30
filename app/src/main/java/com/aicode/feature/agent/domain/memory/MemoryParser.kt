package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import org.yaml.snakeyaml.Yaml
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

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val pinned = frontmatter["pinned"]?.let { it == true || it.toString().equals("true", ignoreCase = true) } ?: false
        // triggers：YAML 列表或单个标量都接受；缺失/非法时为空（旧文件零影响）。
        // 拉丁项小写化，与 tokenizeForRecall 的拉丁分词口径一致，保证匹配时不会因大小写失配。
        val triggers = parseTriggers(frontmatter["triggers"])

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = body.trim(),
            pinned = pinned,
            triggers = triggers
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
        triggers: List<String> = emptyList()
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val pinnedLine = if (pinned) "\npinned: true" else ""
        // triggers 仅在非空时输出——默认参数下字节与旧实现完全一致，存量测试不受影响。
        val triggersLine = if (triggers.isEmpty()) "" else
            "\ntriggers: [" + triggers.joinToString(", ") { yamlScalar(it) } + "]"
        return "---\nname: $safeName\ndescription: $safeDesc$pinnedLine$triggersLine\n---\n$content"
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

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        // 空 frontmatter（`---\n---`）时 end == 3，substring(4, 3) 越界崩。与 SkillParser/AgentDefinitionParser 同源。
        if (end < 4) return emptyMap<String, Any>() to normalized.substring(end + 4).removePrefix("\n")

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}
