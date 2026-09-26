package com.aicode.feature.agent.domain.mcp

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFrameReaderTest {

    private fun read(text: String, limit: Int = 4 * 1024 * 1024): FrameRead =
        McpFrameReader.readFrame(StringReader(text), limit)

    @Test
    fun readsSingleLine() {
        val r = read("{\"id\":1}\n")
        assertEquals(FrameRead.Ok("{\"id\":1}"), r)
    }

    @Test
    fun stripsTrailingCarriageReturn() {
        val r = read("{\"id\":1}\r\n")
        assertEquals(FrameRead.Ok("{\"id\":1}"), r)
    }

    @Test
    fun readsLineWithoutTrailingNewline() {
        assertEquals(FrameRead.Ok("tail"), read("tail"))
    }

    @Test
    fun emptyInputIsEof() {
        assertSame(FrameRead.Eof, read(""))
    }

    @Test
    fun sequentialFramesReadInOrder() {
        val reader = StringReader("first\nsecond\n")
        assertEquals(FrameRead.Ok("first"), McpFrameReader.readFrame(reader, 100))
        assertEquals(FrameRead.Ok("second"), McpFrameReader.readFrame(reader, 100))
        assertSame(FrameRead.Eof, McpFrameReader.readFrame(reader, 100))
    }

    @Test
    fun oversizeLineIsFlaggedAndStreamStaysAligned() {
        val reader = StringReader("AAAAA\nok\n")
        // limit=3 → 首帧超限被标记，且已丢弃至行尾；下一帧仍能正确读取。
        assertSame(FrameRead.Oversize, McpFrameReader.readFrame(reader, 3))
        assertEquals(FrameRead.Ok("ok"), McpFrameReader.readFrame(reader, 3))
    }

    @Test
    fun exactlyAtLimitIsNotOversize() {
        assertEquals(FrameRead.Ok("abc"), read("abc\n", limit = 3))
    }

    @Test
    fun oneOverLimitIsOversize() {
        assertSame(FrameRead.Oversize, read("abcd\n", limit = 3))
    }

    @Test
    fun oversizeAtEofIsFlagged() {
        assertSame(FrameRead.Oversize, read("abcd", limit = 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        read("x\n", limit = 0)
    }

    @Test
    fun blankLineIsReadAsEmptyNotEof() {
        // 空行是合法帧（内容为空），交给上层 isBlank 跳过；不应误判为 Eof。
        val r = read("\n")
        assertTrue(r is FrameRead.Ok && r.line.isEmpty())
    }
}
