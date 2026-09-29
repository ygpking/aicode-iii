package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.id

/**
 * 压缩落库时「哪些 head 消息该被标记 isCompacted」的判定，独立成纯逻辑便于单测。
 *
 * 背景（实测 bug）：旧实现以 **tail 首条是否已落库** 决定能否写已压缩标记，遇持久化竞态
 * （tail 首条尚未落库）就整个跳过标记。跳过之后 head 的 `isCompacted` 保持 0，下一个轮次
 * [com.aicode.feature.agent.domain.session.MessagePersistenceUseCase.buildHistory] 会把整段已被
 * 摘要替换掉的历史原样读回，于是压缩花了摘要的钱却没能缩短上下文——实测同一会话
 * 231677 → 239446 → 327335 tokens 不降反升。
 *
 * 正确做法是按 **head 自己在 DB 中是否已落库** 决定逐条标记：head 的消息绝大多数早已落库，
 * 因此几乎总能标记成功；同时仍只标记 DB 中确实存在的 id，保留旧实现「绝不误标未落库消息
 * （进而误标全部历史）」的安全初衷。
 */
object CompactionMarkSelector {

    /**
     * 返回 [head] 中应当标记为已压缩的消息 id。
     *
     * 只保留同时满足以下两条的 id：id 非空、且该 id 已存在于 [persistedIds]。
     * 保持 [head] 的原顺序，便于日志与断言核对。
     */
    fun selectIdsToMark(head: List<AgentMessage>, persistedIds: Set<String>): List<String> =
        head.map { it.id }.filter { it.isNotEmpty() && it in persistedIds }
}
