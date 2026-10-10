package com.aicode.feature.agent.domain.workflow

import kotlinx.serialization.Serializable

/** 账本里一条验证命令的执行记录。 */
@Serializable
data class LedgerCommand(
    /** 命令正文，截断到 [EvidenceLedgerRepository.CMD_MAX_CHARS]。 */
    val cmd: String,
    /** 命令类别（见 [CommandOutcome.categoryOf]）；比完整命令串可靠——重跑几乎必改参数。 */
    val category: String,
    val ok: Boolean,
    val ts: Long,
)

/** 账本里最近一次未通过的裁决。存声明**原文**，供下次提示时原样展示。 */
@Serializable
data class LedgerFail(
    val claim: String,
    val verdict: String,
    val ts: Long,
)

/** 账本里一次成功写文件的记录（供跨回合汇报核对凭证）。 */
@Serializable
data class LedgerWrite(
    val tool: String,
    val path: String,
    val ts: Long,
)

/**
 * 单个会话的账本内容。
 *
 * 与 [EvidenceLedgerRepository] 分文件：本文件只含数据结构与纯判定逻辑，不依赖 Android/Dagger，
 * 便于单测直接验证（仓储文件含 Context/注入，只能在 Gradle 环境编译）。
 */
@Serializable
data class EvidenceLedger(
    /**
     * 自上次「核对通过」以来连续不通过的次数；通过时归零（见 [afterRecord]）。
     *
     * 超出会话生命周期则随会话删除一并清除。
     */
    val consecutiveFails: Int = 0,
    val lastFail: LedgerFail? = null,
    /** 最近的验证命令执行记录（环形，最多 [EvidenceLedgerRepository.MAX_COMMANDS] 条）。 */
    val verifyCommands: List<LedgerCommand> = emptyList(),
    /**
     * 是否已观察到「同类命令先失败后成功」的针对性复现。
     *
     * 只作**置信度**记录，不作拦截依据（作弊者也能先故意红再绿）。
     * 展示层（把它带进 `EvidenceGuardReport`）待接，当前只落账本。
     */
    val redThenGreen: Boolean = false,
    /**
     * 最近的写文件记录（环形，最多 [EvidenceLedgerRepository.MAX_WRITES] 条）。
     *
     * 供 [hasRecentWrite] 判定「跨回合汇报」：run 级 records 只活一次收尾，
     * 而多轮任务里模型下一轮汇报上一轮的改动是常态（真机实证：不认会让每轮汇报都被拉回）。
     */
    val recentWrites: List<LedgerWrite> = emptyList(),
) {
    companion object {
        /** 跨回合写凭证的时效：只认最近这么久的写入。太宽会拿旧成果给新声明背书。 */
        const val PRIOR_WRITE_WINDOW_MS = 30L * 60 * 1000

        /**
         * 本会话近期是否成功写过 [path]（[path] 为 null 时只判「有没有写过任何文件」）。
         *
         * R3 的路径核对**不放宽**：点名了文件就要么本回合写、要么窗内写过该路径。
         */
        fun hasRecentWrite(writes: List<LedgerWrite>, now: Long, path: String? = null): Boolean =
            writes.any { w -> now - w.ts <= PRIOR_WRITE_WINDOW_MS && (path == null || pathMatches(w.path, path)) }

        /** 路径匹配：等值、或一方是另一方的路径后缀（`app/x.kt` ↔ `/root/workspace/app/x.kt`）。 */
        private fun pathMatches(actual: String, claimed: String): Boolean {
            val a = actual.trim()
            val c = claimed.trim()
            if (a == c) return true
            return a.endsWith("/$c") || c.endsWith("/$a")
        }

        /**
         * 根据本次裁决合并出新账本（纯函数，便于单测）。
         *
         * - `failed=false`（收尾核对通过）时 [consecutiveFails] **归零**：否则诚实修复后的长期会话
         *   会永久背着「已累计 N 次」标签，提示文案失真。归零本身也是设计里的「洗白成本」——
         *   作弊者想清零必须真跑一次验证（会留下类别记录，可被红绿判定捕捉），不是零成本绕过。
         * - [lastFail] 归零后**保留不清**（提示条件 `consecutiveFails >= 1` 已不再展示它）。
         * - 命令记录按 `(cmd, ts)` 去重：同一次 run 内拉回多次时会重复携带全量记录，
         *   不去重会把环形容量挤满。
         */
        fun afterRecord(
            old: EvidenceLedger,
            failed: Boolean,
            claim: String,
            commands: List<LedgerCommand>,
            writes: List<LedgerWrite>,
            now: Long,
            maxCommands: Int,
            maxWrites: Int,
        ): EvidenceLedger {
            val merged = (old.verifyCommands + commands)
                .distinctBy { it.cmd to it.ts }
                .takeLast(maxCommands)
            val mergedWrites = (old.recentWrites + writes)
                .distinctBy { it.tool to it.path to it.ts }
                .takeLast(maxWrites)
            return old.copy(
                consecutiveFails = if (failed) old.consecutiveFails + 1 else 0,
                lastFail = if (failed) LedgerFail(claim = claim.take(500), verdict = "FAIL", ts = now) else old.lastFail,
                verifyCommands = merged,
                redThenGreen = hasRedThenGreenIn(merged),
                recentWrites = mergedWrites,
            )
        }

        /**
         * 「先红后绿」判定：同一类别的验证命令，最近一次成功、且它的**前一条同类别**记录是失败。
         *
         * 用类别而非完整命令串比对——模型重跑时几乎必改参数（换个 `--tests` 类名、加 `--rerun-tasks`），
         * 串比对召回率趋零。「相邻」这个定义天然内置时间窗：隔很久的旧红与新绿中间必然插过
         * 其他同类别命令，无需时钟。
         *
         * 该结果只作**置信度**，不作拦截依据——作弊者也能先故意制造失败再跑成功。
         */
        fun hasRedThenGreenIn(ordered: List<LedgerCommand>): Boolean {
            for (i in ordered.indices) {
                if (!ordered[i].ok) continue
                val priorSameCategory = ordered.take(i).lastOrNull { it.category == ordered[i].category }
                if (priorSameCategory != null && !priorSameCategory.ok) return true
            }
            return false
        }
    }
}
