package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000

    /**
     * tail 保护区的**基础**上限（128k 窗口时代的取值，占窗口 15.6%）。
     *
     * 大窗口下该值偏小：1M 窗口下只占 2%，压缩一次要折叠 98% 的历史，
     * 全押摘要质量。故实际上限按窗口取宽，见 [preserveRecentTokens]。
     */
    const val MAX_PRESERVE_RECENT_TOKENS = 20_000

    /** 大窗口下 tail 占窗口的目标比例。 */
    private const val PRESERVE_WINDOW_RATIO = 0.1

    const val CHARS_PER_TOKEN = 4

    /**
     * tail 保护区预算：按可用空间的四分之一，上限随窗口取宽。
     *
     * 上限 = `max(基础上限, 窗口 * 10%)`——小窗口下与旧行为完全一致（不回归），
     * 大窗口下从 2% 提到 10%。
     *
     * 约束（已验算）：压缩后大小 = tail + 摘要（约 10k）须小于触发阈值，
     * 否则会「压完立刻又触发」形成高频压缩。上式的 tail 不超过 [usableTokens]/4，
     * 而 [usableTokens] 传入的是触发阈值，故压缩后最大为 阈值/4 + 10k，对任何阈值都成立。
     */
    fun preserveRecentTokens(usableTokens: Int, contextLimit: Int = DEFAULT_CONTEXT_TOKENS): Int {
        val cap = maxOf(MAX_PRESERVE_RECENT_TOKENS, (contextLimit * PRESERVE_WINDOW_RATIO).toInt())
        return (usableTokens / 4).coerceIn(MIN_PRESERVE_RECENT_TOKENS, cap)
    }

    /**
     * 按**字符数**估算 token（向下兼容的旧口径）。仅适用于纯 ASCII/拉丁文本；
     * 含 CJK 的文本请用 [estimateTokens]（按文本内容分桶，避免系统性低估中文）。
     */
    fun estimateTokens(chars: Int): Int =
        (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

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

