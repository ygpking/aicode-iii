package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP「Streamable HTTP」传输实现。
 *
 * 单一端点 POST JSON-RPC；server 可回 `application/json`（单条响应）或
 * `text/event-stream`（SSE，我们读取其中首条带 id 的 message 事件即可，因为本传输是
 * 一问一答、不维持服务端推送通道）。`initialize` 响应里的 `Mcp-Session-Id` 头会被记下，
 * 之后每条请求都带上（spec 要求）。
 *
 * SSE 的解析方式与 AnthropicAdapter 一致——手动读 `data:` 行，避免引入 okhttp-sse。
 */
class StreamableHttpTransport(
    private val endpoint: String,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val json: Json = DEFAULT_JSON
) : McpTransport {

    private companion object {
        const val TAG = "McpHttpTransport"
        val JSON_MEDIA = "application/json".toMediaType()

        /** 响应体硬上限（8MB）：超过即拒绝读取，避免 body.string() 无界读入内存触发 OOM。 */
        const val HARD_MAX_BYTES = ResponseSizeGovernor.HARD_MAX_BYTES
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val idCounter = AtomicLong(0)

    @Volatile
    private var sessionId: String? = null

    override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse =
        withContext(Dispatchers.IO) {
            val id = idCounter.incrementAndGet()
            val payload = JsonRpcRequest(id = id, method = method, params = params)
            val bodyJson = json.encodeToString(JsonRpcRequest.serializer(), payload)
            FileLogger.d(TAG, "→ [$method] id=$id")

            val httpReq = buildRequest(bodyJson)
            client.newCall(httpReq).execute().use { resp ->
                // 会话 id 在首个响应（initialize）里下发，记下供后续请求复用。
                resp.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) sessionId = it }

                if (!resp.isSuccessful) {
                    throw McpException(message = "HTTP ${resp.code} 调用 $method 失败: ${resp.message}")
                }

                // 防 OOM：带硬上限读取响应体，超硬限直接拒绝，不再无界 body.string()。
                val source = resp.body?.source()
                    ?: throw McpException(message = "$method 响应体为空")
                // 声明长度用于与实收字节数对账（-1 表示 chunked/未知，跳过对账）。
                val declaredLength = resp.body?.contentLength() ?: -1L
                val bodyBytes = readBodyWithin(source, HARD_MAX_BYTES, declaredLength, method)
                val rawBody = bodyBytes.toString(Charsets.UTF_8)

                val contentType = resp.header("Content-Type").orEmpty()
                val rawJson = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    // 一个 SSE 流可能含多个事件（服务端先发 progress/ping，真正的响应在后面）。
                    // 逐个事件取 data 负载，挑出 id 配对的那条——不能拿第一个事件的负载当结果。
                    val payloads = SseEventExtractor.extractDataPayloads(rawBody)
                    if (payloads.isEmpty()) {
                        throw McpException(message = "SSE 响应中未找到 $method 的数据")
                    }
                    // 挑不出配对 id 就明确报错（而不是把首个负载当结果往下传，让报错变成误导性的
                    // 「JSON 解析失败」）。常见于服务端把响应发成了 notification、或 id 被写成字符串。
                    SseEventExtractor.pickForId(payloads, id, json)
                        ?: throw McpException(
                            message = "$method 的 SSE 响应中无 id=$id 的数据（共 ${payloads.size} 个事件）"
                        )
                } else {
                    rawBody
                }

                parseAndValidate(rawJson, id, method)
            }
        }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        val payload = JsonRpcNotification(method = method, params = params)
        val bodyJson = json.encodeToString(JsonRpcNotification.serializer(), payload)
        FileLogger.d(TAG, "→ notify [$method]")
        client.newCall(buildRequest(bodyJson)).execute().use { resp ->
            // 通知按 spec 通常返回 202 且无 body，失败即报错：与 request() 的契约保持一致。
            // 此前只记日志，导致 HTTP 401/500 被当成功——等后续请求再失败，错误现场已丢失。
            // 当前唯一调用点是握手的 notifications/initialized，失败必须让握手失败。
            resp.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) sessionId = it }
            if (!resp.isSuccessful) {
                throw McpException(message = "HTTP ${resp.code} 通知 $method 失败: ${resp.message}")
            }
        }
    }

    override fun close() {
        sessionId = null
    }

    private fun buildRequest(bodyJson: String): Request {
        val headers = Headers.Builder().apply {
            add("Content-Type", "application/json")
            // 同时接受两种响应，让 server 自行决定单条 JSON 还是 SSE。
            add("Accept", "application/json, text/event-stream")
            sessionId?.let { add("Mcp-Session-Id", it) }
            extraHeaders.forEach { (k, v) -> add(k, v) }
        }.build()

        return Request.Builder()
            .url(endpoint)
            .headers(headers)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun parseAndValidate(rawJson: String, expectedId: Long, method: String): JsonRpcResponse {
        val response = runCatching {
            json.decodeFromString(JsonRpcResponse.serializer(), rawJson)
        }.getOrElse {
            throw McpException(message = "$method 响应 JSON 解析失败: ${it.message}", cause = it)
        }

        validateJsonRpcResponse(response, expectedId, method)
        return response
    }
}

