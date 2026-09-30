package com.aicode.core.text

import org.yaml.snakeyaml.Yaml

/**
 * YAML frontmatter 的**唯一**切分与解析入口。
 *
 * 为什么存在（根因 R1「复制式传播」）：切分 `---\n<yaml>\n---\n<body>` 的逻辑曾在
 * `SkillParser` / `AgentDefinitionParser` / `MemoryParser` 里**复制成三份**，且三份都只挡
 * `end < 0`。空 frontmatter（`---\n---`）时 `end == 3`，`substring(4, 3)` 越界抛
 * `StringIndexOutOfBoundsException`——上一轮只修了 `SkillParser`，同一崩溃随即在另两份里
 * **再次发生**（commit `9661b83` 原话：「这是 SkillParser 同源缺陷的独立副本」）。
 *
 * 收敛到本类后，`end < 4` 这条判据与补引号修复全仓只有一份，改 frontmatter 语义不会再漏改副本。
 */
object FrontmatterCodec {

    private const val OPEN = "---\n"
    private const val CLOSE = "\n---"

    /** 切分结果：`block` 为 null 表示「无元数据」（无 frontmatter 或空 frontmatter）。 */
    data class Split(val block: String?, val body: String)

    /**
     * 切分 Markdown 的 YAML frontmatter，兼容三种输入：
     * - **无 frontmatter**（不以 `---\n` 开头，或无闭合符）→ `block = null`，正文为全文；
     * - **空 frontmatter**（`---\n---`）→ `block = null`（这是合法写法，视为无元数据），正文为闭合符之后；
     * - **正常 frontmatter** → `block` 为两 `---` 之间的 YAML 文本，正文为闭合符之后。
     *
     * 空 frontmatter 必须显式挡下：此时闭合符紧跟起始符（`end == 3`），
     * `substring(4, end)` 会越界崩溃，把整个技能/子代理/记忆列表连同扫描一起打挂。
     */
    fun split(text: String): Split {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith(OPEN)) return Split(null, normalized)

        val end = normalized.indexOf(CLOSE, startIndex = 3)
        if (end < 0) return Split(null, normalized)
        if (end < 4) return Split(null, normalized.substring(end + 4).removePrefix("\n"))

        return Split(
            block = normalized.substring(4, end),
            body = normalized.substring(end + 4).removePrefix("\n")
        )
    }

    /**
     * 解析 frontmatter 文本块。
     *
     * @param repair 失败时是否尝试修复「未加引号的裸标量」后重试（见 [quotePlainScalarsWithColon]）。
     *   历史实现里只有 `SkillParser` 有这个能力，另两份没有；此处改为显式开关，避免抽取时
     *   把 `SkillParser` 的能力降级掉。
     * @param onWarn 告警回调（文案由调用方决定，保留各自的日志标签与上下文）。
     */
    fun parse(block: String, repair: Boolean = false, onWarn: (String, Throwable?) -> Unit = { _, _ -> }): Map<String, Any> {
        val first = runCatching { Yaml().load<Map<String, Any>>(block) }
        first.getOrNull()?.let { return it }

        if (repair) {
            val repaired = quotePlainScalarsWithColon(block)
            if (repaired != block) {
                runCatching { Yaml().load<Map<String, Any>>(repaired) }.getOrNull()?.let {
                    onWarn(REPAIRED, null)
                    return it
                }
            }
        }
        onWarn(FAILED, first.exceptionOrNull())
        return emptyMap()
    }

    /** 解析失败（且未能修复）的告警文案。 */
    const val FAILED: String = "解析 frontmatter 失败"

    /** 补引号修复成功的告警文案。 */
    const val REPAIRED: String = "frontmatter 含未加引号的冒号，已自动补引号修复"

    /** 裸标量行 `key: 值`（跳过缩进行、列表项与注释）。 */
    private val PLAIN_SCALAR = Regex("^([A-Za-z_][A-Za-z0-9_-]*):[ \\t]+(\\S.*)$")

    /**
     * 给「未加引号且含 `: `」的顶层标量值补双引号。
     *
     * 最常见的坏法（技能库实测就有一个）：`description` 是**未加引号的裸标量**，值里又写了
     * `: `（如 `触发：cgo 报 "jni.h: No such file or directory"`）。YAML 会把 `: ` 当映射
     * 分隔符，整块解析失败——而该条目**仍会留在列表里**，只是 description 变空；description
     * 是模型判断「要不要启用」的唯一依据，于是它**永久失效且毫无提示**。
     *
     * 只针对上述坏 frontmatter，不追求覆盖全部 YAML 语法——调用点本就在首次解析已失败之后，
     * 修不动也只是维持原状，不会更坏。
     */
    fun quotePlainScalarsWithColon(block: String): String =
        block.lines().joinToString("\n") { line ->
            val m = PLAIN_SCALAR.matchEntire(line) ?: return@joinToString line
            val value = m.groupValues[2]
            // 已是引号/块标量/流式集合/锚点别名/注释，交给 YAML 自己处理
            if (value.first() in charArrayOf('"', '\'', '|', '>', '[', '{', '&', '*', '#')) return@joinToString line
            if (!value.contains(": ")) return@joinToString line
            val quoted = value.replace("\\", "\\\\").replace("\"", "\\\"").trim()
            "${m.groupValues[1]}: \"$quoted\""
        }
}
