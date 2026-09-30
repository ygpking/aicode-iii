package com.aicode.feature.agent.domain.memory

import kotlin.math.ln

/**
 * 召回用的轻量分词器（零依赖）。
 *
 * 拉丁文段按「非字母数字」切分并小写；CJK 段不做词切分，而是**逐字 + 相邻字符 bigram**——
 * 无需词典即可让「召回」「记忆召回」这类无空格语言的子串匹配生效。
 *
 * 例：`tokenize("记忆召回")` → `[记, 忆, 召, 回, 记忆, 忆召, 召回]`
 */
internal fun tokenizeForRecall(text: String): List<String> {
    val out = ArrayList<String>()
    val cjk = StringBuilder()
    val latin = StringBuilder()

    fun flushCjk() {
        if (cjk.isEmpty()) return
        val run = cjk.toString()
        for (ch in run) out.add(ch.toString())
        var i = 0
        while (i + 1 < run.length) {
            out.add(run.substring(i, i + 2))
            i++
        }
        cjk.setLength(0)
    }

    fun flushLatin() {
        if (latin.isEmpty()) return
        out.add(latin.toString().lowercase())
        latin.setLength(0)
    }

    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val width = Character.charCount(cp)
        when {
            isCjk(cp) -> {
                flushLatin()
                cjk.append(text, i, i + width)
            }
            Character.isLetterOrDigit(cp) -> {
                flushCjk()
                latin.append(text, i, i + width)
            }
            else -> {
                flushCjk()
                flushLatin()
            }
        }
        i += width
    }
    flushCjk()
    flushLatin()
    return out
}

/** 统一表意文字（含扩展 A / 兼容区）与假名、谚文音节。 */
private fun isCjk(cp: Int): Boolean =
    cp in 0x4E00..0x9FFF ||
        cp in 0x3400..0x4DBF ||
        cp in 0xF900..0xFAFF ||
        cp in 0x3040..0x30FF ||
        cp in 0xAC00..0xD7AF

/**
 * 一条可被召回的记忆。
 *
 * @param id 稳定标识（记忆名），用于去重与排序兜底。
 * @param scope 作用域，参与打分加权（项目级权重更高）。
 * @param text 记忆正文。
 * @param pinned true 表示「必常驻」：与相关性正交，召回时直接置顶。
 * @param updatedAtMs 最近更新时间，用于同分时的稳定排序。
 */
internal data class RecallDoc(
    val id: String,
    val scope: MemoryScope,
    val text: String,
    val pinned: Boolean = false,
    val updatedAtMs: Long = 0L,
)

/**
 * 记忆召回选择器：给当前查询挑出最相关的若干条记忆，渲染成可挂到本轮 user 消息后缀的文本块。
 *
 * 打分用 BM25（CJK 由 [tokenizeForRecall] 的 bigram 支撑），再按 scope 轻微加权；泛化查询
 * （「继续」「好的」这类）一律不召回，避免噪声把上下文撑大。
 *
 * 纯函数、零 IO、结果确定：同一 (query, docs) 必得同一结果，故可安全地每轮重算而不产生漂移。
 */
internal object MemoryRecall {

    /** 单条记忆参与索引的正文上限，避免超长记忆拖慢每轮打分。 */
    private const val MAX_INDEX_CHARS = 4000

    fun select(
        query: String,
        docs: List<RecallDoc>,
        maxHits: Int = 5,
        k1: Double = 1.2,
        b: Double = 0.75,
    ): List<RecallDoc> {
        if (docs.isEmpty()) return emptyList()
        if (isGeneric(query)) return emptyList()

        // pinned 与相关性正交：排序时直接置顶（泛化查询仍不召回，与整体抑制策略一致）。
        val pinned = docs.filter { it.pinned }
        val scored = docs.filterNot { it.pinned }

        if (scored.isEmpty()) return pinned.take(maxHits)

        val docTokens = scored.map { tokenizeForRecall(it.text.take(MAX_INDEX_CHARS)) }
        val docFreq = HashMap<String, Int>()
        for (tokens in docTokens) {
            for (t in tokens.toHashSet()) docFreq[t] = (docFreq[t] ?: 0) + 1
        }
        val n = scored.size
        val avgLen = docTokens.sumOf { it.size }.toDouble() / n

        val queryTokens = tokenizeForRecall(query)
        val idf = HashMap<String, Double>()
        var anyMatch = false
        for (t in queryTokens.toHashSet()) {
            val df = docFreq[t]
            if (df != null && df > 0) {
                anyMatch = true
                idf[t] = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            }
        }

        val ranked = if (!anyMatch) emptyList() else scored
            .mapIndexed { i, doc -> doc to rawBm25(idf, docTokens[i], avgLen, k1, b) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<RecallDoc, Double>> { it.second }
                    .thenByDescending { it.first.updatedAtMs }
                    .thenBy { it.first.id },
            )
            .map { it.first }

        return (pinned + ranked).take(maxHits)
    }

