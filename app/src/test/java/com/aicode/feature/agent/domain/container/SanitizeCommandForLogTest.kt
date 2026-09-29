package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令正文进入单行日志前的处理。
 *
 * 存在两个约束，缺一都会让排查被日志本身误导：
 * 1) **必须单行**——多行脚本/heredoc 会把一条日志拆成几十行，被拆出的行不像日志行，
 *    grep 会把它当日志匹配到（曾据此得出「某模块报了 40 次错」的错误结论）。
 * 2) **必须保留下头部与截断标记**——只截不留量，就无法判断命令是否被完整记录过。
 */
class SanitizeCommandForLogTest {

    @Test
    fun multiLineCommand_isFlattenedToOneLine() {
        val command = "cat > /tmp/a.py <<'PY'\nprint(1)\nPY\necho done"

        val sanitized = sanitizeCommandForLog(command)

        assertFalse("不得残留换行，否则一条日志会被拆成多行", sanitized.contains("\n"))
        assertFalse("CRLF 也必须归一", sanitized.contains("\r"))
        assertTrue("内容应仍在，只是折叠为一行", sanitized.contains("print(1)"))
        assertTrue(sanitized.contains(" ⏎ "))
    }

    @Test
    fun crlf_isNormalized() {
        val sanitized = sanitizeCommandForLog("line1\r\nline2")

        assertFalse(sanitized.contains("\r"))
        assertFalse(sanitized.contains("\n"))
    }

    @Test
    fun longCommand_isTruncatedWithSizeMarker() {
        val command = "x".repeat(2_000)

        val sanitized = sanitizeCommandForLog(command)

        assertTrue("应保留可定位的头部", sanitized.startsWith("x".repeat(400)))
        assertTrue("应标明原始长度，便于判断是否被截断", sanitized.contains("共2000字符"))
        assertTrue("单行长度必须有界", sanitized.length < 450)
    }

    @Test
    fun shortCommand_isLeftIntact() {
        val sanitized = sanitizeCommandForLog("ls -la ~/workspace")

        assertEquals("ls -la ~/workspace", sanitized)
    }

    @Test
    fun secrets_areStillRedacted_includingInMultiLine() {
        // 折叠与截断不得破坏既有脱敏语义：多行命令里的凭据同样要打码。
        val sanitized = sanitizeCommandForLog(
            "export API_KEY=sk-real\ncurl -H 'Authorization: Bearer tok'"
        )

        assertFalse("凭据明文不得进日志", sanitized.contains("sk-real"))
        assertFalse(sanitized.contains("Bearer tok"))
        assertTrue(sanitized.contains("***"))
        assertFalse(sanitized.contains("\n"))
    }
}
