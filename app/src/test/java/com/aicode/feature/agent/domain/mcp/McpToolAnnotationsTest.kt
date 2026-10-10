package com.aicode.feature.agent.domain.mcp

import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MCP 工具 `annotations.readOnlyHint` 的解析，以及它到并行调度能力的映射。
 *
 * 背景：多会话并行时工具调用互相卡，根因之一是批调度把「无能力声明」的 MCP 工具一律串行。
 * MCP 规范在 tools/list 的每个工具上提供 `annotations` 作为行为提示，其中
 * `readOnlyHint == true` 是唯一能证明「无副作用」的协议级信号——本测试锁定：
 * 只有**显式 true** 才并行；缺失/false 一律回退串行（fail-closed，绝不臆测只读）。
 */
class McpToolAnnotationsTest {

    /** 与线上传输层一致的 Json 配置（StdioTransport / StreamableHttpTransport）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun descriptorOf(rawToolJson: String): McpToolDescriptor {
        val payload = """{"tools":[$rawToolJson]}"""
        return json.decodeFromString<McpToolsListResult>(payload).tools.single()
    }

    // ---- 解析层 ----

    @Test
    fun readOnlyHintTrue_isParsed() {
        val d = descriptorOf("""{"name":"search","annotations":{"readOnlyHint":true}}""")
        assertEquals(true, d.annotations?.readOnlyHint)
    }

    @Test
    fun readOnlyHintFalse_isParsedAsFalse_notNull() {
        // false 与「缺失」必须可区分：false 是服务端明确声明「有副作用」。
        val d = descriptorOf("""{"name":"write","annotations":{"readOnlyHint":false}}""")
        assertEquals(false, d.annotations?.readOnlyHint)
    }

    @Test
    fun annotationsAbsent_decodesToNull() {
        val d = descriptorOf("""{"name":"plain"}""")
        assertNull(d.annotations)
    }

    @Test
    fun unknownAnnotationFields_areIgnored() {
        // 规范未来可能加字段；ignoreUnknownKeys 下不能整条 tools/list 解析失败。
        val d = descriptorOf(
            """{"name":"x","annotations":{"readOnlyHint":true,"title":"X","futureField":123}}"""
        )
        assertEquals(true, d.annotations?.readOnlyHint)
    }

    // ---- 能力映射（McpTool.capabilities 的判据）----
    // 直接对判据表达式求值：与 McpTool 中 `descriptor.annotations?.readOnlyHint == true` 一致。

    private fun capabilitiesFor(d: McpToolDescriptor): Set<ToolCapability> =
        if (d.annotations?.readOnlyHint == true) {
            setOf(ToolCapability.READ_WORKSPACE)
        } else {
            setOf(ToolCapability.EXTERNAL_TOOL)
        }

    @Test
    fun readOnlyHintTrue_mapsToReadWorkspace() {
        val d = descriptorOf("""{"name":"search","annotations":{"readOnlyHint":true}}""")
        assertEquals(setOf(ToolCapability.READ_WORKSPACE), capabilitiesFor(d))
    }

    @Test
    fun missingHint_staysExternalTool_conservative() {
        val d = descriptorOf("""{"name":"unknown"}""")
        assertEquals(setOf(ToolCapability.EXTERNAL_TOOL), capabilitiesFor(d))
    }

    @Test
    fun readOnlyHintFalse_staysExternalTool() {
        // 明确声明有副作用 → 必须串行。
        val d = descriptorOf("""{"name":"write","annotations":{"readOnlyHint":false}}""")
        assertEquals(setOf(ToolCapability.EXTERNAL_TOOL), capabilitiesFor(d))
    }

    @Test
    fun readOnlyHintTrue_survivesToolBatchSchedulerClassification() {
        // 端到端：只读 MCP 工具与内置只读工具走同一条只读判定 → 允许并行。
        val d = descriptorOf("""{"name":"readThing","annotations":{"readOnlyHint":true}}""")
        assertEquals(
            ToolMutation.READ_ONLY,
            classifyToolMutation("mcp__srv__readThing__ab12cd34", capabilitiesFor(d)),
        )
    }

    @Test
    fun unspecifiedHint_mcpToolStaysSerialThroughScheduler() {
        val d = descriptorOf("""{"name":"doThing"}""")
        assertEquals(
            ToolMutation.FILE_MUTATING,
            classifyToolMutation("mcp__srv__doThing__ab12cd34", capabilitiesFor(d)),
        )
    }
}
