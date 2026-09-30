package com.aicode.feature.agent.domain.tool

/**
 * 落盘工具结果的分页切片（纯逻辑，零 IO）。
 *
 * 移植自 OpenSquilla `tools/builtin/tool_results.py` + `engine/tool_result_query.py`（Apache-2.0）
 * 的「有界分页查询」设计：把落盘的超长输出按行切片喂回模型，而不是整段塞进上下文。
 *
 * 为什么按**行**而不是按字符切：模型读日志/代码时的自然单位是行，按行切才能让
 * 「start_line/end_line」语义与 `readFile` 一致，模型可以把两者当同一套心智模型使用。
 */
object ToolResultPager {

    /** 单页默认行数。 */
    const val DEFAULT_MAX_LINES: Int = 200

    /** 单页行数上限；再大就等于把落盘的意义抹掉了。 */
    const val HARD_MAX_LINES: Int = 2_000

    /** 单页字符上限（防止某页恰好全是超长行时仍撑爆上下文）。 */
    const val MAX_PAGE_CHARS: Int = 40_000

    /** 一页切片的结果。 */
    data class Page(
        /** 本页正文（已按 [MAX_PAGE_CHARS] 二次约束）。 */
        val text: String,
        /** 起始行号（从 1 计，含）。 */
        val startLine: Int,
        /** 结束行号（从 1 计，含）。 */
        val endLine: Int,
        /** 文件总行数。 */
        val totalLines: Int,
        /** 是否还有后续内容（存在比本页更靠后的行）。 */
        val hasMore: Boolean,
        /** 本页是否因字符上限被从中间截断（模型需知道该页不完整）。 */
        val truncatedByChars: Boolean,
    )

    /**
     * 切出 `[startLine, startLine + maxLines - 1]` 行窗口。
     *
     * @param text 全文。
     * @param startLine 起始行号（从 1 计）；越界时会被收敛到合法区间。
     * @param maxLines 最多返回多少行；`<= 0` 或未给时用 [DEFAULT_MAX_LINES]，超过 [HARD_MAX_LINES] 按上限截断。
     */
    fun page(
        text: String,
        startLine: Int = 1,
        maxLines: Int = DEFAULT_MAX_LINES,
    ): Page {
        val lines = text.split('\n')
        val totalLines = lines.size
        val effectiveMax = when {
            maxLines <= 0 -> DEFAULT_MAX_LINES
            maxLines > HARD_MAX_LINES -> HARD_MAX_LINES
            else -> maxLines
        }
        // startLine 收敛到 [1, totalLines]：越界不应报错，而是给出「最后一页」或「空页」，
        // 让模型能靠 has_more / total_lines 自行纠偏，而不是撞一个无信息的错误。
        val start = startLine.coerceIn(1, maxOf(1, totalLines))
        val endExclusive = minOf(start - 1 + effectiveMax, totalLines)

        val window = if (start - 1 < endExclusive) lines.subList(start - 1, endExclusive) else emptyList()
        val joined = window.joinToString("\n")

        val truncatedByChars = joined.length > MAX_PAGE_CHARS
        val body = if (truncatedByChars) joined.take(MAX_PAGE_CHARS) else joined

        return Page(
            text = body,
            startLine = start,
            endLine = if (window.isEmpty()) start - 1 else endExclusive,
            totalLines = totalLines,
            hasMore = endExclusive < totalLines,
            truncatedByChars = truncatedByChars,
        )
    }
}