    fun renderBlock(
        docs: List<RecallDoc>,
        maxCharsPerBlock: Int = 2000,
        maxTotalChars: Int = 8000,
    ): String = render(docs, maxCharsPerBlock, maxTotalChars)

    /**
     * 召回「索引块」：只渲染 名 + 摘要首段，**不内联正文**。
     *
     * 与 [renderBlock] 只差渲染宽度，[select] 的挑选结果完全相同（同一批 docs、同一顺序）。
     * 正文交给模型用 `memory(action=read)` 按需拉取。
     *
     * 为什么值得收紧：召回块会被拼进本轮发往模型的 user 消息，成为上下文固定前缀的一部分。
     * 它内联数千字符正文（实测 16 条真实记忆下平均 7.5k 字符）而多数命中并不需要正文细节，
     * 这部分开销会随上下文一并按 token 计费（且被压缩、缓存等环节一并放大）。
     * 只留「名 + 摘要」后实测降到约 840 字符，降幅约 89%。
     *
     * @param maxCharsPerBlock 单条上限，默认 120 字符（约等于 description 一句摘要）。
     * @param maxTotalChars 整块上限，默认 1200 字符（约 600 tokens）。
     *
     * 注意：摘要可能被截断，正文语义未尽。调用方应在块尾附「按需 read 正文」的提示
     * （见 StatefulAgentWorkflow.RECALL_READ_HINT）。
     */
    fun renderIndexBlock(
        docs: List<RecallDoc>,
        maxCharsPerBlock: Int = 120,
        maxTotalChars: Int = 1200,
    ): String = render(docs, maxCharsPerBlock, maxTotalChars)

    private fun render(
        docs: List<RecallDoc>,
        maxCharsPerBlock: Int,
        maxTotalChars: Int,
    ): String {
        if (docs.isEmpty()) return ""
        val sb = StringBuilder("<recalled_memory>")
        var total = 0
        for (doc in docs) {
            val body = doc.text.take(maxCharsPerBlock)
            val marker = if (doc.pinned) " pinned" else ""
            val entry = "\n[${doc.id} scope=${doc.scope.name.lowercase()}$marker]\n$body"
            if (total + entry.length > maxTotalChars) break
            sb.append(entry)
            total += entry.length
        }
        sb.append("\n</recalled_memory>")
        return sb.toString()
    }

    private fun rawBm25(
        idf: Map<String, Double>,
        tokens: List<String>,
        avgLen: Double,
        k1: Double,
        b: Double,
    ): Double {
        if (tokens.isEmpty()) return 0.0
        val freq = HashMap<String, Int>()
        for (t in tokens) freq[t] = (freq[t] ?: 0) + 1
        val len = tokens.size.toDouble()
        var score = 0.0
        for ((term, idfValue) in idf) {
            val f = freq[term] ?: continue
            val denom = f + k1 * (1 - b + b * len / (avgLen + 1e-9))
            score += idfValue * (f * (k1 + 1)) / denom
        }
        return score
    }

    /** 纯泛化查询（问候/确认/继续）不值得召回任何记忆。 */
    private fun isGeneric(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        if (GENERIC_PHRASES.contains(trimmed.lowercase())) return true
        val terms = tokenizeForRecall(trimmed).filterNot { GENERIC_TERMS.contains(it) }
        return terms.isEmpty()
    }

    private val GENERIC_PHRASES_CN = listOf(
        "继续", "好的", "接着", "收到", "明白", "明白了", "可以", "没问题",
        "谢谢", "多谢", "是的", "对的", "嗯", "好", "行", "可以了", "知道了",
        "就这么办", "继续吧", "下一步", "好了吗", "干得好", "再来",
    )
    private val GENERIC_PHRASES_EN = listOf(
        "ok", "okay", "yes", "yeah", "yep", "sure", "continue", "next",
        "go on", "go ahead", "proceed", "thanks", "thank you", "got it",
        "sounds good", "keep going", "well done", "good job", "nice",
    )
    private val GENERIC_PHRASES: Set<String> =
        (GENERIC_PHRASES_CN + GENERIC_PHRASES_EN).map { it.lowercase() }.toSet()

    private val GENERIC_TERMS: Set<String> = setOf(
        "ok", "okay", "yes", "yeah", "yep", "sure", "continue", "next",
        "proceed", "go", "on", "ahead", "thanks", "thank", "you", "got",
        "it", "good", "nice", "well", "done", "job", "again", "now",
        "please", "the", "and", "then", "let", "let's", "is", "are", "do",
        "嗯", "哦", "啊", "好", "行", "可以", "继续", "接着", "收到",
        "明白", "谢谢", "多谢", "是的", "对", "了", "吧", "呢", "的",
        "请", "然后", "那么", "那就", "一下", "来",
    )
}
