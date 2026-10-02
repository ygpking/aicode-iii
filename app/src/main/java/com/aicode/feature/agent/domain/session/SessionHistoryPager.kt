package com.aicode.feature.agent.domain.session

/**
 * 翻阅历史的取页与字符预算守卫（纯逻辑，独立出来便于单测）。
 *
 * 与 [com.aicode.feature.agent.domain.tool.ToolResultPager] 同一思路：把「按行/按条装包、
 * 超预算即止」的判定从工具里剥离，让边界条件（空结果、单条超限、恰好用尽预算）可被直接验证。
 */
object SessionHistoryPager {

    /** 单条消息正文的截断上限：工具结果动辄上万字符，原样返回会把上下文塞满。 */
    const val MAX_CHARS_PER_MESSAGE = 1_200

    /** 单次返回的总字符上限。 */
    const val MAX_TOTAL_CHARS = 24_000

    /** 单次返回条数上限。 */
    const val MAX_LIMIT = 50

    const val DEFAULT_LIMIT = 20

    /** 一页的装包结果。 */
    data class Page(
        val items: List<SessionHistoryItem>,
        /** 是否有条目因总字符预算被挡在页外。 */
        val truncatedByChars: Boolean,
        /** 正文是否被按条截断过（有任一超长条目）。 */
        val truncatedMessages: Boolean
    )

    /**
     * 把 [rows]（应已按时间倒序）装进一页。
     *
     * 规则：逐条计入 `min(正文长度, [MAX_CHARS_PER_MESSAGE])`；**首条无条件放入**（即使它自己
     * 就超预算——否则一条超长消息会让页永远为空，调用方无法前进），之后遇到超预算即停止并置
     * [Page.truncatedByChars]。
     */
    fun pack(
        rows: List<SessionHistoryItem>,
        maxCharsPerMessage: Int = MAX_CHARS_PER_MESSAGE,
        maxTotalChars: Int = MAX_TOTAL_CHARS
    ): Page {
        val packed = mutableListOf<SessionHistoryItem>()
        var used = 0
        var hit = false
        var anyTruncated = false

        for (row in rows) {
            val cost = row.content.length.coerceAtMost(maxCharsPerMessage)
            if (packed.isNotEmpty() && used + cost > maxTotalChars) {
                hit = true
                break
            }
            if (row.content.length > maxCharsPerMessage) anyTruncated = true
            packed += row
            used += cost
        }
        return Page(items = packed, truncatedByChars = hit, truncatedMessages = anyTruncated)
    }

    /** 单条正文的超限截断（带说明尾巴，让模型知道原文更长）。 */
    fun truncate(content: String, max: Int = MAX_CHARS_PER_MESSAGE): String =
        if (content.length <= max) content else content.take(max) + "…（本条已截断，全文更长）"

    fun clampLimit(raw: Int?): Int = (raw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** 转义 LIKE 通配符，与 UI 搜索同一套约定（`!` 为 ESCAPE 字符）。 */
    fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")
}
