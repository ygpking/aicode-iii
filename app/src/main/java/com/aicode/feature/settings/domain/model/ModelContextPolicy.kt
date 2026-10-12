package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000

    /**
     * tail 保护区的**基础**上限（128k 窗口时代的取值，占窗口 15.6%）。
     *
     * 保留该常量只为说明历史取值与给下方比例设下限，**不再作为绝对上限**。
     * 见 [preserveRecentTokens]。
     */
    const val MAX_PRESERVE_RECENT_TOKENS = 20_000

    /** tail 占窗口的目标比例（各窗口统一，不再对小窗口例外）。 */
    private const val PRESERVE_WINDOW_RATIO = 0.1

    /**
     * 摘要窗口的预留比例：30% 留给摘要提示词与旧摘要，剩 70% 装被折叠的 head。
     *
     * 与 `ContextCompactor.SUMMARY_WINDOW_RESERVE_RATIO` 必须一致——tail 预算的对账
     * 依赖它：只有保证被折叠的 head 装得进摘要窗口，压缩才不会**静默丢掉最旧历史**。
     */
    private const val SUMMARY_WINDOW_RESERVE_RATIO = 0.7

    const val CHARS_PER_TOKEN = 4

    /**
     * tail 保护区预算：按可用空间的四分之一，上限按窗口比例（10%）取宽。
     *
     * 上限 = `max(基础下限, 窗口 * 10%)` 的**比例侧**起主导——即 `窗口 * 10%`，
     * 但不得低于 [MAX_PRESERVE_RECENT_TOKENS]（小窗口下仍需 20k 的绝对保留量，
     * 否则 2k 级别的 tail 会让最近几轮对话被折走）。
     *
     * ## 为什么不能用旧的 `max(20000, 窗口*10%)` 当上限
     *
     * 旧式 `maxOf` 让 128k 成为**唯一**的相对最坏点：窗口 ≤200k 时上限恒为 20000，
     * 占窗口 15.6%（128k）而大窗口只占 10%。tail 越大、被折叠的 head 越大，而 head
     * 必须装进「压缩模型窗口 × 70%」的摘要输入预算——128k 下 head ≈ 95k > 预算 89.6k，
     * 于是**每轮压缩都静默丢掉最旧的一段**（CompactionSummaryWindow 只 WARN 不报错）。
     * 这是「128k 一直压缩失败/历史丢失」的根因之一。
     *
     * 改为比例主导后：128k → 12800 被下限抬到 20000 仍是 15.6%？否——下方约束保证
     * head 一定装得进摘要窗口，见 [minTailForSummaryWindow] 与 [tailBudget]。
     *
     * 约束（已验算）：压缩后大小 = tail + 摘要（约 10k）须小于触发阈值，
     * 否则会「压完立刻又触发」。tail 不超过 [usableTokens]/4，故压缩后最大为
     * 阈值/4 + 10k，对任何阈值都成立。
     */
    fun preserveRecentTokens(usableTokens: Int, contextLimit: Int = DEFAULT_CONTEXT_TOKENS): Int {
        val cap = maxOf(MAX_PRESERVE_RECENT_TOKENS, (contextLimit * PRESERVE_WINDOW_RATIO).toInt())
        return (usableTokens / 4).coerceIn(MIN_PRESERVE_RECENT_TOKENS, cap)
    }

    /**
     * 由摘要模型窗口反推出的 tail **下限**，保证被折叠的 head 装得进摘要输入窗口。
     *
     * head ≈ 触发阈值 − tail，必须 ≤ 摘要窗口 × [SUMMARY_WINDOW_RESERVE_RATIO]，
     * 否则 `ContextCompactor.truncateForSummaryWindow` 会丢弃最旧历史（静默失忆）。
     * 解出 tail ≥ 阈值 − 摘要输入预算，即本函数返回值。
     *
     * 摘要模型窗口未知时调用方回退 [DEFAULT_CONTEXT_TOKENS]。主窗口远大于摘要窗口时
     * （如 1M 主 + 128k 摘要）本下限可能超过按比例算的 tail 预算——此时**取本下限**
     * 优先：宁可多留原文进 tail，也不能让被折叠的 head 超出摘要窗口而被静默丢弃。
     *
     * @return 满足摘要窗口约束的 tail 下限（token）。可为负，调用方需再与下界取大。
     */
    fun minTailForSummaryWindow(triggerThreshold: Int, summaryWindowTokens: Int): Int {
        val summaryInputBudget = (summaryWindowTokens * SUMMARY_WINDOW_RESERVE_RATIO).toInt()
        val headAllowance = summaryInputBudget - SUMMARY_TOKENS_ESTIMATE
        return triggerThreshold - headAllowance
    }

    /** 摘要自动回落时的产物长度估算（摘要正文 + 时点标注 + 块提示，保守取 10k）。 */
    private const val SUMMARY_TOKENS_ESTIMATE = 10_000

    /**
     * 使 C1/C2 可行的**最小摘要模型窗口**（token），供日志提示「压缩专用模型窗口至少多大」。
     *
     * 推导：C2 下界 `阈值 − (0.7·sw − 摘要估算)` ≤ C1 上界 `阈值 − 摘要估算 − 1`
     * ⟺ `0.7·sw ≥ 2 × 摘要估算 + 1` ⟺ `sw ≥ (2 × 摘要估算 + 1) / 0.7`。
     * **与触发阈值无关**（阈值在两边同项抵消），故无需入参。
     */
    fun minSummaryWindowForFeasibility(): Int =
        kotlin.math.ceil((2.0 * SUMMARY_TOKENS_ESTIMATE + 1) / SUMMARY_WINDOW_RESERVE_RATIO).toInt()

    /**
     * tail 保护区的**最终**预算（`ContextCompactor.compactIfNeededLocked` 实际使用的公式）。
     *
     * 两条硬约束同时成立才算合法压缩：
     *
     * - **C1 不立即再触发**：`tail + 摘要(≈10k) < 阈值`。否则压完当场又达标，形成高频压缩。
     * - **C2 摘要装得下**：`head = 阈值 − tail ≤ 摘要窗口 × 70%`。否则被折叠的 head 装不进
     *   摘要模型的输入窗口，`CompactionSummaryWindow` 会把最旧的一段**静默丢弃**（历史失忆）。
     *
     * 由 C1 得 `tail < 阈值 − 10k`（上界）；由 C2 得 `tail ≥ 阈值 − 摘要输入预算`（下界）。
     * 二者可行当且仅当 `摘要窗口 × 70% − 10k ≥ 下限`，即**摘要模型窗口 ≳ 28.6k**。
     * 低于该值时 C1/C2 无交集（小窗口摘要模型是真实存在的配置，不能假定「模型都满足」）——
     * 此时 [tailBudgetPlan] 置 `feasible=false`，调用方必须**显式告警**而非静默丢历史。
     *
     * 取值 = 先按窗口比例取 [preserveRecentTokens]（兼顾大窗口下压缩力度），
     * 再夹进 `[C2 下界, C1 上界]`。因此：
     * - 摘要窗口充足（= 主窗口）时，C2 下界很低，结果就是比例预算——行为与旧版一致；
     * - 摘要窗口偏小时（如 1M 主 + 128k 摘要），C2 下界抬高 tail，把 head 压回摘要窗口内，
     *   牺牲一部分压缩力度换取「不丢历史」。这正是旧版 128k 配置丢历史的修复点。
     */
    fun tailBudget(
        triggerThreshold: Int,
        contextLimit: Int = DEFAULT_CONTEXT_TOKENS,
        summaryWindowTokens: Int = DEFAULT_CONTEXT_TOKENS,
        overheadTokens: Int = 0
    ): Int = tailBudgetPlan(triggerThreshold, contextLimit, summaryWindowTokens, overheadTokens).tail

    /**
     * [tailBudget] 的完整结果：tail 值 + 两条约束是否同时可满足。
     *
     * 为什么要把「可满足性」传出去：当摘要模型窗口过小（`0.7 × 摘要窗口 − 摘要估算 < 下限`，
     * 即摘要窗口 ≲ 28.6k）时 **C1 与 C2 数学上无交集**——tail 再大也压不出装得进摘要窗口的 head。
     * 旧实现直接 clamp 到 C1 上界，表现为「压缩照常成功，但每轮静默丢掉最旧历史」，
     * 调用方与用户都无从得知。现由调用方据此打印**显式告警**（诚实失败优于静默失忆）。
     */
    data class TailBudgetPlan(val tail: Int, val feasible: Boolean, val reason: String)

    /**
     * 计算 tail 预算并判定 C1/C2 是否可同时满足。
     *
     * 取舍顺序（都不可行时以 C1 优先，避免「压完立即再触发」的死循环）：
     * 1. 两者可行 → 比例预算夹进 `[C2 下界, C1 上界]`；
     * 2. C2 无解 → 取 C1 上界（尽量不丢历史是不可能的，退化为「压缩成功但有丢弃」，并置 feasible=false）；
     * 3. C1 无解（阈值小到装不下摘要，压缩本身无意义）→ 同样取上界并置 feasible=false。
     *
     * @param overheadTokens **固定开销 Δ**（system prompt + tools + memory blocks 等每轮恒定注入，
     *   不含在 [composeMessages]/messages 文本里的部分）。触发判断用的是**真实 usage（含 Δ）**，
     *   而压缩后大小 = tail + 摘要 + Δ。C1 上界若不扣 Δ，就会系统性乐观：当 C2 下界主导 tail 时
     *   `tail ≈ 阈值 − 摘要估算`，压缩后 ≈ `阈值 − 摘要估算 + 摘要 + Δ`，只要 `摘要 + Δ > 摘要估算`
     *   就 ≥ 阈值 → 压完立即再触发（实测「128k 主 + 64k 摘要」配置下 Δ 取 20~40k 时必现）。
     *   调用方应传 `max(0, lastInputTokens − 本地估算)`；无真实 usage 时传 0（退化为旧行为）。
     */
    fun tailBudgetPlan(
        triggerThreshold: Int,
        contextLimit: Int = DEFAULT_CONTEXT_TOKENS,
        summaryWindowTokens: Int = DEFAULT_CONTEXT_TOKENS,
        overheadTokens: Int = 0
    ): TailBudgetPlan {
        val proportional = preserveRecentTokens(triggerThreshold, contextLimit)
        // C2 下界：head 必须装进摘要输入预算。
        val lower = minTailForSummaryWindow(triggerThreshold, summaryWindowTokens)
        // C1 上界：压缩后（tail + 摘要 + 固定开销 Δ）必须低于触发阈值；留 1 token 保证严格小于。
        val overhead = overheadTokens.coerceAtLeast(0)
        val upper = (triggerThreshold - SUMMARY_TOKENS_ESTIMATE - overhead - 1)
            .coerceAtLeast(MIN_PRESERVE_RECENT_TOKENS)
        val lo = lower.coerceAtLeast(MIN_PRESERVE_RECENT_TOKENS)
        // 可行 ⟺ C2 下界不高于 C1 上界。
        val feasible = lo <= upper
        val tail = if (feasible) proportional.coerceIn(lo, upper) else upper
        val reason = when {
            feasible -> "C1 与 C2 同时满足"
            // 固定开销分支优先于「摘要窗口过小」：Δ + 摘要 ≥ 阈值时，即便摘要窗口无限大也无解
            // （head 可全塞进摘要窗口，但压缩后仍 = tail + 摘要 + Δ ≥ 阈值）。故这是更根本的原因。
            overhead + SUMMARY_TOKENS_ESTIMATE >= triggerThreshold ->
                "固定开销过大：Δ($overhead) + 摘要估算($SUMMARY_TOKENS_ESTIMATE) ≥ 触发阈值($triggerThreshold)，" +
                    "压缩后必然仍超阈值——需增大窗口/降低每轮固定注入（如收敛记忆召回）"
            lower > upper -> "摘要窗口过小：C2 下界($lower) 高于 C1 上界($upper)，无法既装进摘要窗口又不立即再触发"
            else -> "触发阈值过小：容不下摘要估算($SUMMARY_TOKENS_ESTIMATE)"
        }
        return TailBudgetPlan(tail, feasible, reason)
    }

    /**
     * 按**字符数**估算 token（向下兼容的旧口径）。仅适用于纯 ASCII/拉丁文本；
     * 含 CJK 的文本请用 [estimateTokens]（按文本内容分桶，避免系统性低估中文）。
     */
    fun estimateTokens(chars: Int): Int =
        (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * 单张图片的 token 折算（保守下限）。
     *
     * 视觉模型按「瓦片」计费：OpenAI 系一张中低分辨率图约 765~1105 token，高分辨率可达
     * 数千；Anthropic 约 (宽×高)/750。实测（2026-10-12 会话 f0947716）一晚虚拟屏截图后
     * 真实 usage=241K、文本估算仅 43K，差额 ≈197K 主要是图片——即单图真实成本在数千级。
     * 这里取 **2048/张**：低取真实下限之半与千位对齐，保证「几十张图」必然超过 tail
     * 预算而被折叠；同时不至于把少量图片的普通会话误判成超限。
     * 不计入的话，图片消息在压缩器的估算里是 **0**，压缩器判「无可压缩内容」跳过 →
     * 立即再触发 → 每轮白烧一次摘要调用。
     */
    const val IMAGE_TOKENS = 2_048

    /** N 张图片的 token 折算。独立命名（不用 `estimateTokens(Int)` 重载）——
     *  与 [estimateTokens]`(chars: Int)` 同为 `(Int)->Int`，重载会冲突。 */
    fun estimateImageTokens(images: Int): Int = images * IMAGE_TOKENS

    /** 摘要长度上限占摘要模型窗口的比例。 */
    private const val SUMMARY_MAX_WINDOW_RATIO = 0.2

    /**
     * 压缩摘要的 **token 上限**：摘要自身不得超过「摘要模型窗口」的 [SUMMARY_MAX_WINDOW_RATIO]。
     *
     * 为什么必须封顶：摘要是「模型生成→落库→每轮回放」的常驻文本，且更新式摘要会把整份旧摘要回炉，
     * 实测单会话摘要从 13k 单调膨胀到 105k 字符，直至摘要自身 ≈ 触发阈值，压缩后立即再触发
     * （2026-10-11 会话 22ddb225：42 次压缩、重触发间隔 12.2min→2.9min，一阶自相关 r1=0.889）。
     *
     * 取值 20%：给摘要留出「够写任务状态+未决+下一步」的余量，同时保证摘要 ≪ 触发阈值
     * （阈值通常 ≥ 窗口的 60%，摘要占 20% 窗口时压缩后仍有 ≥40% 窗口净空）。
     *
     * 返回 token 数；字符宽度由调用方 [estimateTokens] 的 CJK 口径折算，故更严。
     */
    fun maxSummaryTokens(summaryWindowTokens: Int): Int {
        val window = summaryWindowTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        return (window * SUMMARY_MAX_WINDOW_RATIO).toInt()
    }

    /**
     * 按**文本内容**估算 token：CJK/全角标点分桶，修正 `chars/4` 对中文的约 2 倍低估。
     *
     * 口径（字符数 / 每 token 字符数）：CJK 汉字·假名·谚文约 1.8、全角标点约 2.8、
     * ASCII 与其它约 2.5。上限做向上取整并对空串返回 0。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0.0
        var punct = 0.0
        var other = 0.0
        for (ch in text) {
            when (bucketOf(ch)) {
                CharBucket.CJK -> cjk += 1.0
                CharBucket.FULLWIDTH_PUNCT -> punct += 1.0
                CharBucket.OTHER -> other += 1.0
            }
        }
        val tokens = cjk / CJK_CHARS_PER_TOKEN + punct / FULLWIDTH_PUNCT_CHARS_PER_TOKEN + other / ASCII_CHARS_PER_TOKEN
        return maxOf(1, kotlin.math.ceil(tokens).toInt())
    }

    private enum class CharBucket { CJK, FULLWIDTH_PUNCT, OTHER }

    private fun bucketOf(ch: Char): CharBucket {
        val code = ch.code
        return when {
            code in 0x4E00..0x9FFF -> CharBucket.CJK   // CJK 统一表意
            code in 0x3400..0x4DBF -> CharBucket.CJK   // 扩展 A
            code in 0x3040..0x30FF -> CharBucket.CJK   // 平/片假名
            code in 0xAC00..0xD7A3 -> CharBucket.CJK   // 谚文音节
            code in 0xF900..0xFAFF -> CharBucket.CJK   // CJK 兼容
            code in 0x3000..0x303F -> CharBucket.FULLWIDTH_PUNCT
            code in 0xFF00..0xFFEF -> CharBucket.FULLWIDTH_PUNCT
            code in 0x2010..0x205E -> CharBucket.FULLWIDTH_PUNCT
            else -> CharBucket.OTHER
        }
    }

    private const val CJK_CHARS_PER_TOKEN = 1.8
    private const val FULLWIDTH_PUNCT_CHARS_PER_TOKEN = 2.8
    private const val ASCII_CHARS_PER_TOKEN = 2.5
}

