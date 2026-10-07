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
        // 使用信号：召回命中回写（touchMemory），缺失（存量）为 0。0 与正文无关，仅证明「被用过」。
        val lastUsedMs = (frontmatter["last_used"] as? Number)?.toLong()?.takeIf { it > 0L } ?: 0L
        val recallCount = (frontmatter["recall_count"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 0
        // 结晶层级：缺省 policy（存量文件零影响）；非法值回退 policy。
        val kind = frontmatter["kind"]?.toString()?.trim()?.takeIf { it in setOf(MemoryKind.TRACE, MemoryKind.POLICY, MemoryKind.SKILL) }
            ?: MemoryKind.POLICY
        val crystallizedTo = frontmatter["crystallized_to"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

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
            lastUsedMs = lastUsedMs,
            recallCount = recallCount,
            keyPoints = extractKeyPoints(body),
            kind = kind,
            crystallizedTo = crystallizedTo,
        )
    }

    /**
     * 提取正文 `## 要点` 段下的条目（L1 层）。约定：段落内每个非空行（去掉列表符号后）
     * 是一条要点。无该段或段内无内容返回空表——注入端回退到正文首段截断的旧行为。
     */
    private fun extractKeyPoints(body: String): List<String> {
        var inSection = false
        val points = ArrayList<String>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("## ")) {
                inSection = line.substring(3).trim() == "要点"
                continue
            }
            if (!inSection || line.startsWith("# ")) {
                // 离开要点段（下一个一级标题）
                if (line.startsWith("# ")) inSection = false
                continue
            }
            if (line.isEmpty()) continue
            points += line.removePrefix("- ").removePrefix("* ").trim()
        }
        return points.filter { it.isNotEmpty() }
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
        lastUsedMs: Long = 0L,
        recallCount: Int = 0,
        kind: String = MemoryKind.POLICY,
        crystallizedTo: String? = null,
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val pinnedLine = if (pinned) "\npinned: true" else ""
        // triggers 仅在非空时输出——默认参数下字节与旧实现完全一致，存量测试不受影响。
        val triggersLine = if (triggers.isEmpty()) "" else
            "\ntriggers: [" + triggers.joinToString(", ") { yamlScalar(it) } + "]"
        // updated 同理仅在已知时输出：存量文件没有该字段，字节不变。
        val updatedLine = if (updatedAtMs > 0L) "\nupdated: $updatedAtMs" else ""
        val lastUsedLine = if (lastUsedMs > 0L) "\nlast_used: $lastUsedMs" else ""
        val recallCountLine = if (recallCount > 0) "\nrecall_count: $recallCount" else ""
        val kindLine = if (kind != MemoryKind.POLICY) "\nkind: $kind" else ""
        val crystallizedLine = if (crystallizedTo.isNullOrBlank()) "" else "\ncrystallized_to: ${yamlScalar(crystallizedTo)}"
        return "---\nname: $safeName\ndescription: $safeDesc$pinnedLine$triggersLine$updatedLine$lastUsedLine$recallCountLine$kindLine$crystallizedLine\n---\n$content"
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
