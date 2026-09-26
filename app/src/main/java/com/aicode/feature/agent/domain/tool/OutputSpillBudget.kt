package com.aicode.feature.agent.domain.tool

/** 超长输出回灌正文时的保留方向。 */
internal enum class Direction { HEAD, TAIL, HEAD_TAIL }

/**
 * 一条已落盘的工具输出记录。
 *
 * @param key 落盘标识（文件名），删除时按此回传。
 * @param bytes 该条目的字节数。
 * @param createdAtMs 落盘时刻（epoch 毫秒）。
 * @param toolName 产出该输出的工具名，用于方向选择。
 */
internal data class SpillEntry(
    val key: String,
    val bytes: Long,
    val createdAtMs: Long,
    val toolName: String,
)

/**
 * 落盘输出池的当前占用快照。
 */
internal data class SpillState(
    val totalBytes: Long,
    val fileCount: Int,
    val expiredCount: Int,
    val overBudget: Boolean,
)

/**
 * 落盘输出的容量治理：只给出「应删除哪些 key」的判定，不做任何 IO。
 *
 * 三条预算同时生效：总字节数、条目数、存活时长（TTL）。
 * 规则：TTL 过期者直接删；其余一旦超预算，**优先删最旧**（同刻按 key 字典序稳定排序，
 * 保证同一输入必得同一结果，便于单测）。
 */
internal class OutputSpillBudget(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    fun decide(entries: List<SpillEntry>, nowMs: Long): List<String> {
        val doomed = ArrayList<String>()
        val alive = ArrayList<SpillEntry>(entries.size)
        for (entry in entries) {
            if (nowMs - entry.createdAtMs > ttlMs) doomed += entry.key else alive += entry
        }
        alive.sortWith(compareBy({ it.createdAtMs }, { it.key }))
        var total = alive.sumOf { it.bytes }
        while (alive.size > maxFiles || total > maxTotalBytes) {
            val victim = alive.removeAt(0)
            total -= victim.bytes
            doomed += victim.key
        }
        return doomed
    }

    /** 只读占用快照；过 TTL 的条目计入 [SpillState.expiredCount]，不进入 [SpillState.totalBytes]。 */
    fun state(entries: List<SpillEntry>, nowMs: Long): SpillState {
        var total = 0L
        var aliveCount = 0
        var expired = 0
        for (entry in entries) {
            if (nowMs - entry.createdAtMs > ttlMs) {
                expired++
            } else {
                total += entry.bytes
                aliveCount++
            }
        }
        return SpillState(
            totalBytes = total,
            fileCount = aliveCount,
            expiredCount = expired,
            overBudget = total > maxTotalBytes || aliveCount > maxFiles,
        )
    }

    /** 按工具类型选择正文保留方向：命令类看尾部，文件读取两端都留，其余默认看头部。 */
    fun headTailDirection(toolName: String): Direction = when (toolName) {
        in TAIL_TOOLS -> Direction.TAIL
        in HEAD_TAIL_TOOLS -> Direction.HEAD_TAIL
        else -> Direction.HEAD
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_FILES: Int = 256
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        private val TAIL_TOOLS = setOf("Bash", "terminal")
        private val HEAD_TAIL_TOOLS = setOf("readFile")
    }
}
