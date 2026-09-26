package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReplayPolicyTest {

    @Test
    fun readOnlyToolsAreReplaySafe() {
        assertTrue(ToolReplayPolicy.isReplaySafe("readFile"))
        assertTrue(ToolReplayPolicy.isReplaySafe("list"))
        assertTrue(ToolReplayPolicy.isReplaySafe("search"))
        assertTrue(ToolReplayPolicy.isReplaySafe("webfetch"))
    }

    @Test
    fun mutatingToolsAreNotReplaySafe() {
        assertFalse(ToolReplayPolicy.isReplaySafe("writeFile"))
        assertFalse(ToolReplayPolicy.isReplaySafe("editFile"))
        assertFalse(ToolReplayPolicy.isReplaySafe("Bash"))
        assertFalse(ToolReplayPolicy.isReplaySafe("terminal"))
    }

    @Test
    fun mcpToolsAreNeverReplaySafe() {
        // 即便 MCP 工具名恰好是只读名单里的名字，只要带 mcp_ 前缀也一律不可安全重试。
        assertFalse(ToolReplayPolicy.isReplaySafe("mcp__files__readFile"))
        assertFalse(ToolReplayPolicy.isReplaySafe("mcp__srv__list"))
    }

    @Test
    fun stubTextSignalsIncompletionAndRetryHint() {
        val safe = ToolReplayPolicy.interruptedStubText("readFile")
        assertTrue(safe.contains("readFile"))
        assertTrue(safe.contains("未完成"))
        assertTrue(safe.contains("可直接重试"))
        // 携带错误码，保持与其它工具失败同一传输格式。
        assertTrue(safe.contains("INTERRUPTED"))
    }

    @Test
    fun stubTextForMutatingToolWarnsAboutSideEffects() {
        val unsafe = ToolReplayPolicy.interruptedStubText("Bash")
        assertTrue(unsafe.contains("Bash"))
        assertTrue(unsafe.contains("副作用"))
        assertFalse(unsafe.contains("可直接重试"))
    }
}
