package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `task(action=send)` 的运行态硬闸：目标仍在运行时默认拒收。
 *
 * 背景（实测 4 天轨迹）：忙期追问会被并入子代理当前任务搭车送达，父代理却收到固定的
 * 「已投递」回执，于是重复追问——337 次 send 里有 61 次落在子代理运行窗口内。
 * 硬闸把这次调用从「无意识空转」变成显式决策：要么等完成通知，要么明确要求强行投递。
 */
class TaskToolSendGateTest {

    private val sessions = listOf(
        ChatSessionEntity(id = "parent-1", title = "主会话", createdAt = 0L, updatedAt = 0L, parentId = null),
        ChatSessionEntity(id = "sub-1", title = "干活中", createdAt = 0L, updatedAt = 0L, parentId = "parent-1"),
        ChatSessionEntity(id = "stranger", title = "别家子代理", createdAt = 0L, updatedAt = 0L, parentId = "parent-2")
    )

    private fun newTool(running: Set<String> = emptySet()): Pair<TaskTool, SubAgentEventBus> {
        val bus = mockk<SubAgentEventBus>(relaxed = true)
        every { bus.activeSubSessionIds } returns MutableStateFlow(running)
        val useCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { useCase.getSessionById(any()) } answers {
            sessions.firstOrNull { it.id == firstArg() }
        }
        val tool = TaskTool(
            sessionUseCase = useCase,
            chatSessionDao = mockk(relaxed = true),
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            writeLease = mockk(relaxed = true),
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk<AIProviderRepository>(relaxed = true)
        )
        return tool to bus
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> =
        mapOf("action" to JsonPrimitive("send")) + pairs.toMap()

    private fun ctx(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/w",
        language = null,
        sessionId = sessionId
    )

    private fun stateOf(result: ToolResult): String? =
        (result as? ToolResult.Success)?.data?.jsonObject?.get("state")?.jsonPrimitive?.content

    /** 核心行为：目标仍在运行且未 force → 拒绝，且一个事件都不许发出去。 */
    @Test
    fun sendToRunningSubAgent_isRejectedWithoutEmitting() = runTest {
        val (tool, bus) = newTool(running = setOf("sub-1"))

        val result = tool.executeWithContext(
            args("id" to JsonPrimitive("sub-1"), "message" to JsonPrimitive("再补一句")),
            ctx("parent-1")
        )

        assertEquals("SUBAGENT_BUSY", (result as ToolResult.Error).code)
        verify(exactly = 0) { bus.emit(any()) }
    }

    /** 拒绝文案必须自己说清怎么继续，否则父代理只会反复重试同一次调用。 */
    @Test
    fun busyRejection_tellsHowToProceed() = runTest {
        val (tool, _) = newTool(running = setOf("sub-1"))

        val result = tool.executeWithContext(
            args("id" to JsonPrimitive("sub-1"), "message" to JsonPrimitive("再补一句")),
            ctx("parent-1")
        ) as ToolResult.Error

        assertTrue("应提示完成通知这条正路：${result.message}", "完成通知" in result.message)
        assertTrue("应给出 force 逃生门：${result.message}", "force=true" in result.message)
    }

    /** 紧急追加的显式逃生门：force=true 时照旧投递，但回执必须如实说是「搭车」而非「已送达」。 */
    @Test
    fun sendToRunningSubAgent_withForce_isQueued() = runTest {
        val (tool, bus) = newTool(running = setOf("sub-1"))

        val result = tool.executeWithContext(
            args(
                "id" to JsonPrimitive("sub-1"),
                "message" to JsonPrimitive("紧急：别删那个文件"),
                "force" to JsonPrimitive(true)
            ),
            ctx("parent-1")
        )

        assertEquals("queued", stateOf(result))
        verify(exactly = 1) { bus.emit(any()) }
    }

    /** 已完成（不在活跃集合）→ 正常投递并唤醒，回执为 woken。 */
    @Test
    fun sendToIdleSubAgent_isWoken() = runTest {
        val (tool, bus) = newTool(running = emptySet())

        val result = tool.executeWithContext(
            args("id" to JsonPrimitive("sub-1"), "message" to JsonPrimitive("再查一遍")),
            ctx("parent-1")
        )

        assertEquals("woken", stateOf(result))
        verify(exactly = 1) { bus.emit(any()) }
    }

    /** force 只对「运行中」有意义：目标空闲时照样唤醒，不得变成跳过投递的理由。 */
    @Test
    fun forceOnIdleSubAgent_stillDelivers() = runTest {
        val (tool, bus) = newTool(running = emptySet())

        val result = tool.executeWithContext(
            args(
                "id" to JsonPrimitive("sub-1"),
                "message" to JsonPrimitive("再查一遍"),
                "force" to JsonPrimitive(true)
            ),
            ctx("parent-1")
        )

        assertEquals("woken", stateOf(result))
        verify(exactly = 1) { bus.emit(any()) }
    }

    /**
     * 投递出去的事件必须自带正确身份：若日后误改成别的事件类型、或写错收件人，
     * 上面那几条只断言 state 的用例仍会全绿——只有校验收件载荷才能抓住。
     */
    @Test
    fun deliveredEvent_carriesRecipientAndBody() = runTest {
        val (tool, bus) = newTool(running = emptySet())

        tool.executeWithContext(
            args("id" to JsonPrimitive("sub-1"), "message" to JsonPrimitive("再查一遍")),
            ctx("parent-1")
        )

        val captured = slot<SubAgentEvent>()
        verify(exactly = 1) { bus.emit(capture(captured)) }
        assertEquals(SubAgentEventType.MESSAGE_FROM_PARENT, captured.captured.type)
        assertEquals("sub-1", captured.captured.subSessionId)
        assertEquals("parent-1", captured.captured.parentSessionId)
        assertEquals("再查一遍", captured.captured.detail)
    }

    /** 回归：新增的运行态判定不能放宽既有的跨父越权拦截。 */
    @Test
    fun crossParentTarget_isStillRejected() = runTest {
        val (tool, _) = newTool(running = setOf("stranger"))

        val result = tool.executeWithContext(
            args("id" to JsonPrimitive("stranger"), "message" to JsonPrimitive("喂")),
            ctx("parent-1")
        )

        assertEquals("NOT_YOUR_SUBAGENT", (result as ToolResult.Error).code)
    }

    /** 回归：参数校验先于运行态判定，空正文不得因目标运行中而被误报成 BUSY。 */
    @Test
    fun blankMessage_isRejectedAsInvalidArgs() = runTest {
        val (tool, _) = newTool(running = setOf("sub-1"))

        val result = tool.executeWithContext(
            args("id" to JsonPrimitive("sub-1"), "message" to JsonPrimitive("   ")),
            ctx("parent-1")
        )

        assertEquals("INVALID_ARGS", (result as ToolResult.Error).code)
    }
}
