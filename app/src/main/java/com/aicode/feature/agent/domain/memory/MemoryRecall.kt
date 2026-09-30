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
    /**
     * 该记忆声明的触发词。用于两件事：① 门控（把候选收窄到「用户真的提了这个场景」）；
     * ② 加权（门控回退全集时用于区分）。空表时两机制自动跳过，不劣化。
     */
    val triggers: List<String> = emptyList(),
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

    /** triggers 命中的加权系数（每个命中词加分，封顶见 [MAX_TRIGGER_HITS]）。 */
    private const val W_TRIGGER = 0.30

    /** triggers 加权封顶命中数，避免堆关键词刷分盖过正文相关性。 */
    private const val MAX_TRIGGER_HITS = 2.0

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

        // 停用词在**分词前**从原文剥离，而不是分词后按 token 过滤。
        // 因为 CJK bigram 会跨词边界：「怎么解决」→ 怎么/么解/解决，只按 token 删掉
        // 「怎么」「解决」后仍会留下「么解」，噪声文档照样匹配（实测踩中）。
        // 两侧都剥：只剥 query 会让 df/avgLen 偏斜。
        val docTokens = scored.map { tokenizeForRecall(stripStopTerms(it.text.take(MAX_INDEX_CHARS))) }

        // 门控：若某些记忆的 triggers 与查询有交集，就把候选收窄到这个子集。
        // 「发版」→ android-build-env（triggers 含「发版」）这类字面不重叠但语义明确的
        // 查询，纯 BM25 会拿 0 分；门控把用户词表桥接到记忆。
        // 只收窄不放空：命中过少（0）或过宽（超过半数候选）时回退全集，避免误杀。
        val queryRaw = tokenizeForRecall(stripStopTerms(query))
        val gated = scored.filter { triggerHits(queryRaw, it.triggers) > 0 }
        val candidates = if (gated.isNotEmpty() && gated.size * 2 <= scored.size) gated else scored
        val candidateTokens = if (candidates === scored) docTokens
            else candidates.map { tokenizeForRecall(stripStopTerms(it.text.take(MAX_INDEX_CHARS))) }

        val docFreq = HashMap<String, Int>()
        for (tokens in candidateTokens) {
            for (t in tokens.toHashSet()) docFreq[t] = (docFreq[t] ?: 0) + 1
        }
        val n = candidates.size
        val avgLen = candidateTokens.sumOf { it.size }.toDouble() / n

        val idf = HashMap<String, Double>()
        var anyMatch = false
        for (t in queryRaw.toHashSet()) {
            val df = docFreq[t]
            if (df != null && df > 0) {
                anyMatch = true
                idf[t] = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            }
        }

        // 门控已命中时，即使 BM25 全为 0（字面无重叠）也应交付这些候选；
        // 否则保留原有的「全无匹配则不召回」行为。
        val gateActive = candidates !== scored
        val ranked = if (!anyMatch && !gateActive) emptyList() else candidates
            .mapIndexed { i, doc ->
                val bm25 = rawBm25(idf, candidateTokens[i], avgLen, k1, b)
                val boost = W_TRIGGER * minOf(MAX_TRIGGER_HITS, triggerHits(queryRaw, doc.triggers).toDouble())
                doc to (bm25 + boost)
            }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<RecallDoc, Double>> { it.second }
                    .thenByDescending { it.first.updatedAtMs }
                    .thenBy { it.first.id },
            )
            .map { it.first }

        return (pinned + ranked).take(maxHits)
    }

    /**
     * 从原文中剥离提问功能词。必须在分词**之前**做：CJK bigram 会跨越被删词的边界，
     * 事后按 token 过滤会漏掉「么解」这类跨界组合，使停用词过滤失效。
     */
    private fun stripStopTerms(text: String): String {
        var out = text
        for (term in STOP_TERMS) {
            if (term.isNotEmpty() && out.contains(term)) out = out.replace(term, " ")
        }
        return out
    }

    /**
     * 查询 token 与记忆 triggers 的命中项数。triggers 自身也走同一分词器，
     * 故中英混排、CJK bigram 都能对齐（如 triggers 里的「发版」可被「帮我发个正式版」命中）。
     *
     * **单个 CJK 字符的偶然重合不算命中**，这是必须的：
     * 「并发」与「发个正式版」共享「发」、「架构」与「构建」共享「构」、
     * 「连不上」与「能不能用」共享「不」——若按 .any 放行，门控会把一批无关记忆
     * 一并纳入候选（实测 16 条真实记忆里 10 条查询中招），甚至把无关项排到第 1 位。
     *
     * 真命中要么有 **≥2 个 token 重合**（「发版」×「发个正式版」共享 发+版），
     * 要么重合的是 **多字符 token**（「release」这类单 token 拉丁词；「构建」这类 CJK bigram，
     * 后者能证明是真实子串而非巧合；而单字「构」只能证明共享了一个汉字）。
     */
    private fun triggerHits(queryTokens: List<String>, triggers: List<String>): Int {
        if (queryTokens.isEmpty() || triggers.isEmpty()) return 0
        val querySet = queryTokens.toHashSet()
        var hits = 0
        for (trg in triggers) {
            val shared = tokenizeForRecall(trg).filter { it in querySet }
            if (shared.size >= 2 || shared.any { it.length >= 2 }) hits++
        }
        return hits
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

    /**
     * 区分度低的「提问功能词」：它们几乎只表达「想问」，不携带主题信息，
     * 但泛用性高会在 BM25 里拿到不低的 idf，把真正相关的记忆挤下去。
     *
     * 与 [GENERIC_TERMS] **分开**：后者参与 [isGeneric] 的「整句是否无信息」判定，
     * 并入会把「怎么构建」这类有效查询误判为泛化而完全不召回。
     *
     * 实测依据：加入「怎么/如何/问题/解决」等词后，「这个构建问题怎么解决」的
     * 完全命中从 0/3 升到 2/3。
     */
    private val STOP_TERMS: Set<String> = setOf(
        "怎么", "如何", "什么", "为什么", "哪里", "哪些", "问题", "解决", "方法", "办法",
        "需要", "时候", "这个", "那个", "还是", "就是", "没有", "情况", "已经", "可能",
        "应该", "必须", "不能", "不会", "进行", "出现", "导致", "因为", "所以", "但是",
        "如果", "以及", "并且", "或者", "东西", "地方", "意思",
    )
}
