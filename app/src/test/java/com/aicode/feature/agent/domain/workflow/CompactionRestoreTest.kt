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
        // 豁免语义：保护启用时，回溯途中预算耗尽也**不截断**恢复段（段不拆开），
        // 继续走到段首截停。启用前提由新上界（段首到末尾合计 <= 预算）保证。
        //
        // 布局：旧消息 + 恢复段 + 新消息，预算 16（=段首到末尾合计）：
        // 从尾回溯 a1(4)+u1(4)+r2(4)=12，r1 处 12+4=16 恰不超限，到段首截停。
        val messages = listOf(
            user("old1"),
            user("r1", restored = true),
            assistant("r2", restored = true),
            user("u1"), assistant("a1")
        )
        // 段首到末尾合计 = 4+4+4+4 = 16，预算 16 → 上界恰好成立，保护启用；
        // （预算小于 16 时上界不成立、保护退位，见 restoredSegmentSplitWhenTailOverBudget）
        val split = CompactionTailSelector.compute(messages, budget = 16, estimate = ::estimate)
        assertEquals(1, split)
    }

    @Test
    fun restoredSegmentSplitWhenTailOverBudget() {
        // 上界按「段首到末尾」验的另一面：预算装不下段+段后时，保护退位、段可被折回。
        // 同布局预算 6：段首到末尾合计 16 > 6 → 保护退位 → 按普通预算回溯，
        // a1(4)+u1(4)=8 已超 6，回溯 a1 后 u1 超限截停，split=4（恢复段整体进 head 被折回）。
        // 这是修复的预期代价：段后积累超预算时，宁可折回恢复段也不能让压缩空转。
        val messages = listOf(
            user("old1"),
            user("r1", restored = true),
            assistant("r2", restored = true),
            user("u1"), assistant("a1")
        )
        val split = CompactionTailSelector.compute(messages, budget = 6, estimate = ::estimate)
        assertEquals(4, split)
    }

    @Test
    fun frontAnchorDoesNotDisableProtectionForMiddleSegment() {
        // 回归：同时存在「锚在最前端的恢复段」与「位于中部的恢复段」时，
        // 前者不得让后者一并失去保护（旧实现全局取 indexOfFirst，中部段会跟着失效）。
        // 布局：A 段（下标 0 起）+ 旧消息 + B 段（中部）
        val messages = listOf(
            user("a1", restored = true),          // A 段（锚在最前端，不保护）
            user("a2", restored = true),
            user("old1"),                         // 可压缩的旧消息
            user("b1", restored = true),          // B 段（中部，应受保护）
            assistant("b2", restored = true),
            user("new1"), assistant("new2")
        )
        // 预算给足时的旧行为对照：不受保护会一路回溯到 0；受保护则停在 b1（下标 3）。
        val split = CompactionTailSelector.compute(messages, budget = 100_000, estimate = ::estimate)
        assertEquals(3, split)
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
    fun oversizedAfterSegmentDisablesProtection() {
        // 事故 C 回归（2026-10-11 真实事故）：恢复段自身装得下预算，但**段后积累**超预算。
        // 旧上界只验段自身 → 保护启用 → 段后全部免预算进 tail → 每轮压缩空转
        // （实测 9 轮压缩全空转，上下文 701k→754k 只涨不降）。
        // 新语义：上界按「段首到末尾全部」验，超预算则保护退位，恢复段按普通预算折回。
        val messages = listOf(
            user("old1"),
            user("r1", restored = true),           // 恢复段首
            assistant("r2", restored = true),
            user("big1"),                          // 段后大消息（远超预算）
            user("big2"),
            assistant("a1")
        )
        val split = CompactionTailSelector.compute(messages, budget = 8, estimate = ::estimate)
        // 保护退位后按普通预算回溯：a1(3) 后 big2(6) 3+6>8 截停，split=5。
        // 旧实现（上界只验段自身）会保护段首 → 免预算走完段+段后全部 → split=1，
        // tail 远超预算、压缩空转。
        assertEquals(5, split)
    }

    @Test
    fun noRestoredMessagesFullBudgetReturnsZero() {
        val messages = listOf(user("u1"), assistant("a1"))
        assertEquals(0, CompactionTailSelector.compute(messages, budget = 10_000, estimate = ::estimate))
    }
}
