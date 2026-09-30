package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

object SkillParser {
    private const val TAG = "SkillParser"
    private const val MAX_DESC_CHARS = 500
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 解析一个 skill 目录；无 SKILL.md / CLAUDE.md 或无 name 时视为非法，返回 null。
     * 目录经 [provider] 以容器路径读取，本地/远程统一。
     */
    fun parse(provider: FileAccessProvider, dirPath: String): Skill? {
        val dir = dirPath.trimEnd('/')
        // 优先 SKILL.md，其次 CLAUDE.md（兼容只用 CLAUDE.md 的技能）；名称匹配忽略大小写
        val fileName = runCatching {
            provider.listFiles(dir).map { it.name }
        }.getOrElse {
            FileLogger.w(TAG, "列出技能目录失败: $dir", it)
            return null
        }.let { names ->
            names.firstOrNull { it.equals(SKILL_FILE, ignoreCase = true) }
                ?: names.firstOrNull { it.equals(CLAUDE_FILE, ignoreCase = true) }
        } ?: return null

        val text = runCatching { provider.readFile("$dir/$fileName") }.getOrElse {
            FileLogger.w(TAG, "读取 Skill 文件失败: $dir/$fileName", it)
            return null
        }

        return parseText(
            text,
            fallbackName = dir.substringAfterLast('/').ifBlank { dir },
            source = "$dir/$fileName"
        ).copy(dirPath = dir)
    }

    /**
     * 从原始 Markdown 文本解析技能（不依赖磁盘），供文件/压缩包导入使用。
     * [fallbackName] 为 frontmatter 缺 name 时的兜底（通常传源文件名或所在目录名）。
     */
    fun parseText(text: String, fallbackName: String, source: String? = null): Skill {
        val (frontmatter, body) = splitAndParseFrontmatter(text, source)

        // name 优先取 frontmatter，缺省回退到兜底名；统一 trim，避免尾随空白与保存侧（form.name.trim()）产生两个名字。
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter["required_tools"]
            if (toolsRaw is List<*>) toolsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 required_tools 失败", e)
            emptyList()
        }

        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dirPath = null,
            instructions = body.trim()
        )
    }

    /**
     * 利用 SnakeYAML 切分并解析 YAML frontmatter。
     * @return (frontmatter 键值对, 正文)
     */
    private fun splitAndParseFrontmatter(text: String, source: String?): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        // 空 frontmatter（`---\n---`）时闭合符紧跟起始符，end == 3：此时 substring(4, end)
        // 会抛 StringIndexOutOfBoundsException，把整个技能列表连同扫描一起打挂。
        // 视为「无元数据」，正文取闭合符之后的部分，不解析、不告警（这是合法写法）。
        if (end < 4) return emptyMap<String, Any>() to normalized.substring(end + 4).removePrefix("\n")

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        return parseYamlBlock(block, source) to rest
    }

    /** 裸标量行 `key: 值`（跳过缩进行、列表项与注释）。 */
    private val PLAIN_SCALAR = Regex("^([A-Za-z_][A-Za-z0-9_-]*):[ \\t]+(\\S.*)$")

    private fun at(source: String?): String = source?.let { "（$it）" } ?: ""

    /**
     * 解析 frontmatter 块，失败时先尝试修复、再报错。
     *
     * 最常见的坏法（技能库里实测就有一个）：`description` 是**未加引号的裸标量**，值里又
     * 写了 `: `（如 `触发：cgo 报 "jni.h: No such file or directory"`）。YAML 会把 `: `
     * 当映射分隔符，整块解析失败——而该技能**仍会留在技能列表里**，只是 description 变空；
     * description 是模型判断「要不要启用这个技能」的唯一依据，于是它**永久失效且毫无提示**。
     *
     * 故先给这类裸标量补引号重试；仍失败才报错，且**带上来源**（原先只打异常堆栈，
     * 同一句告警一天刷十几次却查不出是哪个技能）。
     */
    private fun parseYamlBlock(block: String, source: String?): Map<String, Any> {
        runCatching { Yaml().load<Map<String, Any>>(block) }.getOrNull()?.let { return it }

        val repaired = quotePlainScalarsWithColon(block)
        if (repaired != block) {
            runCatching { Yaml().load<Map<String, Any>>(repaired) }.getOrNull()?.let {
                FileLogger.w(TAG, "frontmatter 含未加引号的冒号，已自动补引号修复${at(source)}")
                return it
            }
        }

        FileLogger.w(TAG, "解析 frontmatter 失败${at(source)}——该技能 name/description 回退兜底值，可能不会被模型启用")
        return emptyMap()
    }

    /**
     * 给「未加引号且含 `: `」的顶层标量值补双引号。
     *
     * 只针对上述坏 frontmatter，不追求覆盖全部 YAML 语法——调用点本就在首次解析已失败之后，
     * 修不动也只是维持原状，不会更坏。
     */
    private fun quotePlainScalarsWithColon(block: String): String =
        block.lines().joinToString("\n") { line ->
            val m = PLAIN_SCALAR.matchEntire(line) ?: return@joinToString line
            val value = m.groupValues[2]
            // 已是引号/块标量/流式集合/锚点别名/注释，交给 YAML 自己处理
            if (value.first() in charArrayOf('"', '\'', '|', '>', '[', '{', '&', '*', '#')) return@joinToString line
            if (!value.contains(": ")) return@joinToString line
            val quoted = value.replace("\\", "\\\\").replace("\"", "\\\"").trim()
            "${m.groupValues[1]}: \"$quoted\""
        }

    /**
     * 把设置页表单写回 `SKILL.md` 文本（frontmatter + 正文），与 [parse] 成对。
     *
     * `name` 总是写出来：技能名优先取 frontmatter，改名只需改这里而不必动目录名（技能目录
     * 里的脚本常被正文按原路径引用，跟着改名会把引用打断）。
     * 工具名是标识符，直接进方括号列表；其余文本字段一律加引号，免得描述里的冒号或 # 把 YAML 弄坏。
     */
    fun serialize(
        name: String,
        description: String,
        requiredTools: List<String>,
        instructions: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        if (requiredTools.isNotEmpty()) appendLine("required_tools: [${requiredTools.joinToString(", ")}]")
        appendLine("---")
        appendLine(instructions.trim())
    }

    /** frontmatter 字符串值：双引号包裹，转义反斜杠与引号，换行压成空格保证单行。 */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
