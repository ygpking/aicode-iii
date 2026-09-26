package com.aicode.feature.agent.domain.tool.editor

import com.aicode.feature.agent.domain.tool.TextSimilarity

/**
 * 编辑文本的多级降级匹配器。
 *
 * 模型给出的 `old_string` 常因「抄写失真」而与文件真实内容有细微出入——弯引号被智能引号替换、
 * 从带行号的读取结果里把 `123: ` 一起抄进来、制表符写成 `\t` 字面量、整块缩进对不齐。若只做
 * 精确匹配，这些都能让一次本可成功的编辑失败。故按「可信度从高到低」逐级尝试，命中即止。
 *
 * 核心不变量：**返回的始终是原文中的真实区间**（[Match.start]/[Match.end] 直接切原文）。
 * 归一化文本只用于查找，绝不用于回写——因此未触碰的行按原字节保留（换行符、编码、尾随空白均不变）。
 *
 * 「首末行锚定」宽匹配在 `replace_all` 场景被调用方关掉（[findUnique] 的 `allowFuzzy`），
 * 避免「宽匹配 + 全部替换」放大误改；该场景改用精确的 [findAllExact]。
 */
internal object EditMatcher {

    /** 匹配档位，按尝试顺序排列（越靠前越可信）。 */
    enum class Level(val label: String) {
        EXACT("精确匹配"),
        QUOTE_NORMALIZED("引号归一"),
        LINE_NUMBER_STRIPPED("剥离行号前缀"),
        ESCAPE_NORMALIZED("转义还原"),
        UNICODE_ESCAPE_NORMALIZED("Unicode 转义还原"),
        LINE_TRIMMED("逐行去空白"),
        BLOCK_ANCHOR("首末行锚定")
    }

    /** 原文中的一段真实区间；[text] 即 `content.substring(start, end)`。 */
    data class Match(val start: Int, val end: Int, val text: String)

    sealed interface Result {
        /** 唯一命中。[level] 为命中的档位（非 [Level.EXACT] 即说明用了模糊匹配）。 */
        data class Found(val match: Match, val level: Level) : Result

        /** 该档位命中多处，无法定位到唯一区间。 */
        data class Ambiguous(val count: Int, val level: Level) : Result

        /** 所有档位均未命中。 */
        data object NotFound : Result
    }

    private const val BLOCK_ANCHOR_MIN_SIMILARITY = 0.8

    private val LINE_NUMBER_PREFIX = Regex("^\\s*\\d+[:\\t]\\s?")
    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    /**
     * 查找唯一匹配。
     *
     * @param allowFuzzy 为 false 时只做精确匹配（供 `replace_all` 使用）。
     */
    fun findUnique(content: String, oldString: String, allowFuzzy: Boolean = true): Result {
        if (oldString.isEmpty()) return Result.NotFound

        exactRanges(content, oldString).let { if (it.isNotEmpty()) return resolve(content, it, Level.EXACT) }
        if (!allowFuzzy) return Result.NotFound

        quoteNormalizedRanges(content, oldString).let { if (it.isNotEmpty()) return resolve(content, it, Level.QUOTE_NORMALIZED) }
        transformedRanges(content, oldString, ::stripLineNumberPrefix).let { if (it.isNotEmpty()) return resolve(content, it, Level.LINE_NUMBER_STRIPPED) }
        transformedRanges(content, oldString, ::unescape).let { if (it.isNotEmpty()) return resolve(content, it, Level.ESCAPE_NORMALIZED) }
        transformedRanges(content, oldString, ::decodeUnicodeEscapes).let { if (it.isNotEmpty()) return resolve(content, it, Level.UNICODE_ESCAPE_NORMALIZED) }

        val index = LineIndex(content)
        val patternLines = oldString.split('\n')
        lineRanges(index, patternLines, ::lineTrimmedPredicate).let { if (it.isNotEmpty()) return resolve(content, it, Level.LINE_TRIMMED) }
        lineRanges(index, patternLines, ::blockAnchorPredicate).let { if (it.isNotEmpty()) return resolve(content, it, Level.BLOCK_ANCHOR) }

        return Result.NotFound
    }

    /** 精确查找全部不重叠匹配，供 `replace_all` 使用。 */
    fun findAllExact(content: String, oldString: String): List<Match> {
        if (oldString.isEmpty()) return emptyList()
        return allOccurrences(content, oldString).map { start ->
            Match(start, start + oldString.length, content.substring(start, start + oldString.length))
        }
    }

    private fun resolve(content: String, ranges: List<IntRange>, level: Level): Result =
        if (ranges.size == 1) {
            val r = ranges.first()
            Result.Found(Match(r.first, r.last + 1, content.substring(r.first, r.last + 1)), level)
        } else {
            Result.Ambiguous(ranges.size, level)
        }

    // ── 各档位的候选区间（原文偏移）───────────────────────────────────────

    private fun exactRanges(content: String, query: String): List<IntRange> =
        allOccurrences(content, query).map { it..(it + query.length - 1) }

