package com.aicode.feature.agent.domain.tool.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditMatcherTest {

    private fun found(content: String, old: String, allowFuzzy: Boolean = true): EditMatcher.Result.Found =
        EditMatcher.findUnique(content, old, allowFuzzy) as EditMatcher.Result.Found

    // ── 精确匹配 ─────────────────────────────────────────────────────────

    @Test
    fun exactMatchReturnsOriginalRange() {
        val content = "fun a() {}\nfun b() {}\n"
        val r = found(content, "fun b() {}")
        assertEquals(EditMatcher.Level.EXACT, r.level)
        assertEquals("fun b() {}", r.match.text)
        assertEquals("fun b() {}", content.substring(r.match.start, r.match.end))
    }

    @Test
    fun exactMatchRejectsWhenAmbiguous() {
        val content = "x\nx\n"
        val r = EditMatcher.findUnique(content, "x")
        assertTrue(r is EditMatcher.Result.Ambiguous)
        assertEquals(2, (r as EditMatcher.Result.Ambiguous).count)
    }

    @Test
    fun missingTextIsNotFound() {
        assertTrue(EditMatcher.findUnique("abc\n", "zzz") is EditMatcher.Result.NotFound)
    }

    // ── 弯引号归一 ───────────────────────────────────────────────────────

    @Test
    fun curlyQuotesAreToleratedAndOriginalPreserved() {
        val content = "val s = \u201Chello\u201D\n"
        val r = found(content, "val s = \"hello\"")
        assertEquals(EditMatcher.Level.QUOTE_NORMALIZED, r.level)
        // 命中的必须是原文区间（保留弯引号），不能用归一化文本回写
        assertEquals("val s = \u201Chello\u201D", r.match.text)
    }

    // ── 行号前缀剥离 ─────────────────────────────────────────────────────

    @Test
    fun lineNumberPrefixIsStripped() {
        val content = "    return total\n"
        val r = found(content, "42:     return total")
        assertEquals(EditMatcher.Level.LINE_NUMBER_STRIPPED, r.level)
        assertEquals("    return total", r.match.text)
    }

    // ── 转义与 Unicode 还原 ──────────────────────────────────────────────

    @Test
    fun literalEscapesAreDecoded() {
        val content = "a\n\tb\n"
        val r = found(content, "a\\n\\tb")
        assertEquals(EditMatcher.Level.ESCAPE_NORMALIZED, r.level)
        assertEquals("a\n\tb", r.match.text)
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        val content = "name = \"中文\"\n"
        val r = found(content, "name = \"\\u4e2d\\u6587\"")
        assertEquals(EditMatcher.Level.UNICODE_ESCAPE_NORMALIZED, r.level)
        assertEquals("name = \"中文\"", r.match.text)
    }

    // ── 行级宽匹配 ───────────────────────────────────────────────────────

    @Test
    fun lineTrimmedMatchesIgnoringPerLineWhitespace() {
        val content = "    val x = 1\n    val y = 2\n"
        val r = found(content, "val x = 1\nval y = 2")
        assertEquals(EditMatcher.Level.LINE_TRIMMED, r.level)
        assertEquals("    val x = 1\n    val y = 2", r.match.text)
    }

    @Test
    fun indentationDifferenceIsAbsorbed() {
        val content = "\t\tif (a) {\n\t\t    b()\n\t\t}\n"
        val r = found(content, "if (a) {\n    b()\n}")
        assertEquals(EditMatcher.Level.LINE_TRIMMED, r.level)
        assertEquals("\t\tif (a) {\n\t\t    b()\n\t\t}", r.match.text)
    }

    @Test
    fun blockAnchorToleratesSmallInnerDifferences() {
        val content = "start-marker\nfunction alpha() {\n  doSomething();\n}\nend-marker\n"
        val r = found(content, "start-marker\nfunction alphA() {\n  doSomething();\n}\nend-marker")
        assertEquals(EditMatcher.Level.BLOCK_ANCHOR, r.level)
        assertTrue(r.match.text.startsWith("start-marker"))
        assertTrue(r.match.text.endsWith("end-marker"))
    }

    // ── 宽匹配在 replace_all 场景被禁用 ──────────────────────────────────

    @Test
    fun fuzzyDisabledFallsBackToExactOnly() {
        val content = "\u201Chello\u201D\n"
        assertTrue(
            EditMatcher.findUnique(content, "\"hello\"", allowFuzzy = false) is EditMatcher.Result.NotFound
        )
    }

    @Test
    fun findAllExactReturnsEveryOccurrence() {
        val content = "a\nb\na\nb\na\n"
        val all = EditMatcher.findAllExact(content, "a")
        assertEquals(3, all.size)
        all.forEach { assertEquals("a", it.text) }
    }

    @Test
    fun findAllExactIgnoresFuzzy() {
        val content = "val s = \u201Cx\u201D\n"
        assertTrue(EditMatcher.findAllExact(content, "\"x\"").isEmpty())
    }

    // ── 行索引边界 ───────────────────────────────────────────────────────

    @Test
    fun lineIndexHandlesLastLineWithoutNewline() {
        val index = EditMatcher.LineIndex("one\ntwo")
        assertEquals(2, index.lines.size)
        assertEquals(0, index.lineStart(0))
        assertEquals(4, index.lineStart(1))
        assertEquals(7, index.lineEndExclusive(1))
    }
}
