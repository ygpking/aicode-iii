package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [collectStreamWithin] 的兜底超时与进度节流覆盖。
 *
 * 该函数是 runToolStream 的兜底守卫：底层 flow 不终止（无界重试/卡死）时，
 * 本轮会一直被占住，链路上没有任何东西能拦住它。这里用真实（虚拟）时间验证
 * 「正常结束返回 true」与「卡死超时返回 false」两条路径，并确认超时后不再回调 onCompleted。
 */
class CollectStreamWithinTest {

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/workspace",
        language = null
    )

    private fun toolCall() = ToolCall("call-1", "demo", mapOf("x" to JsonPrimitive(1)))

    /** 立即产出给定事件后结束的流式工具。 */
    private fun toolEmitting(vararg events: ToolStreamEvent) = object : StreamingAgentTool {
        override fun executeStream(
            args: Map<String, kotlinx.serialization.json.JsonElement>,
            context: AgentContext
        ): Flow<ToolStreamEvent> = flow { events.forEach { emit(it) } }
    }

    /** 永不结束的流式工具：模拟底层卡死 / 无界重试。 */
    private fun hangingTool() = object : StreamingAgentTool {
        override fun executeStream(
            args: Map<String, kotlinx.serialization.json.JsonElement>,
            context: AgentContext
        ): Flow<ToolStreamEvent> = flow {
            emit(ToolStreamEvent.Progress("still running"))
            awaitCancellation()
        }
    }

    @Test
    fun completes_returnsTrueAndDeliversResult() = runTest {
        val result = ToolResult.Success(JsonPrimitive("done"))
        var completed: ToolResult? = null
        var lastProgress: String? = null

        val ok = collectStreamWithin(
            toolCall = toolCall(),
            context = context,
            tool = toolEmitting(ToolStreamEvent.Progress("line-1"), ToolStreamEvent.Completed(result)),
            timeoutMs = 1_000,
            onProgress = { lastProgress = it },
            onCompleted = { completed = it }
        )

        assertTrue("flow 正常结束应返回 true", ok)
        assertEquals(result, completed)
        assertTrue("首条进度应立即上抛（lastEmitMs 初值为 0）", lastProgress?.contains("line-1") == true)
    }

    @Test
    fun hanging_returnsFalseOnTimeout() = runTest {
        var completed: ToolResult? = null

        val ok = collectStreamWithin(
            toolCall = toolCall(),
            context = context,
            tool = hangingTool(),
            timeoutMs = 1_000,
            onProgress = {},
            onCompleted = { completed = it }
        )

        assertFalse("flow 不终止时应由兜底超时判为失败", ok)
        assertEquals("超时路径不应回调 onCompleted", null, completed)
    }

    @Test
    fun progress_isThrottled() = runTest {
        // 连续 emit 多个 chunk：节流窗口内只放行首条，不应逐 chunk 上抛。
        // 不断言精确次数（依赖真实时钟，跨窗口时会多放行一次），只断言「显著少于 chunk 数」。
        var progressCount = 0
        val chunks = List(20) { "chunk-$it" }
        val ok = collectStreamWithin(
            toolCall = toolCall(),
            context = context,
            tool = toolEmitting(
                *chunks.map { ToolStreamEvent.Progress(it) }.toTypedArray(),
                ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive("ok")))
            ),
            timeoutMs = 1_000,
            onProgress = { progressCount++ },
            onCompleted = {}
        )

        assertTrue(ok)
        assertTrue("20 个 chunk 应被 250ms 窗口节流，实际上抛 $progressCount 次", progressCount <= 3)
    }
}
