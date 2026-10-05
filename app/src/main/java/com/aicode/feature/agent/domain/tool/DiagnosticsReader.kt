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

    /** search 命中的最大条数。 */
    const val MAX_SEARCH_HITS = 200

    data class Page(
        val content: String,
        val totalLines: Int,
        val startLine: Int,
        val endLine: Int,
        val hasMore: Boolean,
        val truncatedByChars: Boolean,
    )

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
     * 只属于 [sessionId] 这个会话的模型交互日志，**当前文件在前、轮转归档在后**。
     *
     * 会话 id 的净化规则必须与 [com.aicode.core.util.AILogger] 落盘时一致（非 `[A-Za-z0-9_-]`
     * 一律换成 `_`），否则会漏匹配。返回顺序即优先级：调用方取 `firstOrNull()` 才是正在写的
     * 那份日志——按名字升序时归档 `session-x.log.1` 排在当前 `session-x.log` 之后，直接取末尾会读到归档。
     */
    fun sessionLogFiles(root: File, sessionId: String): List<File> {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val all = listFiles(root)
        val current = all.firstOrNull { it.name == "session-$safeId.log" }
        val rotated = all.firstOrNull { it.name == "session-$safeId.log.1" }
        return listOfNotNull(current, rotated)
    }

    /**
     * 读取一页：以文件末尾 [offsetFromEnd] 行为基准，向前取 [lines] 行。
     * 流式扫描、只保留窗口大小的环形缓冲，20MB 的 ai-logs 也不会整份进内存。
     */
    fun readWindow(file: File, offsetFromEnd: Int, lines: Int): Page? {
        if (!file.isFile || !file.canRead()) return null
        val keep = (lines.coerceAtLeast(1) + offsetFromEnd.coerceAtLeast(0)).coerceAtMost(100_000)
        val ring = ArrayDeque<String>()
        var total = 0
        runCatching {
            file.forEachLine { raw ->
                total++
                ring.addLast(raw.take(MAX_LINE_CHARS))
                while (ring.size > keep) ring.removeFirst()
            }
        }.onFailure { return null }

        if (total == 0) {
            return Page(content = "", totalLines = 0, startLine = 0, endLine = 0, hasMore = false, truncatedByChars = false)
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
        )
    }

    /**
     * 子串搜索（大小写不敏感），返回 (行号, 行内容, 是否命中行) 附上下各一行上下文。
     * 命中数超过 [MAX_SEARCH_HITS] 即停止；行内容先按 [MAX_LINE_CHARS] 截断。
     */
    fun search(file: File, query: String, contextLines: Int = 1): List<String>? {
        if (!file.isFile || !file.canRead()) return null
        val needle = query.lowercase()
        val all = ArrayList<String>(4096)
        runCatching {
            file.forEachLine { raw -> all.add(raw.take(MAX_LINE_CHARS)) }
        }.onFailure { return null }

        val hits = ArrayList<String>()
        var i = 0
        var matched = 0
        while (i < all.size && matched < MAX_SEARCH_HITS) {
            if (all[i].lowercase().contains(needle)) {
                matched++
                val from = (i - contextLines).coerceAtLeast(0)
                val to = (i + contextLines).coerceAtMost(all.size - 1)
                for (j in from..to) {
                    val mark = if (j == i) ">" else " "
                    hits.add("$mark ${j + 1}: ${all[j]}")
                }
                if (to > i) i = to + 1 else i++
            } else {
                i++
            }
        }
        return hits
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
