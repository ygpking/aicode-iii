package com.aicode.feature.agent.domain.tool

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticsReader] 的分页与搜索边界。纯文件逻辑，用临时目录造真实文件，不 mock。
 */
class DiagnosticsReaderTest {

    private fun tempFile(name: String, content: String): File {
        val dir = Files.createTempDirectory("diag-reader").toFile()
        return File(dir, name).apply { writeText(content) }
    }

    private fun numbered(count: Int): String = (1..count).joinToString("\n") { "line-$it" }

    // ── resolveRoot / listFiles ──────────────────────────────────────

    @Test
    fun resolveRoot_mapsKnownKindsOnly() {
        val base = File("/tmp/x")
        assertEquals(File(base, "logs"), DiagnosticsReader.resolveRoot(base, "app"))
        assertEquals(File(base, "traces"), DiagnosticsReader.resolveRoot(base, "trace"))
        assertEquals(File(base, "ai-logs"), DiagnosticsReader.resolveRoot(base, "ai"))
        assertNull(DiagnosticsReader.resolveRoot(base, "nope"))
    }

    @Test
    fun listFiles_filtersPrefixesAndMissingDirIsEmpty() {
        val dir = Files.createTempDirectory("diag-list").toFile()
        File(dir, "log-2026-10-02.txt").writeText("a")
        File(dir, "trace-2026-10-02.log").writeText("b")
        File(dir, "session-abc.log").writeText("c")
        File(dir, "other.txt").writeText("d")
        File(dir, "subdir").mkdirs()

        val names = DiagnosticsReader.listFiles(dir).map { it.name }
        assertEquals(listOf("log-2026-10-02.txt", "session-abc.log", "trace-2026-10-02.log"), names)

        assertTrue(DiagnosticsReader.listFiles(File(dir, "missing")).isEmpty())
    }

    // ── readWindow ───────────────────────────────────────────────────

    @Test
    fun readWindow_emptyFileReturnsEmptyPage() {
        val f = tempFile("trace-x.log", "")
        val page = DiagnosticsReader.readWindow(listOf(f), offsetFromEnd = 0, lines = 10)!!
        assertEquals("", page.content)
        assertEquals(0, page.totalLines)
        assertTrue(!page.hasMore)
    }

    @Test
    fun readWindow_takesTailWithMoreFlag() {
        val f = tempFile("trace-x.log", numbered(10))
        val page = DiagnosticsReader.readWindow(listOf(f), offsetFromEnd = 0, lines = 3)!!

        assertEquals("line-8\nline-9\nline-10", page.content)
        assertEquals(10, page.totalLines)
        assertEquals(8, page.startLine)
        assertEquals(10, page.endLine)
        assertTrue(page.hasMore)
    }

    @Test
    fun readWindow_offsetFromEndPagesBackwards() {
        val f = tempFile("trace-x.log", numbered(10))
        val page = DiagnosticsReader.readWindow(listOf(f), offsetFromEnd = 2, lines = 3)!!

        assertEquals("line-6\nline-7\nline-8", page.content)
        assertEquals(6, page.startLine)
        assertEquals(8, page.endLine)
        assertTrue(page.hasMore)
    }

    @Test
    fun readWindow_linesLargerThanFileReturnsAll() {
        val f = tempFile("trace-x.log", numbered(3))
        val page = DiagnosticsReader.readWindow(listOf(f), offsetFromEnd = 0, lines = 100)!!

        assertEquals("line-1\nline-2\nline-3", page.content)
        assertEquals(1, page.startLine)
        assertEquals(3, page.endLine)
        assertTrue(!page.hasMore)
    }

    @Test
    fun readWindow_missingFileReturnsNull() {
        assertNull(DiagnosticsReader.readWindow(listOf(File("/tmp/does-not-exist-diag.log")), 0, 5))
        assertNull(DiagnosticsReader.readWindow(emptyList(), 0, 5))
    }

    @Test
    fun readWindow_truncatesOverlongLine() {
        val long = "x".repeat(DiagnosticsReader.MAX_LINE_CHARS + 500)
        val f = tempFile("log-x.txt", long)
        val page = DiagnosticsReader.readWindow(listOf(f), 0, 5)!!
        assertEquals(DiagnosticsReader.MAX_LINE_CHARS, page.content.length)
    }

    @Test
    fun readWindow_mergesRotatedAndCurrentAsOneStream() {
        val dir = Files.createTempDirectory("diag-rot").toFile()
        val rotated = File(dir, "session-aaa.log.1").apply { writeText("old-1\nold-2") }
        val current = File(dir, "session-aaa.log").apply { writeText("new-1\nnew-2") }

        // 归档在前、当前在后，行号在整个流内连续；尾巴取到的是当前文件的内容。
        val tail = DiagnosticsReader.readWindow(listOf(rotated, current), 0, 2)!!
        assertEquals("new-1\nnew-2", tail.content)
        assertEquals(4, tail.totalLines)
        assertEquals(3, tail.startLine)

        // 把 offset 推到跨越文件边界，能读到归档里的内容（这正是原来丢失的那段）。
        val across = DiagnosticsReader.readWindow(listOf(rotated, current), 1, 2)!!
        assertEquals("old-2\nnew-1", across.content)
        assertEquals(2, across.startLine)
        assertEquals(3, across.endLine)
    }

    // ── search ───────────────────────────────────────────────────────

    @Test
    fun search_dedupesByContentAndReportsAllLineNumbers() {
        // 同一内容在流里出现两次（行 2 与行 4），只应给一条并列出两个行号。
        val f = tempFile("trace-x.log", "alpha\nBeta\nGAMMA\nBeta")
        val hits = DiagnosticsReader.search(listOf(f), "beta")!!.first

        assertEquals(1, hits.size)
        assertEquals("Beta", hits[0].text)
        assertEquals(listOf(2, 4), hits[0].lineNumbers)
        assertEquals(2, hits[0].totalCount)
        assertEquals("[x2] 行号 2,4: Beta", hits[0].render())
    }

