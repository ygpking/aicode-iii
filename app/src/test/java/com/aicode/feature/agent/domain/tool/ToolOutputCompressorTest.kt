package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputCompressorTest {

    @Test
    fun foldsConsecutiveIdenticalLines() {
        val text = (1..10).joinToString("\n") { "Downloading package" }
        val r = ToolOutputCompressor.compress(text)
        assertTrue(r.linesFolded > 0)
        assertTrue(r.text.contains("...[上一行重复 10 次]..."))
        assertTrue(r.text.lines().size < 10)
    }

    @Test
    fun keepsShortRepetitionBelowThreshold() {
        val text = "line\nline\nother"
        val r = ToolOutputCompressor.compress(text)
        assertEquals(0, r.linesFolded)
        assertEquals(text, r.text)
    }

    @Test
    fun stripsAnsiCodes() {
        val text = "\u001B[32mok\u001B[0m normal"
        val r = ToolOutputCompressor.compress(text)
        assertTrue(r.ansiStripped)
        assertEquals("ok normal", r.text)
    }

    @Test
    fun collapsesCarriageReturnProgress() {
        val text = "10%\r50%\r100% done"
        val r = ToolOutputCompressor.compress(text)
        assertEquals("100% done", r.text)
    }

    @Test
    fun preservesDistinctLines() {
        val text = "a\nb\nc\nd"
        val r = ToolOutputCompressor.compress(text)
        assertEquals(text, r.text)
        assertFalse(r.ansiStripped)
        assertEquals(0, r.linesFolded)
    }

    @Test
    fun emptyInputIsSafe() {
        val r = ToolOutputCompressor.compress("")
        assertEquals("", r.text)
        assertEquals(0, r.linesFolded)
        assertFalse(r.ansiStripped)
    }
}
