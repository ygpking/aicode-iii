package com.aicode.feature.agent.domain.tool

/**
 * 超长文本的「显著行」提取：在只保留头尾的预览里，把被截掉的中间段中最可能有诊断价值的行捞回来。
 *
 * 背景：工具输出落盘回灌时默认只留头尾（见 [ToolOutputStore.buildPreview]），中间段整段丢弃。
 * 但编译/测试/抓取类输出里，头是启动噪声、尾是汇总，真正定位问题的报错常常夹在中间。本对象
 * 按关键词挑出这些行拼成一段，插在截断说明之前，让模型不必回读落盘文件就能看到关键线索。
 *
 * 纯字符串处理、无 IO，便于单测；输出有硬上限，避免「显著行太多」把预览又撑大。
 */
internal object SalientLines {

    /** 命中即视为显著：报错、失败、退出码、栈帧、文件:行号引用等。 */
    private val SALIENT = Regex(
        "(?i)\\b(error|fatal|panic|exception|failed|failure|denied|refused|timeout|timed out|" +
            "warning|traceback|assert|expected|exit code|nonzero|cannot|unable)\\b" +
            "|\\bFile \"|-->|\\S+\\.\\w+[:(]\\d+"
    )

    private const val DEFAULT_MAX_LINES = 20
    private const val DEFAULT_MAX_CHARS_PER_LINE = 300

    /**
     * 从 [text] 中提取显著行。
     *
     * @param maxLines 最多返回的行数，按出现顺序取前 N 条。
     * @return 命中的行拼接文本；无命中返回 null。
     */
    fun extract(
        text: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxCharsPerLine: Int = DEFAULT_MAX_CHARS_PER_LINE
    ): String? {
        if (text.isEmpty() || maxLines <= 0) return null
        val picked = ArrayList<String>(maxLines)
        for (line in text.lineSequence()) {
            if (picked.size >= maxLines) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!SALIENT.containsMatchIn(trimmed)) continue
            picked += if (trimmed.length > maxCharsPerLine) trimmed.take(maxCharsPerLine) + " …" else trimmed
        }
        return if (picked.isEmpty()) null else picked.joinToString("\n")
    }
}
