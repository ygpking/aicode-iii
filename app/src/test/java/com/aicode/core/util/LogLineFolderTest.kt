package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志单行化回归测试。
 *
 * 核心不变量：折叠后**不得再含任何换行**。否则被拆出的行不带时间戳前缀，
 * `grep` 会把日志内容本身当证据匹配到，进而得出错误结论（实测踩过）。
 */
class LogLineFolderTest {

    @Test
    fun foldsNewlinesIntoSeparator() {
        val folded = LogLineFolder.fold("第一行\n第二行\n第三行", 1000)

        assertEquals("第一行 ⏎ 第二行 ⏎ 第三行", folded)
        assertFalse("折叠后不得含换行", folded.contains('\n'))
    }

    @Test
    fun normalizesCrlfAndLoneCr() {
        val folded = LogLineFolder.fold("a\r\nb\rc", 1000)

        assertEquals("a ⏎ b ⏎ c", folded)
        assertFalse(folded.contains('\r'))
        assertFalse(folded.contains('\n'))
    }

    @Test
    fun keepsIndentationOfStackTrace() {
        // 缩进是堆栈层级的信息载体，折叠时必须保留（只换掉换行本身）。
        val stack = """
            java.lang.IllegalStateException: boom
            ${'\t'}at com.aicode.Foo.bar(Foo.kt:1)
            ${'\t'}at com.aicode.Main.main(Main.kt:9)
        """.trimIndent()

        val folded = LogLineFolder.fold(stack, 1000)

        assertTrue("应保留缩进", folded.contains("\tat com.aicode.Foo.bar(Foo.kt:1)"))
        assertFalse(folded.contains('\n'))
    }

    @Test
    fun returnsTextUnchangedWhenSingleLineAndShort() {
        val folded = LogLineFolder.fold("单行普通日志", 1000)

        assertEquals("单行普通日志", folded)
    }

    @Test
    fun truncatesWithVisibleMarkerAndOriginalLength() {
        val folded = LogLineFolder.fold("x".repeat(300), 100)

        assertTrue("应带截断标记", folded.contains("已截断"))
        assertTrue("标记里应含折叠后总长度", folded.contains("300"))
        // 前缀 100 字符 + 提示语
        assertTrue(folded.startsWith("x".repeat(100)))
    }

    @Test
    fun truncationMarkerCountsFoldedLengthNotOriginal() {
        // 多行文本折叠后长度会变（换行变 3 字符），标记必须报「折叠后」的长度，
        // 否则排查时对不上实际写入的字符数。
        val folded = LogLineFolder.fold("a\nb", 1)

        assertTrue(folded.contains("折叠后共5字符"))
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", LogLineFolder.fold("", 1000))
    }

    @Test
    fun foldedOutputNeverContainsLineBreaksForStackTrace() {
        val throwable = IllegalStateException("根因")
        throwable.stackTrace = arrayOf(
            StackTraceElement("com.aicode.Foo", "bar", "Foo.kt", 1),
            StackTraceElement("com.aicode.Main", "main", "Main.kt", 9)
        )
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))

        val folded = LogLineFolder.fold(sw.toString(), 4000)

        assertFalse("真实堆栈折叠后不得含换行", folded.contains('\n'))
        assertTrue(folded.contains("at com.aicode.Foo.bar"))
    }
}
