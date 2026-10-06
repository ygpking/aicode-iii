package com.aicode.feature.agent.domain.tool

import java.io.File

/**
 * 读取宿主观测日志的纯逻辑（不依赖 Android、不做路径猜测），便于单测边界。
 *
 * 三份日志的根目录由调用方给出（[resolveRoot] 只做子目录名映射），读取本身只认
 * [File]——文件不存在返回空结果而非抛异常，因为首次启动 / 日志等级为 NONE 时
 * 本来就可能没有文件。
 *
     * `kind=ai` 只暴露当前会话的文件（见 [sessionLogFiles]），不让模型翻阅他人会话。
     *
     * 目录名与筛选前缀必须与三份日志的落盘实现保持一致：
 * [com.aicode.core.util.FileLogger]（`logs/log-<日期>.txt`）、
 * [com.aicode.core.util.EventTrace]（`traces/trace-<日期>.log`）、
 * [com.aicode.core.util.AILogger]（`ai-logs/session-<id>.log`）。
 */
internal object DiagnosticsReader {

    /** 单行截断上限：日志被折叠成一行，但异常堆栈行仍可能很长。 */
    const val MAX_LINE_CHARS = 2_000

    /** 单次返回的总字符上限，防止一页把上下文撑爆。 */
    const val MAX_PAGE_CHARS = 48_000

    /** search 返回的**不同内容**条数上限（不是命中行数：重复内容合并成一条）。 */
    const val MAX_SEARCH_HITS = 200

    /** 单条命中最多记录多少个行号，超出只计入总数，防止全文命中同一内容时输出爆炸。 */
    const val MAX_HIT_LINE_NUMBERS = 20

