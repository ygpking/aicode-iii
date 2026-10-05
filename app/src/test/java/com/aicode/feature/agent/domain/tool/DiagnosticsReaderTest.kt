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
        val page = DiagnosticsReader.readWindow(f, offsetFromEnd = 0, lines = 10)!!
        assertEquals("", page.content)
        assertEquals(0, page.totalLines)
        assertTrue(!page.hasMore)
    }

    @Test
    fun readWindow_takesTailWithMoreFlag() {
        val f = tempFile("trace-x.log", numbered(10))
        val page = DiagnosticsReader.readWindow(f, offsetFromEnd = 0, lines = 3)!!

        assertEquals("line-8\nline-9\nline-10", page.content)
        assertEquals(10, page.totalLines)
        assertEquals(8, page.startLine)
        assertEquals(10, page.endLine)
        assertTrue(page.hasMore)
    }

    @Test
    fun readWindow_offsetFromEndPagesBackwards() {
        val f = tempFile("trace-x.log", numbered(10))
        val page = DiagnosticsReader.readWindow(f, offsetFromEnd = 2, lines = 3)!!

        assertEquals("line-6\nline-7\nline-8", page.content)
        assertEquals(6, page.startLine)
        assertEquals(8, page.endLine)
        assertTrue(page.hasMore)
    }

    @Test
    fun readWindow_linesLargerThanFileReturnsAll() {
        val f = tempFile("trace-x.log", numbered(3))
        val page = DiagnosticsReader.readWindow(f, offsetFromEnd = 0, lines = 100)!!

        assertEquals("line-1\nline-2\nline-3", page.content)
        assertEquals(1, page.startLine)
        assertEquals(3, page.endLine)
        assertTrue(!page.hasMore)
    }

    @Test
    fun readWindow_missingFileReturnsNull() {
        assertNull(DiagnosticsReader.readWindow(File("/tmp/does-not-exist-diag.log"), 0, 5))
    }

    @Test
    fun readWindow_truncatesOverlongLine() {
        val long = "x".repeat(DiagnosticsReader.MAX_LINE_CHARS + 500)
        val f = tempFile("log-x.txt", long)
        val page = DiagnosticsReader.readWindow(f, 0, 5)!!
        assertEquals(DiagnosticsReader.MAX_LINE_CHARS, page.content.length)
    }

    // ── search ───────────────────────────────────────────────────────

    @Test
    fun search_isCaseInsensitiveWithContext() {
        val f = tempFile("trace-x.log", "alpha\nBeta\nGAMMA\nbeta again")
        val hits = DiagnosticsReader.search(f, "beta")!!
        // 两处命中，各带上下各一行；命中行以 ">" 标注。
        assertTrue(hits.any { it.startsWith("> 2: Beta") })
        assertTrue(hits.any { it.startsWith("> 4: beta again") })
        assertTrue(hits.any { it.startsWith("  1: alpha") })
    }

    @Test
    fun search_noMatchReturnsEmptyList() {
        val f = tempFile("trace-x.log", numbered(5))
        assertTrue(DiagnosticsReader.search(f, "zzz-not-there")!!.isEmpty())
    }

    // ── sessionLogFiles（会话隔离） ────────────────────────────────

    @Test
    fun sessionLogFiles_onlyCurrentSession_withRotatedAfterCurrent() {
        val dir = Files.createTempDirectory("diag-sess").toFile()
        File(dir, "session-aaa.log").writeText("mine")
        File(dir, "session-aaa.log.1").writeText("mine-rotated")
        File(dir, "session-bbb.log").writeText("other")
        File(dir, "session-ccc.log").writeText("other2")

        val names = DiagnosticsReader.sessionLogFiles(dir, "aaa").map { it.name }
        // 只含本会话；当前文件在前，轮转归档在后（取 first 才是正在写的）。
        assertEquals(listOf("session-aaa.log", "session-aaa.log.1"), names)
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
