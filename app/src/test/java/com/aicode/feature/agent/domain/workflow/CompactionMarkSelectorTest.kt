package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 压缩标记选择的回归测试。
 *
 * 核心用例是「tail 首条未落库」的持久化竞态：旧实现此时会跳过整个标记，导致 head 在下个
 * 轮次被 buildHistory 原样读回（压缩白做）；新实现按 head 自身是否落库逐条标记，应从
 * 竞态中恢复。
 */
class CompactionMarkSelectorTest {

    private fun user(id: String) = AgentMessage.UserMessage(id = id, content = "q-$id")
    private fun assistant(id: String) = AgentMessage.AssistantMessage(id = id, content = "a-$id")

    @Test
    fun marksHeadMessagesThatArePersisted() {
        val head = listOf(user("u1"), assistant("a1"), user("u2"))

        val ids = CompactionMarkSelector.selectIdsToMark(
            head = head,
            persistedIds = setOf("u1", "a1", "u2", "tail1")
        )

        assertEquals(listOf("u1", "a1", "u2"), ids)
    }

    @Test
    fun skipsHeadMessagesNotYetPersisted() {
        // head 尾部的新消息可能还没落库：只标记 DB 里确实存在的，避免写出指向不存在行的标记。
        val head = listOf(user("u1"), assistant("a1"), user("unpersisted"))

        val ids = CompactionMarkSelector.selectIdsToMark(
            head = head,
            persistedIds = setOf("u1", "a1")
        )

        assertEquals(listOf("u1", "a1"), ids)
    }

    @Test
    fun ignoresBlankIds() {
        val head = listOf(user(""), assistant("a1"))

        val ids = CompactionMarkSelector.selectIdsToMark(
            head = head,
            persistedIds = setOf("", "a1")
        )

        assertEquals(listOf("a1"), ids)
    }

    @Test
    fun returnsEmptyWhenHeadEntirelyUnpersisted() {
        // 极端情况（首轮即压缩）：DB 里一条都没有，此时无法安全标记，调用方会告警。
        val head = listOf(user("u1"), assistant("a1"))

        val ids = CompactionMarkSelector.selectIdsToMark(
            head = head,
            persistedIds = emptySet()
        )

        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun persistsOrderForLoggingAndChunking() {
        // 顺序需与 head 一致且稳定，便于日志核对与 chunked 分块的可预期性。
        val head = listOf(user("c"), assistant("a"), user("b"))

        val ids = CompactionMarkSelector.selectIdsToMark(
            head = head,
            persistedIds = setOf("a", "b", "c")
        )

        assertEquals(listOf("c", "a", "b"), ids)
    }

    @Test
    fun recoversWhenOnlyTailIsMissingFromDb() {
        // 这就是线上真实触发的场景：tail 首条未落库（持久化竞态），但 head 早已落库。
        // 旧实现因 tail 查不到而跳过标记 → head 下轮被读回；新实现照常标记 head。
        val head = listOf(user("u1"), assistant("a1"))
        val persistedIdsIncludingHeadButNotTail = setOf("u1", "a1")

        val ids = CompactionMarkSelector.selectIdsToMark(head, persistedIdsIncludingHeadButNotTail)

        assertEquals(listOf("u1", "a1"), ids)
    }
}
