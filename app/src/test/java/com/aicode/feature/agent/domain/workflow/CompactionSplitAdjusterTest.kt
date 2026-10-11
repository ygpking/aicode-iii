package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompactionSplitAdjuster] 的纯逻辑回归：拆分点不得劈开 tool 配对，也不得劈开
 * 压缩 marker/summary 对。
 *
 * 背景（实测 bug）：tail 预算边界恰好落在 marker（约 9 token 的固定短句）的估算宽度内时，
 * 拆分点会落在 marker 与 summary 之间。此时 marker 进 head、summary 进 tail，二者都被劈开：
 * marker 作为纯噪音 user 消息进入本次摘要材料，summary 留在活上下文里多占一份。
 * [ContextCompactor.removeCompactionPairs] 只按「marker 紧跟 summary」整对删除，劈开后两个都删不掉。
 */
class CompactionSplitAdjusterTest {

    private fun user(id: String, content: String = "q-$id") =
        AgentMessage.UserMessage(id = id, content = content)

    private fun assistant(id: String, body: String = "a-$id") =
        AgentMessage.AssistantMessage(id = id, content = body)

    private fun toolResult(id: String) =
        AgentMessage.ToolResultMessage(id = id, toolName = "readFile", result = "r-$id")

    private fun assistantWithToolCall(id: String) =
        AgentMessage.AssistantMessage(
            id = id, content = "",
            toolCalls = listOf(ToolCall(id = "call-$id", name = "readFile", arguments = emptyMap<String, JsonElement>()))
        )

    private fun marker(id: String = "mk") = user(id, CONTEXT_COMPACTION_MARKER)

    // ---------- 约束 2：marker/summary 对不许劈开 ----------

    @Test
    fun splitBetweenMarkerAndSummaryIsAdvanced() {
        // [u, mk, summary, u] 原始 split=2（落在 mk 与 summary 之间）→ 应前移到 3
        val messages = listOf(user("u1"), marker(), assistant("sm"), user("u2"))
        val adjusted = CompactionSplitAdjuster.adjust(messages, initialSplitIndex = 2)
        assertEquals("summary 应随 marker 一起进 head", 3, adjusted)
    }

    @Test
    fun splitAfterPairIsUnchanged() {
        // split=3：marker+summary 都在 head，无需调整
        val messages = listOf(user("u1"), marker(), assistant("sm"), user("u2"))
        assertEquals(3, CompactionSplitAdjuster.adjust(messages, 3))
    }

    @Test
    fun splitBeforePairIsUnchanged() {
        // split=1：marker+summary 都在 tail，无需调整（一对一起保留）
        val messages = listOf(user("u1"), marker(), assistant("sm"), user("u2"))
        assertEquals(1, CompactionSplitAdjuster.adjust(messages, 1))
    }

    @Test
    fun advancingNeverEmptiesTail() {
        // 对在最末尾 [u, mk, summary]，split=2 落在 mk 与 summary 之间。
        // 前移会得 3 = size → 清空 tail，必须禁止 → 保持 2。
        val messages = listOf(user("u1"), marker(), assistant("sm"))
        val adjusted = CompactionSplitAdjuster.adjust(messages, initialSplitIndex = 2)
        assertEquals("不允许把 tail 清空", 2, adjusted)
        assertTrue("tail 至少保留一条", adjusted < messages.size)
    }

    @Test
    fun markerLikeContentButNotUserRoleIsNotATrigger() {
        // 内容等于 MARKER 但角色是 assistant → 不是 marker，不应触发前移
        val messages = listOf(
            user("u1"),
            AgentMessage.AssistantMessage(id = "a", content = CONTEXT_COMPACTION_MARKER),
            assistant("sm"),
            user("u2"),
        )
        assertEquals(2, CompactionSplitAdjuster.adjust(messages, 2))
    }

    @Test
    fun assistantAfterNormalUserDoesNotTrigger() {
        // 普通 user 后跟 assistant（非 marker）→ 不触发
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(2, CompactionSplitAdjuster.adjust(messages, 2))
    }

    // ---------- 约束 1：tool 配对回溯（既有行为，回归保护） ----------

    @Test
    fun tailStartingWithToolResultWalksBackToAssistant() {
        val messages = listOf(
            user("u1"),
            assistantWithToolCall("a1"),
            toolResult("t1"),
            user("u2"),
        )
        // split=2 落在 tool 结果上 → 回溯到 assistantWithToolCall（下标 1）
        assertEquals(1, CompactionSplitAdjuster.adjust(messages, 2))
    }

    @Test
    fun zeroSplitStaysZero() {
        assertEquals(0, CompactionSplitAdjuster.adjust(listOf(user("u1"), assistant("a1")), 0))
    }

    // ---------- 组合：两个约束同时存在 ----------

    @Test
    fun toolResultWalkBackThenPairAdvanceCompose() {
        // [u0, asst+tc1, tool2, mk3, summary4, u5]
        // 原始 split=2（指向 tool2）→ 触发 tool 回溯到 1（asst+tc）。
        // 之后 messages[0]=u（非 marker）→ 不触发前移。结果 1。
        val messages = listOf(
            user("u1"),
            assistantWithToolCall("a1"),
            toolResult("t1"),
            marker(),
            assistant("sm"),
            user("u2"),
        )
        assertEquals(1, CompactionSplitAdjuster.adjust(messages, initialSplitIndex = 2))
    }

    @Test
    fun toolBacktrackAndPairAdvanceAreIndependent() {
        // split=3 指向 mk（非 tool）→ 无 tool 回溯；messages[2]=tool（非 marker）→ 不触发前移，结果 3。
        val messages = listOf(
            user("u1"),
            assistantWithToolCall("a1"),
            toolResult("t1"),
            marker(),
            assistant("sm"),
            user("u2"),
        )
        assertEquals(3, CompactionSplitAdjuster.adjust(messages, initialSplitIndex = 3))
    }
}
