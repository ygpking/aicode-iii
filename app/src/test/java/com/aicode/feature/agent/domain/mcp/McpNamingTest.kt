package com.aicode.feature.agent.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 命名：拼给模型的工具名必须符合 function-calling 规范（^[a-zA-Z0-9_-]{1,64}$）。 */
class McpNamingTest {

    private val functionCallingName = Regex("[a-zA-Z0-9_-]{1,64}")

    @Test
    fun nonAsciiServerName_isSanitizedToAscii() {
        val name = McpTool.buildNamespacedName("淘宝", "query")
        assertTrue(name, functionCallingName.matches(name))
    }

    @Test
    fun nonAsciiToolName_isSanitizedToAscii() {
        val name = McpTool.buildNamespacedName("github", "查询仓库")
        assertTrue(name, functionCallingName.matches(name))
    }

    @Test
    fun asciiNames_includeReadablePrefixAndHash() {
        val name = McpTool.buildNamespacedName("github", "list-issues")
        // 恒加 hash，但保留可读前缀（便于通配匹配与人工识别）。
        assertTrue(name, name.startsWith("mcp__github__list-issues__"))
        assertTrue(name, functionCallingName.matches(name))
    }

    @Test
    fun distinctRawNames_areNotCollidedBySanitize() {
        // `a.b` 与 `a_b` 清洗后同形；hash 基于原始名，故二者不应同名（否则会静默覆盖）。
        val dotted = McpTool.buildNamespacedName("srv", "a.b")
        val underscored = McpTool.buildNamespacedName("srv", "a_b")
        assertTrue("$dotted vs $underscored", dotted != underscored)
    }

    @Test
    fun nameIsDeterministic() {
        assertEquals(
            McpTool.buildNamespacedName("srv", "tool"),
            McpTool.buildNamespacedName("srv", "tool"),
        )
    }

    @Test
    fun longName_staysWithinLimit() {
        val name = McpTool.buildNamespacedName("a".repeat(80), "b".repeat(80))
        assertEquals(64, name.length)
        assertTrue(name, functionCallingName.matches(name))
    }

    @Test
    fun serverNameValidation_rejectsNonAscii() {
        assertTrue(McpServerConfig.isValidName("github"))
        assertTrue(McpServerConfig.isValidName("my-server_1"))
        assertFalse(McpServerConfig.isValidName("淘宝"))
        assertFalse(McpServerConfig.isValidName("my server"))
        assertFalse(McpServerConfig.isValidName(""))
    }
}