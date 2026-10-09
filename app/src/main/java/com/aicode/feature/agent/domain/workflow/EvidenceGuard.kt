package com.aicode.feature.agent.domain.workflow

/**
 * 完工证据守卫：模型宣布「做完 / 验证通过」时，用本回合的工具执行记录核对是否有对应凭证。
 *
 * ## 为什么需要
 *
 * 模型的收尾声明完全靠自觉：可以没跑测试就说「全部通过」、没动文件就说「已修复」。
 * 这类「该做不做 / 谎报完成」没有工具层的客观信号可拦（判据在自然语言里），
 * 但**声明本身**给了锚点——声明了就得拿证据来。本守卫挂在「模型不再调工具、
 * 主动收尾」的那一刻，核对不过就把它拉回一轮补做或改正措辞。
 *
 * ## 设计来源
 *
 * 判别层借鉴 EricFinland/proof 的 `classifier.py`（短语锚定 + 否定抑制器）；
 * 退出码与失败信号借鉴 spyrae/truthguard 的 `check-exit-code.sh`（非零不等于失败）；
 * 作弊检测见 [TamperDetector]。裁决枚举 PASS/FAIL/SUSPECT 三值与 proof 的
 * pass/fail/suspect 对应，**INCONCLUSIVE 并入 SUSPECT**（两者都是「判不准」，
 * 守卫对它们的处置相同：不放行）；proof 保留四值是因为它的调用方要区分退出码。
 * 关键约束是「拿不准时绝不给 PASS」。
 *
 * ## 边界
 *
 * 只做「主动声明 → 核对凭证」，**不做无条件检查**：分析类任务天然不跑测试、不改文件，
 * 但它不会声称「已修复」，故不会误伤。
 *
 * 与 [CommandSleepGuard] 同一模式：纯字符串处理、不访问文件系统、便于单测。
 */
internal object EvidenceGuard {

    /** 单次请求内最多拉回的次数。与 proof 的 `max_fix_cycles` 默认值一致。 */
    const val MAX_BLOCKS = 3

    /**
     * 一条工具执行记录。只保留判据需要的字段——`isError` 在 workflow 层的
     * `ToolResultMessage` 里是丢的，故必须由调用方在批次执行处单独累积。
     */
    data class ToolRecord(
        val toolName: String,
        val isError: Boolean,
        /** 写文件的落点路径（工具的 `path` 参数），非写工具为 null。 */
        val path: String? = null,
        /** shell 命令正文（Bash 的 `command` / terminal 的 `command` 与 `input`），非命令工具为 null。 */
        val command: String? = null,
        /** 该次写文件是否被 [TamperDetector] 判定为作弊（改坏了测试）。 */
        val tampered: Boolean = false,
        /**
         * 命令输出的尾部切片（仅 Bash/terminal）。
         *
         * 必需：[isError] 对「命令跑了但失败」恒为 false（工具层把非零退出包装成 Success），
         * 故只能从输出文本里补判失败信号（见 [CommandOutcome.hasFailureSignal]）。
         */
        val outputTail: String? = null,
    )

    /** 裁决。三值与 proof 的 pass/fail/suspect 对应，INCONCLUSIVE 并入 SUSPECT（都不放行）。 */
    enum class Verdict { PASS, FAIL, SUSPECT }

    /** 写文件类工具：只有它们能作为「改了代码」的凭证。 */
    private val WRITE_TOOLS = setOf("editFile", "writeFile")

    /** 报告失败的词。句里出现这些词，说明是在如实报告失败而不是声称全通过。 */
    private val FAILURE_MARKERS = listOf("失败", "不通过", "未通过", "failed", "failure")

