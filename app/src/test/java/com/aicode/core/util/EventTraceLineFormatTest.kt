package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹写入的**单行格式契约**：一条记录必须占一行。
 *
 * 背景（真机实测）：`write()` 直接把 detail 拼进行格式，而 detail 可能来自工具输出
 * （如 HTTP 504 响应体），若含换行就把一条记录撑成多行——后续行没有时间戳/会话前缀，
 * 看着像格式损坏，`grep` 与按行解析都会出错。
 *
 * 这里直接测纯函数，不依赖 Robolectric：问题在字符串处理层，无需构造 Android 环境。
 */
class EventTraceLineFormatTest {

    @Test
    fun plainTextUnchanged() {
        assertEquals("正常单行文本", "正常单行文本", EventTrace.foldNewlines("正常单行文本"))
    }

    @Test
    fun lfIsFolded() {
        assertEquals("a\\nb", EventTrace.foldNewlines("a\nb"))
    }

    @Test
    fun crlfIsFoldedAsOneMarker() {
        // CRLF 必须整体折叠成一个标记，否则会残留一个空行
        assertEquals("a\\nb", EventTrace.foldNewlines("a\r\nb"))
    }

    @Test
    fun loneCrIsFolded() {
        assertEquals("a\\nb", EventTrace.foldNewlines("a\rb"))
    }

    @Test
    fun multiLineHtmlCollapsesToSingleLine() {
        // 复刻真机上那条把轨迹撑成 5 行的输入
        val html = """
            <html>
            <head><title>504 Gateway Time-out</title></head>
            <body bgcolor="white">
            </body>
            </html>
        """.trimIndent()

        val folded = EventTrace.foldNewlines(html)

        assertFalse("折叠后不应残留任何换行", folded.contains('\n') || folded.contains('\r'))
        assertTrue("应保留「此处原本断行」的痕迹", folded.contains("\\n"))
    }

    @Test
    fun foldedLineStaysSingleLineWhenEmbedded() {
        // 模拟 write() 的拼接：fold 之后整条记录只应有一个换行（结尾那个）
        val detail = EventTrace.foldNewlines("第一行\n第二行\n第三行")
        val line = "2026-10-02 10:00:00.000  s=abc12345 t1 #1  TOOL  $detail\n"

        assertEquals("整条记录应只占一行", 1, line.count { it == '\n' })
        assertTrue("结尾之外不应有换行", line.trimEnd('\n').let { !it.contains('\n') })
    }

    @Test
    fun emptyAndBlankHandled() {
        assertEquals("", EventTrace.foldNewlines(""))
        assertEquals("\\n", EventTrace.foldNewlines("\n"))
    }
}
