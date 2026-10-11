package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER

/**
 * 压缩拆分点的最后调整，独立成纯逻辑便于单测（与 [CompactionTailSelector]、[CompactionMarkSelector]
 * 同一思路）。负责把 [CompactionTailSelector] 算出的原始拆分点修正为「合法且不劈开配对」的位置。
 *
 * 两条约束：
 *
 * 1. **tool 配对**：tail 不得以孤立的 [AgentMessage.ToolResultMessage] 开头。OpenAI 要求 role:"tool"
 *    必须紧跟含对应 tool_calls 的 assistant；若 tail 以 tool 结果开头，压缩后它前面的 assistant（摘要）
 *    不含 toolCalls → 400。→ 向前回溯到配对的 assistant。
 *
 * 2. **marker/summary 配对**：压缩对是「marker(user) 紧接 summary(assistant)」，二者必须同进同出：
 *    - 都进 head → `removeCompactionPairs` 整对删除（旧摘要退场，由新摘要接管）；
 *    - 都进 tail → 作为一对保留在上下文里；
 *    - **被劈开**（marker 进 head、summary 进 tail）→ 两个都删不掉：marker 作为纯噪音 user 消息
 *      进入本次摘要材料（挤占预算、可能误导模型），summary 则在活上下文里多占一份。
 *      实测可复现：tail 预算边界恰落在 marker 的估算宽度（约 9 token）内即命中——低频但确实发生，
 *      且压缩频繁触发时边界反复扫过不同位置，命中概率累积上升。
 *      → 把 split 前移一位，使 summary 也进 head，整对被清理。
 *
 *    前移**不得清空 tail**：若该对正好在可见流最末尾，前移会连最近消息一起折走，代价更大
 *    （宁可留一次 orphan，也不丢最近上下文）。
 */
internal object CompactionSplitAdjuster {

    /**
     * 返回修正后的拆分下标（head = `[0, 结果)`，tail = `[结果, size)`）。
     *
     * @param messages 压缩前的完整消息序列（未折叠）
     * @param initialSplitIndex [CompactionTailSelector] 给出的原始拆分点
     */
    fun adjust(messages: List<AgentMessage>, initialSplitIndex: Int): Int {
        var splitIndex = initialSplitIndex.coerceIn(0, messages.size)

        // 约束 1：tail 不得以孤立 tool 结果开头，向前回溯到配对的 assistant。
        while (splitIndex > 0 && messages[splitIndex] is AgentMessage.ToolResultMessage) {
            splitIndex--
        }

        // 约束 2：marker/summary 对不许劈开。split 落在二者之间时前移，把 summary 也纳入 head。
        // 上界用 `messages.size - 1`：前移后 splitIndex 至多到 size-1，tail 至少保留最后一条，
        // 绝不清空（若该对正好在可见流最末尾，宁可留一次 orphan，也不把最近消息一起折走）。
        if (
            splitIndex in 1 until messages.size - 1 &&
            isMarker(messages[splitIndex - 1]) &&
            messages[splitIndex] is AgentMessage.AssistantMessage
        ) {
            splitIndex++
        }

        return splitIndex
    }

    /**
     * 是否为压缩锚点。与 `ContextCompactor.removeCompactionPairs` / `extractPreviousSummary` 同源：
     * 内存态 [AgentMessage] 不带 isCompactionMarker 标志，只认「内容是 [CONTEXT_COMPACTION_MARKER]
     * 的 UserMessage」。三处判据必须一致，否则会出现「一处认、另一处不认」的劈开漏洞。
     */
    private fun isMarker(message: AgentMessage): Boolean =
        message is AgentMessage.UserMessage && message.content == CONTEXT_COMPACTION_MARKER
}
