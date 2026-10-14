package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `messageSibling` 的边界：核心越权面是「跨父可达」——不同主会话派出的子代理
 * 必须互相看不见，否则 A 项目的子代理能给 B 项目的子代理下指令。
 */
class MessageSiblingToolTest {

    private fun session(id: String, parentId: String?, title: String) = ChatSessionEntity(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = 0L,
        parentId = parentId
    )

    private val sessions = listOf(
        session("parent-1", null, "主会话一"),
        session("parent-2", null, "主会话二"),
        session("me", "parent-1", "兄弟A"),
        session("sib", "parent-1", "兄弟B"),
        session("stranger", "parent-2", "别家子代理"),
        session("twin-1", "parent-1", "同名任务"),
        session("twin-2", "parent-1", "同名任务")
    )

    private fun newTool(
        bus: SubAgentEventBus = mockk(relaxed = true)
    ): Pair<MessageSiblingTool, SubAgentEventBus> {
        val useCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { useCase.getSessionById(any()) } answers {
            sessions.firstOrNull { it.id == firstArg() }
        }
        coEvery { useCase.getSubSessionsByParentOnce(any()) } answers {
            sessions.filter { it.parentId == firstArg<String>() }
        }
        return MessageSiblingTool(useCase, bus) to bus
    }

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    private fun ctx(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/w",
        language = null,
        sessionId = sessionId
    )

    @Test
    fun sendToSibling_byId_succeeds() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "sib", "message" to "帮我跑下测试"), ctx("me"))
        assertTrue("同父兄弟应投递成功：$result", result is ToolResult.Success)
    }

    @Test
    fun sendToSibling_byTitle_succeeds() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "兄弟B", "message" to "看下日志"), ctx("me"))
        assertTrue("按标题也应能找到：$result", result is ToolResult.Success)
    }

    @Test
    fun sendToCrossParentSibling_isRejected() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "stranger", "message" to "喂"), ctx("me"))
        assertTrue("跨父必须被拒：$result", result is ToolResult.Error)
        assertEquals("SIBLING_NOT_FOUND", (result as ToolResult.Error).code)
    }

    @Test
    fun sendToSelf_isRejected() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "me", "message" to "自言自语"), ctx("me"))
        assertEquals("INVALID_TARGET", (result as ToolResult.Error).code)
    }

    /**
     * 同名兄弟（典型：连派两个「Explore」）不能静默取第一个——消息会送给错的人而发送方不知道。
     */
    @Test
    fun sendToDuplicatedTitle_isRejectedAsAmbiguous() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "同名任务", "message" to "给谁"), ctx("me"))

        assertEquals("AMBIGUOUS_TARGET", (result as ToolResult.Error).code)
        assertTrue("应列出候选 id 供改用：${result.message}", "twin-1" in result.message && "twin-2" in result.message)
    }

    /** 歧义只针对标题回退：id 唯一，按 id 发送不受同名影响。 */
    @Test
    fun sendToDuplicatedTitle_byId_stillSucceeds() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "twin-2", "message" to "给第二个"), ctx("me"))

        assertTrue("按 id 应能投递：$result", result is ToolResult.Success)
    }

    @Test
    fun sendFromRootSession_isRejected() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "sib", "message" to "喂"), ctx("parent-1"))
        assertEquals("NO_PARENT", (result as ToolResult.Error).code)
    }

    @Test
    fun blankMessage_isRejected() = runTest {
        val (tool, _) = newTool()
        val result = tool.executeWithContext(args("to" to "sib", "message" to "   "), ctx("me"))
        assertEquals("INVALID_ARGS", (result as ToolResult.Error).code)
    }

    /**
     * 投递出去的事件必须自带发送者身份：收件方的通知文案靠 senderSessionId 区分
     * 「兄弟发来的」还是「父会话发来的」，缺了它收件子代理会认错发言人。
     *
     * 这里直接校验收件总线收到的载荷，不用订阅真实 SharedFlow（replay=0，采集协程的
     * 调度时机不确定，断言会变成时序竞态）。
     */
    @Test
    fun emitsSiblingEvent_carryingSender() = runTest {
        val bus = mockk<SubAgentEventBus>(relaxed = true)
        val (tool, _) = newTool(bus)

        tool.executeWithContext(args("to" to "sib", "message" to "同步结论"), ctx("me"))

        val captured = slot<SubAgentEvent>()
        verify(exactly = 1) { bus.emit(capture(captured)) }
        assertEquals(SubAgentEventType.MESSAGE_FROM_SIBLING, captured.captured.type)
        assertEquals("sib", captured.captured.subSessionId)
        assertEquals("parent-1", captured.captured.parentSessionId)
        assertEquals("me", captured.captured.senderSessionId)
        assertEquals("同步结论", captured.captured.detail)
    }
}