    /** 弯引号等单字符归一：归一化不改变长度，故偏移可直接映射回原文。 */
    private fun quoteNormalizedRanges(content: String, query: String): List<IntRange> = try {
        val normalizedContent = normalizeQuotes(content)
        val normalizedPattern = normalizeQuotes(query)
        if (normalizedContent == content && normalizedPattern == query) emptyList()
        else exactRanges(normalizedContent, normalizedPattern)
    } catch (e: OutOfMemoryError) {
        emptyList()
    }

    /** 先对 [oldString] 施加 [transform] 再在原文中精确查找；变换无效果时跳过该档。 */
    private fun transformedRanges(
        content: String,
        oldString: String,
        transform: (String) -> String
    ): List<IntRange> {
        val transformed = transform(oldString)
        if (transformed == oldString || transformed.isEmpty()) return emptyList()
        return exactRanges(content, transformed)
    }

    /** 行窗口映射：调用方传入谓词，命中多处即交给上层判为歧义。 */
    private fun lineRanges(
        index: LineIndex,
        patternLines: List<String>,
        predicate: (contentWindow: List<String>, patternLines: List<String>) -> Boolean
    ): List<IntRange> {
        val windows = index.findWindows(patternLines, predicate)
        if (windows.isEmpty()) return emptyList()
        return windows.map { (startLine, endLine) ->
            index.lineStart(startLine)..(index.lineEndExclusive(endLine) - 1)
        }
    }

    /** 逐行去首尾空白后比较；覆盖纯缩进/尾随空白差异。 */
    fun lineTrimmedPredicate(contentWindow: List<String>, patternLines: List<String>): Boolean =
        patternLines.indices.all { contentWindow[it].trim() == patternLines[it].trim() }

    /** 首末行去空白后必须相等，中间行按相似度门槛逐个校验；单行模式不适用（与逐行去空白重复）。 */
    fun blockAnchorPredicate(contentWindow: List<String>, patternLines: List<String>): Boolean {
        if (patternLines.size < 2) return false
        if (contentWindow.first().trim() != patternLines.first().trim()) return false
        if (contentWindow.last().trim() != patternLines.last().trim()) return false
        for (k in 1 until patternLines.size - 1) {
            val similarity = TextSimilarity.similarity(contentWindow[k].trim(), patternLines[k].trim())
            if (similarity < BLOCK_ANCHOR_MIN_SIMILARITY) return false
        }
        return true
    }

    private fun allOccurrences(text: String, query: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = text.indexOf(query)
        while (from >= 0) {
            out += from
            from = text.indexOf(query, from + 1)
        }
        return out
    }

    // ── 归一化实现 ────────────────────────────────────────────────────────

    /** 长度保持的引号/短横/不间断空格归一（每个字符映射到等长单字符）。 */
    private fun normalizeQuotes(s: String): String {
        var changed = false
        val out = StringBuilder(s.length)
        for (c in s) {
            val mapped = when (c) {
                '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                '\u2013', '\u2014', '\u2212' -> '-'
                '\u00A0' -> ' '
                else -> c
            }
            if (mapped != c) changed = true
            out.append(mapped)
        }
        return if (changed) out.toString() else s
    }

    /** 剥离行首行号前缀（`123: ` 或 `123\t`），处理「从带行号读取结果抄参数」。 */
    fun stripLineNumberPrefix(line: String): String = LINE_NUMBER_PREFIX.replace(line, "")

    /** 把 `\n \t \r \" \' \\` 字面转义还原为真实字符。 */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\'' -> { out.append('\''); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** 把 `\uXXXX` 还原为真实字符。 */
    fun decodeUnicodeEscapes(s: String): String =
        if (!s.contains("\\u")) s else UNICODE_ESCAPE.replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

    /** 文件按行视图：保留每行起始偏移，供把行窗口映射回原文区间。 */
    internal class LineIndex(val content: String) {
        val lines: List<String>
        private val starts: IntArray

        init {
            val ls = ArrayList<String>()
            val ss = ArrayList<Int>()
            var i = 0
            while (true) {
                val start = i
                val newline = content.indexOf('\n', i)
                if (newline < 0) {
                    ls.add(content.substring(start))
                    ss.add(start)
                    break
                }
                ls.add(content.substring(start, newline))
                ss.add(start)
                i = newline + 1
            }
            lines = ls
            starts = ss.toIntArray()
        }

        fun lineStart(line: Int): Int = starts[line]

        /** 该行结束偏移（不含 `\n`，即该行末字符的下一位置；末行到 content 结尾）。 */
        fun lineEndExclusive(line: Int): Int =
            if (line + 1 < starts.size) starts[line + 1] - 1 else content.length

        /** 滑动 [patternLines] 大小的窗口，返回所有满足 [predicate] 的行区间（闭区间）。 */
        fun findWindows(
            patternLines: List<String>,
            predicate: (contentWindow: List<String>, patternLines: List<String>) -> Boolean
        ): List<Pair<Int, Int>> {
            val size = patternLines.size
            if (size == 0 || lines.size < size) return emptyList()
            val out = mutableListOf<Pair<Int, Int>>()
            for (start in 0..lines.size - size) {
                val window = lines.subList(start, start + size)
                if (predicate(window, patternLines)) out += start to (start + size - 1)
            }
            return out
        }
    }
}
