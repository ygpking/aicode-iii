package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessage
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.aicode.feature.agent.data.remote.anthropic.AnthropicStopDetails
import com.aicode.feature.agent.data.remote.anthropic.AnthropicUsage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicAdapterTest {

    /** 捕获实际上送的请求体，并返回可配置的响应。 */
    private class FakeApi(var response: AnthropicMessageResponse) : AnthropicApi {
        var lastRequest: AnthropicMessageRequest? = null

        override suspend fun createMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): AnthropicMessageResponse {
            lastRequest = request
            return response
        }

        override suspend fun streamMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): ResponseBody = throw UnsupportedOperationException("本测试只覆盖非流式路径")
    }

    private fun response(
        content: List<AnthropicContentBlock> = listOf(AnthropicContentBlock(type = "text", text = "ok")),
        stopReason: String? = "end_turn",
        stopDetails: AnthropicStopDetails? = null,
        usage: AnthropicUsage = AnthropicUsage(input_tokens = 10, output_tokens = 5)
    ) = AnthropicMessageResponse(
        id = "msg_1",
        type = "message",
        role = "assistant",
        content = content,
        model = "claude-3-5-sonnet-20241022",
        stop_reason = stopReason,
        stop_sequence = null,
        stop_details = stopDetails,
        usage = usage
    )

    private fun adapter(api: FakeApi, maxOutput: Int? = null): AnthropicAdapter =
        AnthropicAdapter(api).apply {
            apiKey = "k"
            maxOutputTokens = maxOutput
        }

    private fun blocksOf(request: AnthropicMessageRequest?, role: String): List<AnthropicContentBlock> {
        val message = request?.messages?.first { it.role == role } ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return message.content as? List<AnthropicContentBlock> ?: emptyList()
    }

    private fun toolCall(id: String) = ToolCall(id = id, name = "readFile", arguments = JsonObject(emptyMap()))

    private class FakeTool(override val name: String = "readFile") : AgentTool() {
        override val description = "desc"
        override val parameters = emptyMap<String, ToolParameter>()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    @Test
    fun max_tokens_uses_model_metadata_output_limit() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = 64000).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(64000, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_falls_back_when_metadata_missing() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = null).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(16384, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_leaves_room_for_content_above_thinking_budget() = runTest {
        val api = FakeApi(response())
        // 元数据上限比思考预算还小时不能直接用：max_tokens 必须大于 budget_tokens，否则服务端 400。
        adapter(api, maxOutput = 2048).complete(
            "sys",
            listOf(AgentMessage.UserMessage(content = "hi")),
            reasoningEffort = "high"
        )

        assertEquals(8192, api.lastRequest?.thinking?.budget_tokens)
        assertEquals(8192 + 4096, api.lastRequest?.max_tokens)
    }

    @Test
    fun thinking_and_redacted_blocks_are_snapshotted_in_order() = runTest {
        val api = FakeApi(
            response(
                content = listOf(
                    AnthropicContentBlock(type = "thinking", thinking = "先想想", signature = "sig-a"),
                    AnthropicContentBlock(type = "redacted_thinking", data = "opaque-blob"),
                    AnthropicContentBlock(type = "thinking", thinking = "再想想", signature = "sig-b"),
                    AnthropicContentBlock(type = "text", text = "答案")
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals("答案", result.content)
        // 思考文本仍拼接给 UI 展示，但回传用的快照必须保留三个原始块及其顺序。
        assertEquals("先想想再想想", result.reasoning)
        val snapshot = result.thinkingBlocksJson
        assertTrue(snapshot != null && snapshot.contains("opaque-blob"))
        assertEquals(
            listOf("thinking", "redacted_thinking", "thinking"),
            Regex("\"type\":\"(\\w+)\"").findAll(snapshot!!).map { it.groupValues[1] }.toList()
        )
    }

    @Test
    fun stop_details_and_cache_creation_are_surfaced() = runTest {
        val api = FakeApi(
            response(
                content = emptyList(),
                stopReason = "refusal",
                stopDetails = AnthropicStopDetails(type = "refusal", explanation = "违反使用政策"),
                usage = AnthropicUsage(
                    input_tokens = 10,
                    output_tokens = 0,
                    cache_read_input_tokens = 7,
                    cache_creation_input_tokens = 3
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        // 拒答时正文为空，只有 stop_details 里有可展示的理由。
        assertEquals("违反使用政策", result.stopDetail)
        assertTrue(result.isAborted)
        assertEquals(7, result.cachedInputTokens)
        assertEquals(3, result.cacheCreationTokens)
    }

    @Test
    fun assistant_history_replays_thinking_snapshot_verbatim() = runTest {
        val api = FakeApi(response())
        val snapshot = """[{"type":"thinking","thinking":"上轮思考","signature":"sig-a"},""" +
            """{"type":"redacted_thinking","data":"opaque-blob"}]"""
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "上轮回答",
                    reasoning = "上轮思考",
                    signature = "sig-a",
                    thinkingBlocksJson = snapshot
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "redacted_thinking", "text"), blocks.map { it.type })
        assertEquals("opaque-blob", blocks[1].data)
        assertEquals("sig-a", blocks[0].signature)
    }

    @Test
    fun assistant_history_falls_back_to_signature_block_without_snapshot() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                // 旧数据/备份恢复只有 signature，没有原生快照。
                AgentMessage.AssistantMessage(
                    content = "上轮回答",
                    reasoning = "上轮思考",
                    signature = "sig-a"
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "text"), blocks.map { it.type })
        assertEquals("上轮思考", blocks[0].thinking)
        assertNull(blocks[0].data)
    }

    @Test
    fun assistant_history_sends_no_thinking_block_when_nothing_stored() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "上轮回答")
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("text"), blocks.map { it.type })
    }

    @Test
    fun parallel_tool_results_go_into_one_user_message() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "并行读两个文件",
                    toolCalls = listOf(toolCall("c1"), toolCall("c2"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "b")
            )
        )

        // 两个 tool_result 拆成两条 user 消息时，不合并连续 user 轮的网关会报
        // "tool_use ids were found without tool_result blocks immediately after"。
        val messages = api.lastRequest!!.messages
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        @Suppress("UNCHECKED_CAST")
        val resultBlocks = messages.last().content as List<AnthropicContentBlock>
        assertEquals(listOf("tool_result", "tool_result"), resultBlocks.map { it.type })
        assertEquals(listOf("c1", "c2"), resultBlocks.map { it.tool_use_id })
    }

    @Test
    fun user_message_keeps_cache_breakpoint_on_last_text_block() = runTest {
        val api = FakeApi(response())
        adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        val user: AnthropicMessage = api.lastRequest!!.messages.last { it.role == "user" }
        @Suppress("UNCHECKED_CAST")
        val blocks = user.content as List<AnthropicContentBlock>
        assertEquals("ephemeral", blocks.last().cache_control?.get("type"))
    }

    @Test
    fun disablePromptCaching_strips_all_breakpoints() = runTest {
        // 一次性请求（上下文压缩）不能写缓存：写入费按高于普通输入的单价计，而该输入不会被复用。
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(AgentMessage.UserMessage(content = "hi")),
            tools = listOf(FakeTool()),
            disablePromptCaching = true
        )

        val request = api.lastRequest!!
        assertEquals("system 段应回退为纯文本、不带 cache_control", "sys", request.system)
        assertTrue(
            "工具定义不应再带断点，实际=${request.tools?.map { it.cache_control }}",
            request.tools.orEmpty().all { it.cache_control == null }
        )
        val user: AnthropicMessage = request.messages.last { it.role == "user" }
        @Suppress("UNCHECKED_CAST")
        val blocks = user.content as? List<AnthropicContentBlock>
        val breakpoints = blocks.orEmpty().mapNotNull { it.cache_control }
        assertTrue("消息断点应全部移除，实际=$breakpoints", breakpoints.isEmpty())
    }

    @Test
    fun promptCachingEnabled_keeps_tool_breakpoint() = runTest {
        // 对照组：默认（非一次性）请求仍应在最后一个工具上打点，否则缓存能力被误关。
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(AgentMessage.UserMessage(content = "hi")),
            tools = listOf(FakeTool())
        )

        assertEquals("ephemeral", api.lastRequest?.tools?.last()?.cache_control?.get("type"))
    }
}
