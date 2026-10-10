package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage

/**
 * 压缩时 tail 保护区起点的判定，独立成纯逻辑便于单测（与 [CompactionMarkSelector] 同一思路）。
 *
 * 原始行为：从尾部按预算回溯，装不下的更早消息留在 head（将被折叠）。
 * 恢复扩展：**刚从压缩块恢复的消息**（[AgentMessage.restoredFromCompaction]）必须优先落进
 * 保护区——否则下次压缩可能立刻把它们再折回去，恢复动作白做。回溯越过恢复段尾后
 * 在段首停下，恢复段整体进 tail、段首之前的消息仍照常留 head。
 *
 * 例外：恢复段横跨历史最前端（段首下标 0）时**不能**启用整体保护。保护会让回溯
 * 一路走到下标 0、`splitIndex` 恒为 0，而调用方把 `splitIndex <= 0` 判为「无可压缩内容」
 * 并跳过压缩——于是压缩被**永久**跳过。实测事故：恢复 87 条原文后（其位置在历史最前端），
 * 连续 25 次「压缩放弃：拆分点落在消息最前端」，上下文从 220k 一路涨到 253k 再也压不动。
 * 此时该段按普通预算划分：保护它的收益，小于「压缩彻底不动」的代价。
 *
 * 多段并存：按「各恢复段独立保护」处理——取所有**段首 > 0** 的恢复段中最靠左的段首作为
 *
 * 保护的成立条件：仅当**恢复段自身**的估算不超过 tail 预算时才启用。
 * 恢复标记由「未折叠 + 带块归属」实时推导（见 MessagePersistenceUseCase），而保护恰恰阻止折叠，
 * 因此一旦启用就**永久**生效、没有退出路径。恢复段超过预算时，每轮压缩只能折掉前面寥寥几条，
 * 上下文只涨不降——实测：恢复 87 条原文后会话 341k→346k→355k→370k，每轮只折叠上一轮刚生成的摘要。
 * 此时与「锚在最前端」取同一取舍：宁可让恢复段被折回，也不能让压缩失效。
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
        // 仅保护「段首 > 0」的恢复段：取最靠左的这样一个段首作为回溯终点。
        // 锚在最前端的恢复段不启用保护——否则回溯必然走到 0、压缩被永久跳过（见类注释）。
        // 多段并存时，最靠左的受保护段首之后的所有恢复段也自然落进 tail。
        val restoreAnchor = messages.indices.firstOrNull { index ->
            index > 0 && isRestored(messages[index]) && !isRestored(messages[index - 1])
        }
        // 仅当恢复段自身装得下预算时才保护：保护无退出路径，超预算段会让压缩永久失效（见类注释）。
        val protectedStart = restoreAnchor?.takeIf { anchor ->
            val segmentEnd = (anchor until messages.size).firstOrNull { !isRestored(messages[it]) }
                ?: messages.size
            messages.subList(anchor, segmentEnd).sumOf { estimate(it) } <= budget
        }

        for (index in messages.indices.reversed()) {
            val inRestored = protectedStart != null && index >= protectedStart
            val next = estimate(messages[index])
            // 恢复段整体进保护区，不受预算截断：预算耗尽若恰好落在段中间会把恢复段拆开，
            // 半进半留等于恢复白做。是否启用保护已由上面的预算上界限定：段自身装不下预算时
            // protectedStart 为空、这里退化为普通预算回溯。段首之前的消息仍照常按预算。
            if (!inRestored && total + next > budget && splitIndex < messages.size) break
            total += next
            splitIndex = index
            if (protectedStart != null && index == protectedStart) break
        }

        return splitIndex
    }

    private fun isRestored(message: AgentMessage): Boolean = when (message) {
        is AgentMessage.UserMessage -> message.restoredFromCompaction
        is AgentMessage.AssistantMessage -> message.restoredFromCompaction
        is AgentMessage.ToolResultMessage -> message.restoredFromCompaction
    }
}