/**
 * 有界读取响应体：最多读 [hardMaxBytes]，超过即报错；读到结尾后确认流已正常结束。
 *
 * 不能用 [okio.BufferedSource.readByteArray] 的**带参**重载：那个重载要求「**读满**指定字节数」，
 * 不足就抛 EOFException（okio 3.6 实测：36 字节的响应配 `readByteArray(8MB+1)` 必抛 EOF）。
 * 本函数与它对照使用时表现为「任何小于硬上限的响应都连接失败」——MCP 因此从未握手成功过。
 * [okio.Source.read]（读满或读到源耗尽，返回实际字节数）才是有界读的正确语义。
 *
 * 结尾校验不可省：单靠 [okio.Source.read] 会**静默接受截断的响应**。实测（真实 OkHttp：声明 100 字节、
 * 实际只发 50 字节就断连）该源会先返回 50、再以 -1 结束，**不抛异常**；半截 JSON 会被当完整响应
 * 去解析，报出误导性的「JSON 解析失败」。而 [okio.Source.read] 是单次读、不保证读满，
 * 故必须循环读到结束，并用 [declaredLength]（源自 `ResponseBody.contentLength()`）对账实收长度。
 *
 * @param declaredLength 服务端声明的字节数；`-1` 表示长度未知（chunked），此时跳过长度对账。
 */
internal fun readBodyWithin(
    source: okio.Source,
    hardMaxBytes: Long,
    declaredLength: Long,
    method: String
): ByteArray {
    val sink = okio.Buffer()
    var total = 0L
    while (total <= hardMaxBytes) {
        val read = source.read(sink, hardMaxBytes + 1 - total)
        if (read == -1L) break
        total += read
    }
    if (total > hardMaxBytes) {
        throw McpException(
            message = "$method 响应超过硬上限 ${hardMaxBytes / 1024 / 1024}MB，拒绝读取"
        )
    }
    if (declaredLength >= 0 && total != declaredLength) {
        throw McpException(
            message = "$method 响应长度与声明不符（声明 $declaredLength 字节、实收 $total 字节），传输可能中断"
        )
    }
    return sink.readByteArray()
}

/**
 * 校验一条响应确实能与请求配对。
 *
 * 之前 id 不匹配只 `FileLogger.w`，于是把串台/乱序的响应当结果返回——调用方会拿到别人的数据
 * （例如 B 调的 `tools/call` 收到的却是 A 的 `tools/list`），错误完全静默。这里统一改为抛错。
 */
internal fun validateJsonRpcResponse(response: JsonRpcResponse, expectedId: Long, method: String) {
    response.error?.let {
        throw McpException(rpcCode = it.code, message = "$method 返回错误 [${it.code}] ${it.message}")
    }
    if (response.id == null) {
        // 请求必须被带上 id 原样回传（JSON-RPC 2.0）；没有 id 就无从确认这条响应是我们的。
        throw McpException(message = "$method 响应缺少 id，无法与请求配对")
    }
    if (response.id != expectedId) {
        throw McpException(message = "$method 响应 id 不匹配: 期望 $expectedId, 实际 ${response.id}")
    }
}

/**
 * MCP Streamable HTTP 的 SSE 响应解析。
 *
 * 一次 POST 的响应体可能是 SSE，且**可能含多个事件**（服务端先发 progress/notification，真正
 * 的响应在后面的 message 事件里）。旧实现「读到第一个空行就返回」，只在响应恰好是首个带 data
 * 的事件时才正确，其余情况静默拿到错误负载。这里按 SSE 规范把所有事件的 data 负载都切出来，
 * 再由上层按 id 配对挑选。
 *
 * 纯字符串逻辑、零 Android 依赖，便于单测（与 McpFrameReader 同一风格）。
 */
internal object SseEventExtractor {

    /**
     * 切出各事件的 data 负载，保持出现顺序。
     *
     * 规范要点：`data:` 行去掉可选的一个前导空格后取值；同一事件的多个 `data:` 行用 `\n` 连接；
     * 空行是事件边界；`event:` / `id:` / `retry:` 与 `:` 注释对负载无贡献，直接忽略。
     */
    fun extractDataPayloads(body: String): List<String> {
        val payloads = mutableListOf<String>()
        val current = StringBuilder()
        var hasData = false

        for (rawLine in body.split('\n')) {
            val line = rawLine.trimEnd('\r')
            when {
                line.isEmpty() -> {
                    // 事件边界：交出本事件累积的 data（没有 data 的事件直接跳过）。
                    if (hasData) payloads.add(current.toString().trimEnd('\n'))
                    current.setLength(0)
                    hasData = false
                }
                line.startsWith("data:") -> {
                    val value = line.removePrefix("data:")
                    current.append(if (value.startsWith(" ")) value.substring(1) else value).append('\n')
                    hasData = true
                }
                else -> Unit
            }
        }
        // 流末尾没有空行时，最后一个事件同样要交出（否则丢最后一条 = 丢响应）。
        if (hasData) payloads.add(current.toString().trimEnd('\n'))
        return payloads
    }

    /**
     * 在 [payloads] 中挑出 id 等于 [expectedId] 的那条响应；没有匹配则返回 null。
     * 单条负载解析失败只跳过它，不影响其余候选。
     */
    fun pickForId(payloads: List<String>, expectedId: Long, json: Json): String? =
        payloads.firstOrNull { payload ->
            runCatching { json.decodeFromString(JsonRpcResponse.serializer(), payload).id }
                .getOrNull() == expectedId
        }
}
