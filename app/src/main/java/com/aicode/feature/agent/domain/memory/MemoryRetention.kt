package com.aicode.feature.agent.domain.memory

import com.aicode.core.text.NameKey

/**
 * 记忆陈旧度评估与显式清理的纯逻辑（零 IO）。
 *
 * 移植自 OpenSquilla `memory/retention.py::prune_expired_memory_files`（Apache-2.0），
 * 但**刻意偏离上游的淘汰策略**，原因如下：
 *
 * - 上游只淘汰「名字带日期的临时笔记」（`memory/YYYY-MM-DD.md`），具名常青记忆永不淘汰
 *   （检索层把具名记忆标记为非过期）。AiCode 的记忆**全部是具名常青**（`<name>.md`，见
 *   [MemorySource.sanitizeName]），没有任何「临时笔记」概念——照搬上游等于功能永不生效。
 * - 改为「按最后修改时间自动删」则更糟：会误删「长期有效但久未改动」的项目约定
 *   （如半年前定下、至今仍算数的架构约定）。**记忆不可再生**（不同于可再生的工具输出，
 *   后者由 OutputSpillBudget 按 TTL 自动清理是安全的），删掉即用户资产永久丢失。
 *
 * 故本实现采取「只读评估 + 显式清理」：
 * - [assess] 是纯函数，只判断陈旧，**不删任何东西**；
 * - 是否真删由用户/模型显式给出天数触发（见 [MemoryRepository.pruneStaleMemories]），
 *   且默认 dry-run 先预览；
 * - `pinned: true` 的记忆永不列为陈旧候选（对应上游的 `exempt_files` 豁免名单）。
 */
object MemoryRetention {

    /** 默认「陈旧」阈值：超过该天数未更新即视为陈旧（仅用于列表标注与 prune 默认值）。 */
    const val DEFAULT_STALE_DAYS: Long = 180L

    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /** 单条记忆的年龄评估结果。 */
    data class Age(
        val name: String,
        val scope: MemoryScope,
        val lastModifiedMs: Long,
        val ageDays: Long,
        val pinned: Boolean,
        val stale: Boolean,
    )

    /** 一次评估的汇总。 */
    data class Report(
        val ages: List<Age>,
        val staleCount: Int,
        val pinnedExemptCount: Int,
    ) {
        fun isStale(name: String): Boolean =
            ages.firstOrNull { NameKey.of(it.name) == NameKey.of(name) }?.stale == true
    }

    /**
     * 评估一批记忆的陈旧度。纯函数、零 IO。
     *
     * @param memories 待评估的记忆（通常来自 [MemoryRepository.listMemories]）。
     * @param nowMs 当前时刻（epoch 毫秒）。
     * @param staleDays 超过该天数未更新即视为陈旧；`<= 0` 表示关闭评估（全部不陈旧）。
     * @param lastModifiedOf 取记忆文件最后修改时间的函数；返回 `0` 表示「未知」，
     *   该记忆**不计为陈旧**（读取失败时宁可漏报也不误报，避免诱导误删）。
     */
    fun assess(
        memories: List<Memory>,
        nowMs: Long,
        staleDays: Long = DEFAULT_STALE_DAYS,
        lastModifiedOf: (Memory) -> Long = { it.file?.lastModified() ?: 0L },
    ): Report {
        if (staleDays <= 0) {
            return Report(
                ages = memories.map { Age(it.name, it.scope, 0L, 0L, it.pinned, stale = false) },
                staleCount = 0,
                pinnedExemptCount = memories.count { it.pinned },
            )
        }

        var staleCount = 0
        var pinnedExempt = 0
        val ages = memories.map { memory ->
            val mtime = lastModifiedOf(memory)
            // 未来时间（mtime > now）按 0 天计，不判陈旧：时钟回拨/文件系统时间异常时不应误伤。
            val ageDays = if (mtime > 0L && nowMs > mtime) (nowMs - mtime) / MILLIS_PER_DAY else 0L
            // 未知 mtime（0）不参与陈旧判定——读不到就是信息缺失，不能当作「足够旧」。
            val stale = !memory.pinned && mtime > 0L && ageDays > staleDays
            if (memory.pinned) pinnedExempt++
            if (stale) staleCount++
            Age(memory.name, memory.scope, mtime, ageDays, memory.pinned, stale)
        }
        return Report(ages, staleCount, pinnedExempt)
    }
}
