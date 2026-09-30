package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP Streamable HTTP 的 SSE 解析与响应配对。
 *
 * 背景：旧实现读到第一个空行就返回，只在「响应恰好是首个带 data 的事件」时才正确——
 * 服务端先发 progress/ping 再发响应、或响应跨多个事件时，会静默拿到错误负载。
 */
class StreamableHttpSseTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- extractDataPayloads ----

    @Test
    fun singleDataEvent() {
        val body = "data: {\"id\":1,\"result\":{}}\n\n"
        assertEquals(listOf("{\"id\":1,\"result\":{}}"), SseEventExtractor.extractDataPayloads(body))
    }

    /** 多行 data 属于同一事件，按规范以换行连接。 */
    @Test
    fun multiLineDataIsOnePayload() {
        val body = "data: {\"id\":1,\ndata: \"result\":{}}\n\n"
        assertEquals(listOf("{\"id\":1,\n\"result\":{}}"), SseEventExtractor.extractDataPayloads(body))
    }

    /**
     * 关键回归：首批事件不含响应（只有 progress）、响应在后面。
     * 旧实现遇首个空行就 return，只拿到 progress 负载。
     */
    @Test
    fun keepsAllEventsWhenResponseIsNotFirst() {
        val body = buildString {
            append("event: progress\n")
            append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n")
            append("\n")
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n")
            append("\n")
        }
        val payloads = SseEventExtractor.extractDataPayloads(body)

        assertEquals(2, payloads.size)
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}", payloads.last())
    }

    /** 流末尾没有空行时，最后一个事件同样要交付，否则丢的就是响应本身。 */
    @Test
    fun deliversTrailingEventWithoutBlankLine() {
        val body = "event: message\ndata: {\"id\":9,\"result\":{}}"
        assertEquals(listOf("{\"id\":9,\"result\":{}}"), SseEventExtractor.extractDataPayloads(body))
    }

    /** `event:` / `id:` / `retry:` / 注释行不产生负载；无 data 的事件不产生空条目。 */
    @Test
    fun ignoresNonDataFieldsAndEmptyEvents() {
        val body = ": keep-alive\n\nevent: ping\nretry: 3000\n\ndata: {\"id\":1}\n\n"
        assertEquals(listOf("{\"id\":1}"), SseEventExtractor.extractDataPayloads(body))
    }

    /** CRLF 行结束符要被剥离，不能让 `\r` 混进 JSON。 */
    @Test
    fun stripsCarriageReturns() {
        val body = "data: {\"id\":1}\r\n\r\n"
        assertEquals(listOf("{\"id\":1}"), SseEventExtractor.extractDataPayloads(body))
    }

    /** `data:` 后可选一个前导空格要去掉，但不能吃掉 JSON 本身的缩进。 */
    @Test
    fun stripsSingleLeadingSpaceOnly() {
        assertEquals(
            listOf("{\"a\": 1}"),
            SseEventExtractor.extractDataPayloads("data: {\"a\": 1}\n\n")
        )
    }

    @Test
    fun emptyBodyYieldsNoPayloads() {
        assertTrue(SseEventExtractor.extractDataPayloads("").isEmpty())
        assertTrue(SseEventExtractor.extractDataPayloads("\n\n").isEmpty())
    }

    // ---- pickForId ----

    /** 在多个事件里挑出 id 配对的那条（而不是无脑取第一条）。 */
    @Test
    fun picksPayloadMatchingExpectedId() {
        val payloads = listOf(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}"
        )
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}", SseEventExtractor.pickForId(payloads, 3, json))
    }

    /** 单条解析失败只跳过它，不影响后续候选。 */
    @Test
    fun skipsUnparsablePayloads() {
        val payloads = listOf("not json at all", "{\"id\":5,\"result\":{}}")
        assertEquals("{\"id\":5,\"result\":{}}", SseEventExtractor.pickForId(payloads, 5, json))
    }

    /** 没有配对 id 时返回 null（上层据此报错，而不是拿错负载当结果）。 */
    @Test
    fun returnsNullWhenNoIdMatches() {
        val payloads = listOf("{\"id\":1,\"result\":{}}", "{\"id\":2,\"result\":{}}")
        assertNull(SseEventExtractor.pickForId(payloads, 99, json))
    }

    // ---- validateJsonRpcResponse ----

    @Test
    fun validate_acceptsMatchingId() {
        validateJsonRpcResponse(JsonRpcResponse(id = 1, result = null), 1, "tools/list")
    }

    /** JSON-RPC 错误响应照旧抛错，并保留 rpcCode。 */
    @Test
    fun validate_throwsOnErrorWithRpcCode() {
        val e = runCatching {
            validateJsonRpcResponse(
                JsonRpcResponse(id = 1, error = JsonRpcError(code = -32601, message = "method not found")),
                1, "tools/list"
            )
        }.exceptionOrNull()
        assertTrue(e is McpException)
        assertEquals(-32601, (e as McpException).rpcCode)
    }

    /** 缺 id 的响应无从确认归属（可能是服务端通知），必须抛错而不是当结果返回。 */
    @Test
    fun validate_throwsWhenIdMissing() {
        val e = runCatching {
            validateJsonRpcResponse(JsonRpcResponse(id = null, result = null), 1, "tools/call")
        }.exceptionOrNull()
        assertTrue("缺 id 应抛 McpException，实际: $e", e is McpException)
    }

    /** 关键回归：id 不匹配以前只记日志、把别人的响应当结果返回；现在必须抛错。 */
    @Test
    fun validate_throwsOnIdMismatch() {
        val e = runCatching {
            validateJsonRpcResponse(JsonRpcResponse(id = 2, result = null), 1, "tools/call")
        }.exceptionOrNull()
        assertTrue("id 不匹配应抛 McpException，实际: $e", e is McpException)
        assertTrue("报错应含期望与实际 id", e!!.message!!.contains("id") )
    }
}
