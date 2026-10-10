package com.aicode.feature.agent.domain.mcp

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 有界读取响应体的回归锁。
 *
 * 曾把 [okio.BufferedSource.readByteArray] 的带参重载当作「读上限」用，实际语义是
 * 「**读满**指定字节数、不足即抛 EOFException」——于是任何小于硬上限的响应都被判成
 * 连接失败（MCP 从未握手成功过）。这里锁住正确语义，并锁住截断响应的拒绝行为。
 *
 * [declaredLength] 传 `-1` 表示服务端未声明长度（chunked），此时跳过长度对账。
 */
class StreamableHttpReadBodyTest {

    private fun filled(n: Int) = Buffer().apply { write(ByteArray(n) { 0x41 }) }

    @Test
    fun readsShortBodyWithoutThrowing() {
        // 核心回归：小响应必须能正常读出，且内容逐字节一致。
        val payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
        val source = Buffer().apply { writeUtf8(payload) }
        val bytes = readBodyWithin(source, 8L * 1024 * 1024, payload.length.toLong(), "initialize")
        assertEquals(payload, bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun readsEmptyBodyAsEmptyArray() {
        val bytes = readBodyWithin(Buffer(), 8L * 1024 * 1024, 0L, "initialize")
        assertEquals(0, bytes.size)
    }

    @Test
    fun readsBodyExactlyAtLimit() {
        val limit = 64L
        val bytes = readBodyWithin(filled(limit.toInt()), limit, limit, "initialize")
        assertEquals(limit.toInt(), bytes.size)
    }

    @Test
    fun rejectsBodyAboveLimit() {
        try {
            readBodyWithin(filled(65), 64L, 65L, "tools/list")
            fail("超过硬上限应抛 McpException")
        } catch (e: McpException) {
            // 报错要指明方法，便于定位是哪个调用越界。
            assertEquals(true, e.message.orEmpty().contains("tools/list"))
            assertEquals(true, e.message.orEmpty().contains("拒绝读取"))
        }
    }

    @Test
    fun rejectsBodyShorterThanDeclared() {
        // 回归：声明 100 字节、实际只有 50。源**不会**自己抛异常（真机断流正是先返回 50、
        // 再以 -1 结束），必须靠长度对账才发现，否则半截 JSON 会被当完整响应去解析。
        try {
            readBodyWithin(filled(50), 8L * 1024 * 1024, 100L, "initialize")
            fail("截断的响应必须报错，不能当完整内容返回")
        } catch (e: McpException) {
            assertEquals(true, e.message.orEmpty().contains("长度与声明不符"))
        }
    }

    @Test
    fun unknownDeclaredLengthSkipsReconciliation() {
        // chunked（Content-Length 为 -1）时无法对账，按实收处理，不误报。
        assertEquals(50, readBodyWithin(filled(50), 8L * 1024 * 1024, -1L, "initialize").size)
    }

    @Test
    fun oversizedBodyDoesNotRequireDrainingToDetect() {
        // 只需越过上限 1 字节即可判定，不把整段大响应读进内存。
        val total = 1024 * 1024
        val limit = 1024L
        val source = filled(total)
        try {
            readBodyWithin(source, limit, total.toLong(), "tools/call")
            fail("应抛 McpException")
        } catch (e: McpException) {
            // 只消费了 limit+1 字节，其余仍在源里（未整段读入内存）。
            assertEquals(total - (limit + 1), source.size)
        }
    }
}
