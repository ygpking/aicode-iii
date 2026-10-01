package com.aicode.feature.agent.domain.memory

import java.util.Locale

/**
 * 存量记忆的**只读**整理评估：指出「值得人工关注」的记忆，**不修改任何文件**。
 *
 * 为什么存在：AiCode 只有「模型自觉调 `memory(save)`」一条入账路径，没有任何机制回看已有记忆
 * 是否重复、过大、缺触发词。本对象补上这个只读诊断层——与 [MemoryRetention] 同一策略（只评估、
 * 不自动改），因为记忆是不可再生的用户资产。
 *
 * 与上游 dream 的关系：上游用「按日期命名的笔记 + 信号计数」驱动候选排序；AiCode 没有日期维度
 * （全是常青具名记忆），故信号换成**能由现有数据直接判定**的三项（见 [Kind]）。
 *
 * **实测基线（2026-10-01，20 条真实记忆）**：
 * - 近重复最高仅 0.219（[DEFAULT_NEAR_DUPLICATE] = 0.5 下 0 对命中）——当前语料确实互不重复，
 *   该信号在现状**不会触发**，保留是为语料增长提供保护，而非「有产出」的功能；
 * - [Kind.OVERSIZED_BODY] 命中 3 条（正文 5998 / 5836 / 6465 字符）——超出
 *   [MemoryRecall.MAX_INDEX_CHARS]，尾部永远参与不了召回；
 * - [Kind.MISSING_TRIGGERS] 命中 0 条（20/20 均有 triggers）。
 *
 * **不要用 mtime 判新旧**：2026-09-30 一次外部批量重写把 15 个文件的时间戳刷到同一秒，此后
 * mtime 无法反映内容新旧。任何依赖 mtime 的淘汰判断都只能当提示，不可作删除依据。
 */
internal object MemoryCuration {

    /** 近重复判定的 Jaccard 阈值（desc+正文，与召回 MMR 同一分词口径）。 */
    const val DEFAULT_NEAR_DUPLICATE = 0.5

    enum class Kind {
        /** 两条记忆的正文高度重合（Jaccard ≥ 阈值），建议合并。 */
        NEAR_DUPLICATE,

        /** 正文超过召回索引上限，尾部无法被匹配到，建议拆分或精简。 */
        OVERSIZED_BODY,

        /** 没有 triggers：召回门控无法命中，只能靠字面重合。 */
        MISSING_TRIGGERS,
    }

    /**
     * @param memories 相关记忆名（[Kind.OVERSIZED_BODY] / [Kind.MISSING_TRIGGERS] 只有 1 个）。
     * @param detail 人类可读的依据（相似度数值 / 字符数），供直接展示。
     */
    data class Finding(val kind: Kind, val memories: List<String>, val detail: String)

    /**
     * 评估一组记忆。纯函数、零 IO、不改任何文件；结果顺序稳定（先近重复、再超大、再缺 triggers）。
     *
     * 复杂度 O(n²)：n 是记忆条数（当前 20）。记忆规模由人工维护，不会到影响启动的量级；
     * 若日后真的过千，应改为先按 token 倒排分桶再配对。
     */
    fun evaluate(
        memories: List<Memory>,
        nearDuplicate: Double = DEFAULT_NEAR_DUPLICATE,
    ): List<Finding> {
        if (memories.isEmpty()) return emptyList()
        val out = ArrayList<Finding>()

        for (i in memories.indices) {
            for (j in i + 1 until memories.size) {
                val a = memories[i]
                val b = memories[j]
                val sim = MemoryRecall.jaccardOf(
                    a.description + "\n" + a.content,
                    b.description + "\n" + b.content,
                )
                if (sim >= nearDuplicate) {
                    out += Finding(
                        Kind.NEAR_DUPLICATE,
                        listOf(a.name, b.name),
                        String.format(Locale.US, "相似度 %.2f", sim),
                    )
                }
            }
        }

        for (m in memories) {
            if (m.content.length > MemoryRecall.MAX_INDEX_CHARS) {
                out += Finding(
                    Kind.OVERSIZED_BODY,
                    listOf(m.name),
                    "正文 ${m.content.length} 字符 > 召回索引上限 ${MemoryRecall.MAX_INDEX_CHARS}，尾部无法被匹配",
                )
            }
        }

        for (m in memories) {
            if (m.triggers.isEmpty()) {
                out += Finding(Kind.MISSING_TRIGGERS, listOf(m.name), "无 triggers，召回门控无法命中")
            }
        }

        return out
    }
}