    @Test
    fun search_stripsLeadingTimestampBeforeComparing() {
        // logs/ 与 traces/ 每行带时间戳；同一逻辑内容不该因时刻不同被当成两条。
        val f = tempFile("log-x.txt", "2026-10-06 09:00:01.1 DEBUG [T] same\n2026-10-06 09:05:02.9 DEBUG [T] same")
        val hits = DiagnosticsReader.search(listOf(f), "same")!!.first
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].totalCount)
    }

    @Test
    fun search_capsRecordedLineNumbersButKeepsTotal() {
        val cap = DiagnosticsReader.MAX_HIT_LINE_NUMBERS
        val f = tempFile("trace-cap.log", (1..cap + 5).joinToString("\n") { "dup target" })
        val hits = DiagnosticsReader.search(listOf(f), "target")!!.first

        assertEquals(1, hits.size)
        assertEquals(cap + 5, hits[0].totalCount)
        assertEquals(cap, hits[0].lineNumbers.size)
        assertTrue(hits[0].render().contains("共${cap + 5}处"))
    }

    @Test
    fun search_noMatchReturnsEmptyList() {
        val f = tempFile("trace-x.log", numbered(5))
        assertTrue(DiagnosticsReader.search(listOf(f), "zzz-not-there")!!.first.isEmpty())
    }

    @Test
    fun search_reportsTruncationWhenHitCapReached() {
        // 超过 MAX_SEARCH_HITS 触顶后必须把 halted 传出去：否则 hits/occurrences 被当全量统计。
        val f = tempFile(
            "trace-cap.log",
            (1..DiagnosticsReader.MAX_SEARCH_HITS + 1).joinToString("\n") { "unique-$it hit" }
        )
        val (hits, truncated) = DiagnosticsReader.search(listOf(f), "hit")!!
        assertEquals(DiagnosticsReader.MAX_SEARCH_HITS, hits.size)
        assertTrue(truncated)
    }

    @Test
    fun search_spansRotatedAndCurrentWithContinuousLineNumbers() {
        val dir = Files.createTempDirectory("diag-rot-search").toFile()
        val rotated = File(dir, "session-aaa.log.1").apply { writeText("old target\nold-2") }
        val current = File(dir, "session-aaa.log").apply { writeText("new-1\nnew target") }

        val hits = DiagnosticsReader.search(listOf(rotated, current), "target")!!.first
        // 归档里的命中是第 1 行，当前文件里的命中接着数第 4 行——行号跨文件连续。
        assertEquals(2, hits.size)
        assertEquals(listOf(1), hits[0].lineNumbers)
        assertEquals(listOf(4), hits[1].lineNumbers)
    }

    @Test
    fun search_reportsMissingFileAsNull() {
        assertNull(DiagnosticsReader.search(listOf(File("/tmp/does-not-exist-diag.log")), "x"))
        assertNull(DiagnosticsReader.search(emptyList(), "x"))
    }

    // ── sessionLogFiles（会话隔离） ────────────────────────────────

    @Test
    fun sessionLogFiles_onlyCurrentSession_rotatedFirstForStreamOrder() {
        val dir = Files.createTempDirectory("diag-sess").toFile()
        File(dir, "session-aaa.log").writeText("mine")
        File(dir, "session-aaa.log.1").writeText("mine-rotated")
        File(dir, "session-bbb.log").writeText("other")
        File(dir, "session-ccc.log").writeText("other2")

        val names = DiagnosticsReader.sessionLogFiles(dir, "aaa").map { it.name }
        // 只含本会话。顺序即读取顺序：轮转归档是先前写下的，故在前；当前文件在最后
        // （`pickFile` 取 `files.last()` 即正在写的那份）。
        assertEquals(listOf("session-aaa.log.1", "session-aaa.log"), names)
    }

    @Test
    fun sessionLogFiles_normalizesIdLikeAILogger() {
        val dir = Files.createTempDirectory("diag-sess2").toFile()
        File(dir, "session-a_b_c.log").writeText("x")

        // AILogger 落盘时把非 [A-Za-z0-9_-] 一律换成 "_"，故 `a:b/c` 对应 `a_b_c`。
        assertEquals(listOf("session-a_b_c.log"), DiagnosticsReader.sessionLogFiles(dir, "a:b/c").map { it.name })
    }

    @Test
    fun sessionLogFiles_unknownSessionIsEmpty() {
        val dir = Files.createTempDirectory("diag-sess3").toFile()
        File(dir, "session-aaa.log").writeText("mine")

        assertTrue(DiagnosticsReader.sessionLogFiles(dir, "zzz").isEmpty())
    }

    // ── pickFile ─────────────────────────────────────────────────────

    @Test
    fun pickFile_matchesDateElseFallsBackToLast() {
        val dir = Files.createTempDirectory("diag-pick").toFile()
        val a = File(dir, "log-2026-10-01.txt").apply { writeText("a") }
        val b = File(dir, "log-2026-10-02.txt").apply { writeText("b") }
        val files = DiagnosticsReader.listFiles(dir)

        assertEquals(a, DiagnosticsReader.pickFile(files, "2026-10-01"))
        assertEquals(b, DiagnosticsReader.pickFile(files, null))
        assertEquals(b, DiagnosticsReader.pickFile(files, "1999-01-01"))
        assertNull(DiagnosticsReader.pickFile(emptyList(), "2026-10-01"))
    }
}
