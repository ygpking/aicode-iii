package com.aicode.feature.agent.domain.workflow

/**
 * 压缩摘要输入按压缩模型窗口预算取尾部，并**量化被丢弃的部分**。
 *
 * 独立成纯逻辑便于单测（与 [CompactionTailSelector]、[CompactionHeadFolder] 等同一思路）。
 *
 * 为什么要把丢弃量显式传出去：主模型窗口常远大于压缩模型窗口（实测 1M vs 128k，8 倍差），
 * 预算内装不下的最旧历史会被丢掉且**不进摘要**。旧实现只打一行 INFO，
 * 用户与接手方都无从得知「更早的历史没进摘要」——会把「摘要里没有」当成「从未发生」。
 */
internal object CompactionSummaryWindow {

    /**
     * 从新到旧取消息，累计 token 超过 [budgetTokens] 时停止。
     *
     * 至少保留一条（最后一条即最新消息），否则会产出空材料。
     */
    fun <T> selectTail(messages: List<T>, budgetTokens: Int, estimate: (T) -> Int): Result<T> {
        if (messages.isEmpty()) return Result(emptyList())
        var total = 0
        val kept = mutableListOf<T>()
        for (msg in messages.asReversed()) {
            val tokens = estimate(msg)
            if (kept.isNotEmpty() && total + tokens > budgetTokens) break
            total += tokens
            kept.add(msg)
        }
        val ordered = kept.asReversed()
        val droppedCount = messages.size - ordered.size
        if (droppedCount == 0) return Result(ordered)
        val allTokens = messages.sumOf { estimate(it) }
        return Result(
            kept = ordered,
            droppedCount = droppedCount,
            droppedTokens = allTokens - total,
        )
    }

    /**
     * 取用结果：保留的消息 + 被丢弃的量。
     *
     * @param droppedTokens 为估算值（与 [estimate] 同口径），用于告警与摘要产物标注
     */
    data class Result<T>(
        val kept: List<T>,
        val droppedCount: Int = 0,
        val droppedTokens: Int = 0,
    ) {
        val hasDrop: Boolean get() = droppedCount > 0

        /**
         * 追加到摘要产物的说明；无丢弃时为空串。
         *
         * B5 修复：被丢弃的最旧段**并未丢失**——它随 head 一起被标 `isCompacted` 且写入
         * 同一 `compactionBlockId`（`ContextCompactor` 用**未裁剪的 head** 做标记，见其
         * `CompactionMarkSelector.selectIdsToMark(head, …)`），仍在 DB 中，可经
         * `restoreCompactedRange` 取回。故提示改为指向该工具，而不是含糊的「查原始会话记录」
         * ——否则接手者会把「摘要里没有」当成「已丢失」，白白重做或臆测。
         */
        fun notice(): String =
            if (!hasDrop) {
                ""
            } else {
                "\n> 注：更早的 $droppedCount 条消息（约 $droppedTokens token）超出压缩模型窗口，" +
                    "未逐条纳入本摘要**但并未丢失**：它们仍在本次压缩块内。如需该段内容，" +
                    "用 restoreCompactedRange 的 preview 模式检索、action=restore 恢复原文；不要臆测其内容。"
            }
    }
}
