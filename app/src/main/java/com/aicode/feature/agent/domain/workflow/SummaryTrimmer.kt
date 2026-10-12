package com.aicode.feature.agent.domain.workflow

/**
 * 压缩摘要的长度封顶（纯逻辑，便于单测）。
 *
 * ## 为什么需要
 *
 * 实测（2026-10-11 会话 22ddb225）：单日压缩 42+ 次，摘要长度从 13025 单调膨胀到 104795 字符。
 * 原因有二：
 * 1. 提示词要求「用户原话逐字保留」「路径命令报错一律贴原文」，且更新时「逐条与新历史对照重算」；
 * 2. 全链路（落库 [com.aicode.feature.agent.domain.workflow.ContextCompactor]、回放
 *    [com.aicode.feature.agent.domain.session.MessagePersistenceUseCase.buildHistory]）**没有任何长度上限**。
 *
 * 后果：摘要是「模型生成 → 直接落库 → 每轮回放」的文本，膨胀后自身 ≈ 触发阈值，
 * 压缩后大小立即回到阈值 → 每 ~3 分钟重触发一次（实测重触发间隔随摘要增大从 12.2min 掉到 2.9min，
 * 一阶自相关 r1=0.889 = 反馈环）。
 *
 * ## 为什么不是朴素尾部截断
 *
 * 摘要按固定小节输出（见 `agent/compact-summary.md`）：
 * 用户原话 → 任务状态 → 涉及的文件 → 执行过的命令 → 报错与修复 → 关键技术概念 → 决定与取舍 → 下一步动作。
 * 接手者最需要的是**头**（任务状态）与**尾**（下一步动作）；朴素 `take(N)` 从尾部丢，
 * 恰好先丢掉这两节，属于严重回归。故本类**保头 + 保尾、折叠中段**。
 */
internal object SummaryTrimmer {

    /** 中段折叠标记，插在保留的头尾之间。 */
    const val ELISION = "\n\n…（摘要中段因长度上限折叠，如需细节请用 restoreCompactedRange 检索原始历史）…\n\n"

    /**
     * 把 [text] 裁到不超过 [maxChars] 字符（按字符近似，调用方按 token 预算折算传入）。
     *
     * 规则：
     * - `text.length <= maxChars` 或 [maxChars] 太小不足以容纳头尾时，原样返回（不制造更差结果）；
     * - 否则保留**头部** [_HEAD_RATIO] 与**尾部** [_TAIL_RATIO] 比例的部分，中段替换为 [ELISION]。
     *
     * 头部优先保「任务状态」，尾部优先保「下一步动作」，故尾部比例略高于头部之外的中段。
     */
    fun trim(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        // 头尾至少各留出「标记长度 + 各 1 个字符」的余量，否则折叠反而更短/更乱。
        val minKept = ELISION.length + 2
        if (maxChars < minKept * 2) return text

        val budget = maxChars - ELISION.length
        val headLen = (budget * HEAD_RATIO).toInt().coerceAtLeast(1)
        val tailLen = (budget - headLen).coerceAtLeast(1)
        val head = text.take(headLen)
        val tail = text.takeLast(tailLen)
        return head + ELISION + tail
    }

    /** 头部（任务状态等总述）占可保留预算的比例。 */
    private const val HEAD_RATIO = 0.5
    // 尾部 = 1 - HEAD_RATIO，容纳「决定与取舍 / 下一步动作」。
}
