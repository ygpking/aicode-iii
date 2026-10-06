package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩块恢复的纯逻辑回归测试：覆盖恢复态在消息投影上的判据与 [CompactionTailSelector]
 * 对恢复段的 tail 保护区行为。
 */
class CompactionRestoreTest {

    private fun user(id: String, restored: Boolean = false) =
        AgentMessage.UserMessage(id = id, content = "q-$id", restoredFromCompaction = restored)

    private fun assistant(id: String, restored: Boolean = false) =
        AgentMessage.AssistantMessage(id = id, content = "a-$id", restoredFromCompaction = restored)

    // ---------- 恢复态判据 ----------

    @Test
    fun restoredFlagIsFalseByDefault() {
        val msg = AgentMessage.UserMessage(id = "u1", content = "q")
        assertFalse(msg.restoredFromCompaction)
    }

    @Test
    fun restoredFlagSurvivesAllThreeMessageTypes() {
        assertTrue(user("u", restored = true).restoredFromCompaction)
        assertTrue(assistant("a", restored = true).restoredFromCompaction)
        assertTrue(
            AgentMessage.ToolResultMessage(
                id = "t", toolName = "readFile", result = "x", restoredFromCompaction = true
            ).restoredFromCompaction
        )
    }

    // ---------- tail 保护区（selectTailStartIndex 对恢复段的优先容纳） ----------

    /** 与 ContextCompactor 同口径的 token 估算下界：纯 ASCII 按 2.5 字/token。 */
    private fun estimate(msg: AgentMessage): Int =
        ModelContextPolicyLocal.estimate(msg)

    /** 隔离测试用的估算入口（避免触碰 Android 类）。 */
    private object ModelContextPolicyLocal {
        fun estimate(msg: AgentMessage): Int = when (msg) {
            is AgentMessage.UserMessage -> msg.content.length
            is AgentMessage.AssistantMessage -> msg.content.length
            is AgentMessage.ToolResultMessage -> msg.result.length
        }
    }

    @Test
    fun noRestoredMessages_behaviorUnchanged() {
        // 无恢复段：从尾部按预算回溯，普通行为。
        val messages = listOf(
            user("u1"), assistant("a1"), user("u2"), assistant("a2")
        )
        // 预算给足：全部进 tail
        assertEquals(0, CompactionTailSelector.compute(messages, budget = 100, estimate = ::estimate))
    }

    @Test
    fun restoredSegmentIsProtectedFromTheStartOfTheSegment() {
        // 恢复段从 r1 开始：即使 r1 恰好耗尽预算，tail 起点也必须推进到 r1（含），
        // 否则下轮压缩会把刚恢复的消息再折回去，恢复白做。
        val messages = listOf(
            user("r1", restored = true),           // 恢复段首
            assistant("r2", restored = true),
            user("u1"), assistant("a1")            // 新消息
        )
        // 预算只够 a1+u1+r2，r2 之后再放不下 r1 —— 但 r1 是恢复段首，必须整体进 tail。
        val split = CompactionTailSelector.compute(messages, budget = 60, estimate = ::estimate)
        assertEquals(0, split)
    }

    @Test
    fun restoredSegmentStopsBacktrackAtSegmentStart() {
        // 恢复段在中间：回溯越过恢复段尾后，必须在段首停下（不让段首之前的消息进 tail）。
        val messages = listOf(
            user("old1"), user("old2"),            // 更早的普通消息（应留 head）
            user("r1", restored = true),           // 恢复段
            assistant("r2", restored = true),
            user("new1"), assistant("new2")        // 新消息
        )
        val split = CompactionTailSelector.compute(messages, budget = 10_000, estimate = ::estimate)
        // 预算充足时回溯应停在恢复段首（index=2），old1/old2 仍在 head。
        assertEquals(2, split)
    }

    @Test
    fun noRestoredMessagesFullBudgetReturnsZero() {
        val messages = listOf(user("u1"), assistant("a1"))
        assertEquals(0, CompactionTailSelector.compute(messages, budget = 10_000, estimate = ::estimate))
    }
}
