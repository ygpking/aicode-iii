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

    /** 切分结果里 frontmatter 的形态，供调用方决定是否告警（见 [split] 的 `onWarn`）。 */
    enum class Status {
        /** 不以 `---\n` 开头：本就没有 frontmatter。 */
        NONE,

        /** 正常：有起始与闭合符，且块非空。 */
        OK,

        /** 空 frontmatter（`---\n---` 等）：合法写法，视为无元数据，**不是**畸形。 */
        EMPTY,

        /** 有起始符但**无闭合符**：畸形（见 [split] 的尽力恢复逻辑）。 */
        UNCLOSED,
    }

    /**
     * 切分结果。
     *
     * @param block 两 `---` 之间的 YAML 文本；null 表示「无元数据」。
     * @param body 正文（frontmatter 之后的内容；无/畸形且无法恢复时为全文）。
     * @param status 形态，便于调用方区分「本就没有」与「畸形」。[Status.EMPTY] 与 [Status.NONE]
     *   都不该告警，只有 [Status.UNCLOSED] 需要。
     */
    data class Split(val block: String?, val body: String, val status: Status)

    /** 未闭合畸形的告警文案（`onWarn` 回调）。 */
    const val UNCLOSED: String = "frontmatter 未闭合（缺结束符 ---）"

    /** 正文里又出现 frontmatter 起始符——多半是「重复追加了整块」的畸形（`onWarn` 回调）。 */
    const val NESTED: String = "正文以 --- 开头，疑似重复追加了 frontmatter 块"

    /** frontmatter 里合法的元数据行：`key:` 或 `key: 值`（不含空格分隔的 `key:值`，YAML 视其为标量）。 */
    private val META_LINE = Regex("^[A-Za-z_][A-Za-z0-9_-]*:(?:[ \t].*)?$")

    /**
     * 切分 Markdown 的 YAML frontmatter，兼容四种输入：
     * - **无 frontmatter**（不以 `---\n` 开头）→ `block = null`、正文为全文、[Status.NONE]；
     * - **空 frontmatter**（`---\n---`、`---\n\n---`、仅含空白的块）→ `block = null`
     *   （这是合法写法，视为无元数据）、正文为闭合符之后、[Status.EMPTY]；
     * - **正常 frontmatter** → `block` 为两 `---` 之间的 YAML 文本、正文为闭合符之后、[Status.OK]；
     * - **未闭合**（有起始符、无闭合符）→ **尽力恢复**：把起始符之后连续的 `key: 值` 行当作元数据，
     *   其余算正文，[Status.UNCLOSED] 并通过 `onWarn` 告警。
     *
     * 空 frontmatter 必须显式挡下：此时闭合符紧跟起始符（`end == 3`），
     * `substring(4, end)` 会越界崩溃，把整个技能/子代理/记忆列表连同扫描一起打挂。
     * 含空白行的空块（`end == 4`）虽不崩溃，但会得到一个空的 `block`，让调用方多走一套
     * 「有元数据」流程（多一次解析、误报「name 回退兜底」告警），故一并归一为 `null`。
     *
     * **为什么未闭合要恢复而不是直接当「无元数据」**：2026-09-30 一次外部批量重写记忆文件时
     * 漏写闭合符，17/20 条记忆的 `description`/`triggers` 因此被静默丢弃——元数据丢失在当时
     * **没有任何日志**，是排查中才发现「注入到提示词里的记忆清单只剩名字」。恢复 + 告警使这类
     * 畸形既不静默、也不至于让已写好的元数据白白失效；文档头部的 `---` 也依赖它才不会被当成正文
     * 渲染进召回块（此前 `read` 会把 `---\nname: ...` 一起显示给模型）。
     *
     * 恢复范围刻意很窄（只认行首 `key:` 形态），不做 YAML 推断：无法确认形态时退回「无元数据」，
     * 保持与其他分支同样的「不猜」原则。
     *
     * @param onWarn 畸形提示回调（[UNCLOSED] / [NESTED]）；文案由常量给出，日志标签由调用方定。
     */
    fun split(text: String, onWarn: (String) -> Unit = {}): Split {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith(OPEN)) return Split(null, normalized, Status.NONE)

        val end = normalized.indexOf(CLOSE, startIndex = 3)
        if (end < 0) {
            if (!recoverableAsMeta(normalized)) {
                onWarn(UNCLOSED)
                return Split(null, normalized, Status.UNCLOSED)
            }
            onWarn(UNCLOSED)
            return recoverUnclosed(normalized)
        }
        if (end < 4) return Split(null, normalized.substring(end + 4).removePrefix("\n"), Status.EMPTY)

        val block = normalized.substring(4, end)
        val body = normalized.substring(end + 4).removePrefix("\n")
        // 纯空白块视为「无元数据」：`---\n\n---` 与 `---\n---` 语义相同。
        if (block.isBlank()) return Split(null, body, Status.EMPTY)
        // 块正常，但正文又出现起始符 → 多半是整块被追加了两次（后者通常才是真元数据）。
        // 只告警不改行为：这种文件的正确修法是重写，而任取其一会静默丢另一半信息。
        if (body.startsWith(OPEN)) onWarn(NESTED)
        return Split(block, body, Status.OK)
    }

    /** 起始符之后是否紧跟至少一行 `key: 值`（决定未闭合时能否恢复）。 */
    private fun recoverableAsMeta(normalized: String): Boolean =
        normalized.substring(OPEN.length).split("\n").firstOrNull()?.let { META_LINE.matches(it) } == true

    /** 未闭合恢复：起始符之后连续的 `key: 值` 行为元数据，其余为正文。 */
    private fun recoverUnclosed(normalized: String): Split {
        val lines = normalized.substring(OPEN.length).split("\n")
        var i = 0
        while (i < lines.size && META_LINE.matches(lines[i])) i++
        return Split(
            block = lines.subList(0, i).joinToString("\n"),
            body = lines.subList(i, lines.size).joinToString("\n"),
            status = Status.UNCLOSED,
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
        // 空块与「只有注释」的块，snakeyaml 会正常返回 null（不抛异常）——这是合法输入，
        // 不是解析失败，故与历史实现的 `?: emptyMap()` 保持一致：不打告警。
        // 只有**抛异常**才真正意味着块内容有问题，此时才告警。
        first.getOrNull()?.let { return it }
        if (first.isSuccess) return emptyMap()

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
}
