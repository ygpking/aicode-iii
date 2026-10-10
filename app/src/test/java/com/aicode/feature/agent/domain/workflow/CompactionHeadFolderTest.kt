package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩输入头部折叠的回归用例。
 *
 * 核心回归：旧实现 `dropWhile { it !is UserMessage }` 会把「整段无 user 的头部」
 * 丢光——无报错、无日志，该段要么进不了摘要（模型永久失忆），要么让摘要请求
 * 只剩一条指令、模型凭空编造。两条路径都在这里锁死。
 */
class CompactionHeadFolderTest {

    private fun user(content: String) = AgentMessage.UserMessage(content = content)

    private fun assistant(content: String, tools: List<String> = emptyList()) =
        AgentMessage.AssistantMessage(
            content = content,
            toolCalls = tools.map { ToolCall(id = "call_$it", name = it, arguments = emptyMap()) },
        )

    private fun tool(name: String, result: String) =
        AgentMessage.ToolResultMessage(toolName = name, result = result)

    @Test
    fun `头部为空时不折叠`() {
        assertNull(CompactionHeadFolder.fold(emptyList()))
    }

    @Test
    fun `整段无 user 的头部不再丢光`() {
        // 真机形态：tail 侧配对保护把 assistant(toolCalls) 拉进 tail，
        // 下次压缩的 head 正好以它开头，且整段都是工具轮次。
        val leading = listOf(
            assistant("", listOf("readFile")),
            tool("readFile", "内容 A"),
            assistant("", listOf("Bash")),
            tool("Bash", "输出 B"),
        )
        val folded = CompactionHeadFolder.fold(leading)
        assertTrue("折叠结果不应为空（旧实现此处返回 null/空）", folded != null)
        val text = folded!!.content
        assertTrue("应保留工具名，实测=$text", text.contains("readFile"))
        assertTrue("应保留工具名，实测=$text", text.contains("Bash"))
        assertTrue("应保留结果内容，实测=$text", text.contains("内容 A"))
        assertTrue("应保留结果内容，实测=$text", text.contains("输出 B"))
    }

    @Test
    fun `折叠产物是一条 user 消息`() {
        val folded = CompactionHeadFolder.fold(listOf(assistant("说明"), tool("Bash", "ok")))
        assertTrue("折叠结果必须是 UserMessage（各 provider 要求首条为 user）", folded is AgentMessage.UserMessage)
    }

    @Test
    fun `助手消息含调用参数时保留参数`() {
        val withArgs = AgentMessage.AssistantMessage(
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "c1",
                    name = "editFile",
                    arguments = mapOf("path" to JsonPrimitive("Foo.kt")),
                ),
            ),
        )
        val text = CompactionHeadFolder.fold(listOf(withArgs))!!.content
        assertTrue("应保留参数内容，实测=$text", text.contains("Foo.kt"))
    }

    @Test
    fun `无正文无调用的助手消息给占位而非留空`() {
        val text = CompactionHeadFolder.fold(listOf(assistant("")))!!.content
        assertTrue("不应产出空串（会让该条在摘要材料里消失），实测=$text", text.isNotBlank())
    }

    @Test
    fun `超长工具结果被截断`() {
        val long = "x".repeat(10_000)
        val text = CompactionHeadFolder.fold(listOf(tool("Bash", long)))!!.content
        assertTrue("应截断超长结果，实测长度=${text.length}", text.length < 10_000)
        assertTrue("截断后仍应保留开头内容", text.contains("xxxx"))
    }

    @Test
    fun `单条 user 头部也能折叠（调用方按需使用）`() {
        val text = CompactionHeadFolder.fold(listOf(user("原始请求")))!!.content
        assertEquals("原始请求", text)
    }
}
