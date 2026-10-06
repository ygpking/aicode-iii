package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage

/**
 * 压缩时 tail 保护区起点的判定，独立成纯逻辑便于单测（与 [CompactionMarkSelector] 同一思路）。
 *
 * 原始行为：从尾部按预算回溯，装不下的更早消息留在 head（将被折叠）。
 * 恢复扩展：**刚从压缩块恢复的消息**（[AgentMessage.restoredFromCompaction]）必须优先落进
 * 保护区——否则下次压缩可能立刻把它们再折回去，恢复动作白做。回溯越过恢复段尾后
 * 在段首停下，恢复段整体进 tail、段首之前的消息仍照常留 head。
 */
object CompactionTailSelector {

    /**
     * 返回 tail 的起点下标（[0, messages.size]）。
     *
     * @param budget tail 保护区 token 预算（调用方已按窗口比例折算）。
     * @param estimate 单条消息的 token 估算（注入以便测试用字符数近似）。
     */
    fun compute(
        messages: List<AgentMessage>,
        budget: Int,
        estimate: (AgentMessage) -> Int
    ): Int {
        var total = 0
        var splitIndex = messages.size

        // 刚恢复过的消息（块归属非空但未折叠）优先落进保护区：否则下次压缩可能立刻
        // 把它们再折回去，恢复动作白做。恢复段整体不拆，从段首整体进入 tail。
        val restoredStart = messages.indexOfFirst {
            when (it) {
                is AgentMessage.UserMessage -> it.restoredFromCompaction
                is AgentMessage.AssistantMessage -> it.restoredFromCompaction
                is AgentMessage.ToolResultMessage -> it.restoredFromCompaction
            }
        }

        for (index in messages.indices.reversed()) {
            val next = estimate(messages[index])
            if (total + next > budget && splitIndex < messages.size) break
            total += next
            splitIndex = index
            if (restoredStart >= 0 && index == restoredStart) break
        }

        return splitIndex
    }
}
