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
    fun restoredAnchorAtHead_doesNotBlockCompaction() {
        // 回归：恢复段横跨历史最前端时，不能启用整体保护。
        //
        // 真实事故：恢复块后原文回灌到历史最前端（restoredStart=0），旧实现让恢复段
        // 不受预算限制整体进 tail → 回溯走到下标 0 → splitIndex=0 → 调用方判为
        // 「无可压缩内容」永久跳过压缩。实测连续 25 次放弃、上下文从 220k 涨到 253k。
        // 期望：仍按普通预算划分，得出**非 0** 的拆分点，压缩能继续工作。
        val messages = listOf(
            user("r1", restored = true),
            assistant("r2", restored = true),
            user("r3", restored = true),
            user("u1"), assistant("a1")
        )
        val split = CompactionTailSelector.compute(messages, budget = 1, estimate = ::estimate)
        assertTrue("恢复段锚在最前端时不得返回 0（否则压缩被永久跳过）", split > 0)
        // 预算为 1 时只有最后一条能进 tail。
        assertEquals(4, split)
    }

    @Test
    fun restoredSegmentIsProtectedFromTheStartOfTheSegment() {
        // 恢复段**不在**最前端（前面还有可压缩的旧消息）：段首必须整体进 tail，
        // 否则下轮压缩会把刚恢复的消息再折回去，恢复白做。
        val messages = listOf(
            user("old1"),                        // 段首之前的旧消息（应留 head）
            user("r1", restored = true),          // 恢复段首
            assistant("r2", restored = true),
            user("u1"), assistant("a1")           // 新消息
        )
        // 预算给足（全部消息估算合计 22）——本用例验证的是「回溯在恢复段首截停」：
        // old1 留在 head，tail 从 r1 开始。
        val split = CompactionTailSelector.compute(messages, budget = 60, estimate = ::estimate)
        assertEquals(1, split)
    }

    @Test
    fun restoredSegmentExemptFromBudget() {
        // 恢复段的消息**即使超预算也不得被截断**（豁免语义），与「段首截停」是两条独立路径。
        //
        // 布局：旧消息 + 恢复段 + 新消息。预算取 8：新消息 a1/u1 恰好装满（各 4），
        // 轮到恢复段 r2 时 8+4>8 —— 若无豁免会 break、split=3（恢复段被撕开）；
        // 有豁免则继续走过 r2、r1，在段首截停得到 split=1。
        val messages = listOf(
            user("old1"),
            user("r1", restored = true),
            assistant("r2", restored = true),
            user("u1"), assistant("a1")
        )
        val split = CompactionTailSelector.compute(messages, budget = 8, estimate = ::estimate)
        assertEquals(1, split)
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
