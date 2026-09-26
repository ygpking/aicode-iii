package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPayloadGuardTest {

    private fun user(text: String, images: List<AgentImage> = emptyList()) =
        AgentMessage.UserMessage(content = text, images = images)

    private fun assistant(text: String = "", toolCalls: List<ToolCall> = emptyList()) =
        AgentMessage.AssistantMessage(content = text, toolCalls = toolCalls)

    private fun toolResult(result: String, images: List<AgentImage> = emptyList()) =
        AgentMessage.ToolResultMessage(id = "c1", toolName = "Bash", result = result, images = images)

    private fun image(bytes: Int = 100) = AgentImage(mimeType = "image/png", base64Data = "A".repeat(bytes))

    // ---------- D3 首条必须 user ----------

    @Test
    fun leadingAssistantGetsBridgeUser() {
        val messages = listOf(assistant("cut mid-turn"), toolResult("x"))
        val out = RequestPayloadGuard.ensureLeadingUser(messages)
        assertTrue(out.first() is AgentMessage.UserMessage)
        assertEquals(messages.size + 1, out.size)
    }

    @Test
    fun leadingUserUnchanged() {
        val messages = listOf(user("hi"), assistant("yo"))
        assertSameList(messages, RequestPayloadGuard.ensureLeadingUser(messages))
    }

    @Test
    fun emptyStaysEmpty() {
        assertTrue(RequestPayloadGuard.ensureLeadingUser(emptyList()).isEmpty())
    }

    // ---------- D5 巨型单条截断 ----------

    @Test
    fun oversizedUserMessageIsTruncatedToBudget() {
        val giant = user("汉".repeat(50_000)) // 中文，约数万 token
        val out = RequestPayloadGuard.truncateOversizedUserMessages(listOf(giant), limitTokens = 2_000)
        val text = (out.single() as AgentMessage.UserMessage).content
        assertTrue("应插入截断标记", text.contains("已截断"))
        assertTrue("应比原文短", text.length < 50_000)
        assertTrue("应保留开头", text.startsWith("汉"))
        assertTrue("应保留结尾", text.endsWith("汉"))
    }

    @Test
    fun underBudgetLeavesUnchanged() {
        val list = listOf(user("短消息"))
        assertSameList(list, RequestPayloadGuard.truncateOversizedUserMessages(list, 100_000))
    }
    @Test
    fun smallMessagesNotSizedEvenIfTotalOverBudget() {
        // 每条都低于「巨型」阈值 → 不做单条截断（那属于折叠逻辑的职责）。
        val messages = (1..10).map { user("普通消息$it".repeat(50)) }
        assertSameList(messages, RequestPayloadGuard.truncateOversizedUserMessages(messages, 10))
    }

    // ---------- D4 字节预算 ----------

    @Test
    fun oversizedToolResultIsCompacted() {
        val big = toolResult("x".repeat(200_000))
        val out = RequestPayloadGuard.enforceByteBudget(listOf(user("q"), assistant("a"), big), maxBytes = 50_000)
        val result = (out.last() as AgentMessage.ToolResultMessage).result
        assertTrue(result.contains("已省略"))
        assertTrue(result.length < 200_000)
    }

    @Test
    fun underByteBudgetLeavesUnchanged() {
        val messages = listOf(user("hi"), assistant("yo"))
        assertSameList(messages, RequestPayloadGuard.enforceByteBudget(messages, maxBytes = 1_000_000))
    }

    @Test
    fun imagesStrippedWhenStillOverByteBudget() {
        val big = user("q", images = listOf(image(200_000)))
        val out = RequestPayloadGuard.enforceByteBudget(listOf(big), maxBytes = 10_000)
        val stripped = out.single() as AgentMessage.UserMessage
        assertTrue(stripped.images.isEmpty())
        assertTrue(stripped.content.contains("图片"))
    }

    @Test
    fun imagesStrippedWhenStillOverByteBudgetAfterToolCompaction() {
        // 工具输出无法再压（文本不大），但图片 base64 撑爆 → 剥图
        val msg = user("q", images = listOf(image(300_000), image(300_000)))
        val out = RequestPayloadGuard.enforceByteBudget(listOf(msg), maxBytes = 100_000)
        assertTrue((out.single() as AgentMessage.UserMessage).images.isEmpty())
    }

    @Test
    fun preservesMessageCountAndOrder() {
        val messages = listOf(user("q"), assistant("a"), toolResult("r".repeat(200_000)))
        val out = RequestPayloadGuard.enforceByteBudget(messages, maxBytes = 50_000)
        assertEquals(messages.size, out.size)
        assertTrue(out[0] is AgentMessage.UserMessage)
        assertTrue(out[1] is AgentMessage.AssistantMessage)
        assertTrue(out[2] is AgentMessage.ToolResultMessage)
    }

    // ---------- prepareForSend 组合 ----------

    @Test
    fun prepareAppliesAllThreeDefenses() {
        // 首条 assistant（D3）+ 巨型用户消息（D5）+ 超长工具输出（D4）。
        val messages = listOf(
            assistant("cut"),
            user("汉".repeat(60_000)),
            toolResult("x".repeat(300_000)),
        )
        val out = RequestPayloadGuard.prepareForSend(messages, budgetTokens = 2_000)
        assertTrue("D3：首条应为 user", out.first() is AgentMessage.UserMessage)
        assertTrue("D4：应回到字节预算内", RequestPayloadGuard.estimatePayloadBytes(out) <= RequestPayloadGuard.MAX_REQUEST_BYTES)
    }

    @Test
    fun prepareLeavesNormalConversationIntact() {
        val list = listOf(user("你好"), assistant("你好，有什么可以帮你？"))
        assertSameList(list, RequestPayloadGuard.prepareForSend(list, budgetTokens = 128_000))
    }

    // ---------- 字节估算 ----------

    @Test
    fun estimateCountsImagesAndFraming() {
        val justText = RequestPayloadGuard.estimatePayloadBytes(listOf(user("abc")))
        val withImage = RequestPayloadGuard.estimatePayloadBytes(listOf(user("abc", images = listOf(image(1_000)))))
        assertTrue(withImage > justText)
        assertTrue(justText >= 64) // 至少含一条 framing
    }

    private fun assertSameList(expected: List<AgentMessage>, actual: List<AgentMessage>) {
        // 无变化时应返回原实例（引用相等），避免无谓拷贝。
        assertTrue("预期无变化时返回原列表", expected === actual)
    }
}