    /**
     * 核对收尾声明。
     *
     * @param message 本回合最后一条模型正文（收尾声明所在）。
     * @param records 本次请求内累积的工具执行记录。
     * @return 违规说明列表；空表示声明都有凭证，放行收尾。
     */
    fun evaluate(message: String, records: List<ToolRecord>): List<String> {
        if (message.isBlank()) return emptyList()

        val notices = mutableListOf<String>()

        // 失败词按**整条消息**排除而非逐句：真实汇报常把「测试通过」与「但 2 个 failed」
        // 拆成小句（逗号切分后分属两句），逐句判定会漏掉这个跨句情形。
        val mentionsFailure = FAILURE_MARKERS.any { message.contains(it, ignoreCase = true) }

        val claimsVerify = !mentionsFailure && ClaimClassifier.claimsVerification(message)
        val claimsChange = ClaimClassifier.claimsChange(message)

        // R1：声称验证通过，但本回合没有成功的验证类命令。
        if (claimsVerify && !hasSuccessfulVerifyCommand(records)) {
            notices += "你声称验证/测试通过，但本回合没有成功的命令执行记录。请实际运行验证后再报告，或明确说明「未验证」。"
        }

        // R2：声称改完并解决，但本回合没有任何成功的文件修改。
        if (claimsChange && !hasSuccessfulWrite(records)) {
            notices += "你声称已完成/已修复，但本回合没有成功的文件修改记录。请确认是否已动手，或改为说明当前状态与下一步。"
        }

        // R3：点名说了改了某个文件，但本回合没有对该路径的成功写入。
        if (claimsChange) {
            ClaimClassifier.claimedPaths(message).forEach { claimed ->
                if (!hasSuccessfulWriteTo(records, claimed)) {
                    notices += "你声称修改了 `$claimed`，但本回合没有对该文件的成功修改记录。请确认，或说明实际改动位置。"
                }
            }
        }

        // R4：测试作弊——写了测试却在削弱它。与「有没有跑」无关，单独成条。
        records.filter { it.tampered && !it.isError }.forEach { rec ->
            notices += "检测到对测试的改动可能削弱了验证（`${rec.path ?: "-"}`）：请说明为什么必须改测试，或改回测试并修真实问题。"
        }

        return notices.distinct()
    }

    /**
     * 三值裁决（PASS/FAIL/SUSPECT）。供需要区分「有假凭证」「作弊」「只是没跑」的调用方使用。
     *
     * 关键约束（对照 proof）：**判不了时返回 SUSPECT，绝不返回 PASS**。
     */
    fun verdict(message: String, records: List<ToolRecord>): Verdict {
        val notices = evaluate(message, records)
        if (notices.isEmpty()) return Verdict.PASS
        return if (records.any { it.tampered && !it.isError }) Verdict.SUSPECT else Verdict.FAIL
    }

    private fun hasSuccessfulVerifyCommand(records: List<ToolRecord>): Boolean =
        records.any { rec ->
            !rec.isError && rec.command != null &&
                CommandOutcome.isVerifyCommand(rec.command) &&
                // 输出里带失败信号则不算凭证——工具层把非零退出包成 Success，
                // 不看输出会让「BUILD FAILED 后声称测试通过」成为漏网的正例。
                !CommandOutcome.hasFailureSignal(rec.outputTail) &&
                TamperDetector.inspectCommand(rec.command, isVerifyCommand = true).isEmpty()
        }

    private fun hasSuccessfulWrite(records: List<ToolRecord>): Boolean =
        records.any { !it.isError && it.toolName in WRITE_TOOLS }

    private fun hasSuccessfulWriteTo(records: List<ToolRecord>, claimed: String): Boolean =
        records.any { !it.isError && it.toolName in WRITE_TOOLS && pathMatches(it.path, claimed) }

    /** 路径匹配：等值、或一方是另一方的工作区相对路径（`app/x.kt` ↔ `/root/workspace/app/x.kt`）。 */
    private fun pathMatches(actual: String?, claimed: String): Boolean {
        if (actual == null) return false
        val a = actual.trim()
        val c = claimed.trim()
        if (a == c) return true
        val suffix = "/$c"
        return a.endsWith(suffix) || c.endsWith("/$a")
    }
}