    /** 行首时间戳：`logs/` 与 `traces/` 每行都带，去重时须剥掉才认得出同一逻辑内容。 */
    private val LEADING_TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}[.,]?\d{0,3}\s*""")

    /** 环形缓冲保留的最大行数：兼顾「往回翻很久」与内存上限。 */
    const val MAX_RING_LINES = 100_000

    data class Page(
        val content: String,
        val totalLines: Int,
        val startLine: Int,
        val endLine: Int,
        val hasMore: Boolean,
        val truncatedByChars: Boolean,
        /** 本页拍平后的不同文本行数（去重后）；与页行数相比即重复率。 */
        val distinctLines: Int,
    )

    /**
     * 一条去重后的搜索命中。[lineNumbers] 是该内容出现的行号（最多 [MAX_HIT_LINE_NUMBERS] 个），
     * [totalCount] 是实际出现次数；[text] 取首次出现时的原文。
     */
    data class SearchHit(
        val text: String,
        val lineNumbers: List<Int>,
        val totalCount: Int,
    ) {
        /** 渲染成一行：`[x3] 行号 5,17,42: 内容`；行号被截断时以 `…共N处` 标注。 */
        fun render(): String {
            val nums = if (totalCount > lineNumbers.size) {
                "${lineNumbers.joinToString(",")}…共${totalCount}处"
            } else {
                lineNumbers.joinToString(",")
            }
            return "[x$totalCount] 行号 $nums: $text"
        }
    }

    /** 三种日志的子目录名；未知 kind 返回 null。 */
    fun resolveRoot(base: File, kind: String): File? = when (kind) {
        "app" -> File(base, "logs")
        "trace" -> File(base, "traces")
        "ai" -> File(base, "ai-logs")
        else -> null
    }

    /** 列出根目录下的日志文件（按名升序，含轮转归档），目录不存在返回空。 */
    fun listFiles(root: File): List<File> =
        root.listFiles { f ->
            f.isFile && (f.name.startsWith("log-") || f.name.startsWith("trace-") || f.name.startsWith("session-"))
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * 列出某会话的模型交互日志，**按时间顺序**（轮转归档在前、正在写的在后）。
     *
     * 会话 id 的净化规则必须与 [com.aicode.core.util.AILogger] 落盘时一致（非 `[A-Za-z0-9_-]`
     * 一律换成 `_`），否则会漏匹配。返回顺序即读取顺序：归档是先前写下的，故排在前面；
     * 取「正在写的那个」用 `lastOrNull()`。
     */
    fun sessionLogFiles(root: File, sessionId: String): List<File> {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val all = listFiles(root)
        val rotated = all.firstOrNull { it.name == "session-$safeId.log.1" }
        val current = all.firstOrNull { it.name == "session-$safeId.log" }
        return listOfNotNull(rotated, current)
    }

    /**
     * 读取一页：把 [files] 依序当作**同一条日志流**，以流末尾 [offsetFromEnd] 行为基准向前取
     * [lines] 行；行号在整个流内连续。
     *
     * 流式扫描、只保留窗口大小的环形缓冲，故「当前 + 轮转归档」合计 40MB 的会话也不会整份进内存。
     * 任一文件不可读即返回 null：诊断工具宁可让调用方报错，也不给半截数据。
     */
    fun readWindow(files: List<File>, offsetFromEnd: Int, lines: Int): Page? {
        if (files.isEmpty() || files.any { !it.isFile || !it.canRead() }) return null
        val keep = (lines.coerceAtLeast(1) + offsetFromEnd.coerceAtLeast(0)).coerceAtMost(MAX_RING_LINES)
        val ring = ArrayDeque<String>()
        var total = 0
        for (file in files) {
            runCatching {
                file.forEachLine { raw ->
                    total++
                    ring.addLast(raw.take(MAX_LINE_CHARS))
                    while (ring.size > keep) ring.removeFirst()
                }
            }.onFailure { return null }
        }

        if (total == 0) {
            return Page(
                content = "", totalLines = 0, startLine = 0, endLine = 0,
                hasMore = false, truncatedByChars = false, distinctLines = 0,
            )
        }

        val pageEnd = (total - offsetFromEnd.coerceAtLeast(0)).coerceAtLeast(0)
        val pageStart = (pageEnd - lines.coerceAtLeast(1) + 1).coerceAtLeast(1)
        val skip = total - ring.size
        val fromIdx = pageStart - 1 - skip
        val toIdx = pageEnd - skip
        val slice = if (fromIdx in 0..ring.size && toIdx >= fromIdx) {
            ring.toList().subList(fromIdx.coerceAtLeast(0), toIdx.coerceAtMost(ring.size))
        } else {
            emptyList()
        }
        val (text, truncatedByChars) = capByChars(slice)
        return Page(
            content = text,
            totalLines = total,
            startLine = pageStart,
            endLine = pageEnd,
            hasMore = pageStart > 1,
            truncatedByChars = truncatedByChars,
            distinctLines = slice.distinct().size,
        )
    }

    /**
     * 子串搜索（大小写不敏感）：[files] 依序视为同一条流，行号连续。
     *
     * 结果**按内容去重**。`ai-logs` 记的是每次模型调用的完整请求体，同一段文本会因历史重发
     * 出现在几十行里；逐行返回只会把上下文塞满重复内容，也看不出「这段到底重复了多少次」。
     * 去重后每个不同内容给一条，附其全部行号，调用方再用 `read` 按行号精确定位。
     *
     * 去重键是**剥掉行首时间戳后的整行**，所以 `logs/` / `traces/` 里同一逻辑事件在不同时刻的
     * 重复也归为一条。不同内容条数上限 [MAX_SEARCH_HITS]；单条最多记 [MAX_HIT_LINE_NUMBERS]
     * 个行号，超出部分只计入 [SearchHit.totalCount]。
     *
     * 流式逐行匹配，不把日志整份读进内存——会话文件是 20MB 级，两份拼接后再全量驻留会逼近
     * 移动端堆上限。任一文件不可读返回 null。
     */
    fun search(files: List<File>, query: String): List<SearchHit>? {
        if (files.isEmpty() || files.any { !it.isFile || !it.canRead() }) return null
        val needle = query.lowercase()
        val byContent = LinkedHashMap<String, SearchHitBuilder>()
        var lineNo = 0
        var halted = false
        for (file in files) {
            runCatching {
                file.bufferedReader().use { reader ->
                    var raw = reader.readLine()
                    while (raw != null && !halted) {
                        lineNo++
                        val line = raw.take(MAX_LINE_CHARS)
                        if (line.lowercase().contains(needle)) {
                            val key = normalizeForDedup(line)
                            val existing = byContent[key]
                            if (existing != null) {
                                existing.add(lineNo)
                            } else if (byContent.size < MAX_SEARCH_HITS) {
                                byContent[key] = SearchHitBuilder(line, lineNo)
                            } else {
                                halted = true
                            }
                        }
                        raw = reader.readLine()
                    }
                }
            }.onFailure { return null }
            if (halted) break
        }
        return byContent.values.map { it.build() }
    }

    /** 去重键：剥掉行首时间戳再 trim。 */
    private fun normalizeForDedup(line: String): String =
        LEADING_TIMESTAMP.replace(line, "").trim()

    /** 逐条命中累积行号：行号列表封顶，总数照实累加。 */
    private class SearchHitBuilder(private val text: String, firstLine: Int) {
        private val lineNumbers = ArrayList<Int>()
        private var total = 0

        init {
            add(firstLine)
        }

        fun add(lineNo: Int) {
            total++
            if (lineNumbers.size < MAX_HIT_LINE_NUMBERS) lineNumbers.add(lineNo)
        }

        fun build() = SearchHit(text = text, lineNumbers = lineNumbers.toList(), totalCount = total)
    }

    /** 按总字符上限截断（保留头部，日志阅读习惯是头部在前）。 */
    private fun capByChars(lines: List<String>): Pair<String, Boolean> {
        val sb = StringBuilder()
        var truncated = false
        for (line in lines) {
            if (sb.length + line.length + 1 > MAX_PAGE_CHARS) {
                truncated = true
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString() to truncated
    }

    /** 按名称挑选文件：date 指定时匹配 `log-<date>.txt` / `trace-<date>.log` 等；缺省取最新。 */
    fun pickFile(files: List<File>, date: String?): File? {
        if (files.isEmpty()) return null
        if (date.isNullOrBlank()) return files.last()
        return files.firstOrNull { it.name.contains(date) } ?: files.last()
    }
}
