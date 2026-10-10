package com.aicode.feature.agent.domain.workflow

/**
 * 压缩触发阈值的判定，独立成纯逻辑便于单测（与 [CompactionTailSelector] 同一思路）。
 *
 * 抽出动机：这段策略原本内联在 `ContextCompactor` 的压缩流程里，**零测试覆盖**，
 * 结果一个反向调整的下限长期没被发现（见 [effectivePercent] 注释）。
 */
internal object CompactionThreshold {

    /** 自适应下调的步长（百分点）。 */
    const val ADAPTIVE_STEP = 15

    /**
     * 下调后的绝对下限（百分点）。
     *
     * 该常量只对**高于它的用户设置**有意义；对更低的设置，下限取用户值本身（见 [effectivePercent]）。
     */
    const val ADAPTIVE_FLOOR = 60

    /** 判定「增长过快」的相对幅度：距上次成功压缩的增长超过窗口的这个比例。 */
    private const val GROWTH_RATIO = 0.15

    /**
     * 距上次成功压缩的增长是否已超过窗口 [GROWTH_RATIO]。
     *
     * 两端口径一致：入参 `estimatedTokens` 与账本里记的 `lastCompactedSize` 同为本地估算
     * （`estimateTokens(messages)`），不混用真实 usage，避免两者偏差叠加。
     *
     * @param lastCompactedSize 上次压缩后的上下文估算；0 表示本进程内尚未成功压缩过
     * @param estimatedTokens 当前上下文的本地估算
     * @param contextLimit 窗口大小
     */
    fun isFastGrowth(lastCompactedSize: Int, estimatedTokens: Int, contextLimit: Int): Boolean =
        lastCompactedSize > 0 &&
            contextLimit > 0 &&
            estimatedTokens - lastCompactedSize > contextLimit * GROWTH_RATIO

    /**
     * 本次压缩的生效阈值百分比。
     *
     * 用户设置为基准；增长过快的会话额外下调 [ADAPTIVE_STEP] 个百分点以提前压缩，
     * 避免固定阈值追不上增长（实测压缩点一路扬升 136k→199k→224k）。
     *
     * **下调只降不升**：下限取 `min(用户设置, ADAPTIVE_FLOOR)`。
     * 旧实现直接 `coerceAtLeast(ADAPTIVE_FLOOR)`，对低于 75 的设置反而把阈值**抬高**——
     * 用户设 15%（1M 窗口，期望 150k 触发）时，首次压缩后命中下调分支，
     * `15 - 15 = 0` 被抬到 60%，阈值跳到 600k，会话此后再也压不动。
     */
    fun effectivePercent(basePercent: Int, fastGrowth: Boolean): Int {
        if (!fastGrowth) return basePercent
        val floor = minOf(basePercent, ADAPTIVE_FLOOR)
        return maxOf(basePercent - ADAPTIVE_STEP, floor)
    }

    /** 生效阈值对应的 token 数（达到即触发压缩）。 */
    fun triggerTokens(contextLimit: Int, basePercent: Int, fastGrowth: Boolean): Int =
        (contextLimit * effectivePercent(basePercent, fastGrowth) / 100.0).toInt()
}
