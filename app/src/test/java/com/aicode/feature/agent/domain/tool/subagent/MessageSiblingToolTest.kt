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
import kotlinx.coroutines.launch
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
        session("stranger", "parent-2", "别家子代理")
    )

    private fun newTool(bus: SubAgentEventBus = SubAgentEventBus()): Pair<MessageSiblingTool, SubAgentEventBus> {
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

    @Test
    fun emitsSiblingEvent_carryingSender() = runTest {
        val (tool, bus) = newTool()
        val events = mutableListOf<SubAgentEvent>()
        val job = backgroundScope.launch { bus.events.collect { events.add(it) } }
        testScheduler.advanceUntilIdle()

        tool.executeWithContext(args("to" to "sib", "message" to "同步结论"), ctx("me"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(1, events.size)
        assertEquals(SubAgentEventType.MESSAGE_FROM_SIBLING, events[0].type)
        assertEquals("sib", events[0].subSessionId)
        assertEquals("parent-1", events[0].parentSessionId)
        assertEquals("me", events[0].senderSessionId)
    }
}
