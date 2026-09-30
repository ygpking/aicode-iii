package com.aicode.feature.agent.domain.tool

/**
 * run 级累积预算（纯逻辑，零 IO）。
 *
 * 移植自 OpenSquilla `result_budget.py` + `tools/builtin/shell.py::_reserve_tool_call_with_runtime_guards`
 * （Apache-2.0）的「预留 → 提交/回滚」三段式。
 *
 * 与既有两个预算的分工（不重复、互为上下游）：
 * - [OutputSpillBudget]：**单条**输出该不该落盘、落盘后何时清（管磁盘占用）。
 * - **本类**：这一 run **累计**喂进上下文的字符总量（管上下文膨胀）。
 * - 协同：run 预算超限时，调用方可据此决定「后续输出一律落盘、内联只留预览」。
 *
 * 为什么用「预留/提交/回滚」而不是「事后累加」：工具执行是异步且可能失败的，
 * 事后累加无法在执行前拦住「一次 Bash 吐出几万字符」；预留式能在执行前先占额，
 * 执行失败再回滚，保证不会因中途异常而把额度算漏（漏算 = 预算形同虚设）。
 *
 * **默认不启用**（见 [DISABLED]）：AiCode 现在没有 run 级预算，贸然开启会改变
 * 已有会话的输出行为。调用方显式传入正数上限才生效。
 */
class ToolRunBudget(private val maxChars: Long) {

    companion object {
        /** 未启用（上限为 0 或负）。所有判定恒为「未超限」，行为与无预算一致。 */
        const val DISABLED: Long = 0L
    }

    /** 预留结果：是否放行，以及当前累计占用。 */
    data class Reservation(
        val granted: Boolean,
        val usedChars: Long,
        val maxChars: Long,
    ) {
        val overBudget: Boolean get() = !granted
    }

    private var usedChars: Long = 0L

    val committedChars: Long get() = usedChars
    val enabled: Boolean get() = maxChars > 0

    /**
     * 执行前预留 [chars] 个字符额度。
     *
     * 返回 [Reservation.granted] = false 表示**本次已超预算**——调用方应改为「落盘 + 内联只留预览」，
     * 而不是直接失败（与上游一致：超预算返回有界结果 + 显式截断标记，而非报错）。
     * 未启用时恒为 granted。
     */
    fun reserve(chars: Long): Reservation {
        if (!enabled) return Reservation(true, usedChars, maxChars)
        val amount = chars.coerceAtLeast(0L)
        // 已超限后继续放行调用方自行截断，但 usedChars 不再累加，避免无界增长。
        if (usedChars + amount > maxChars) {
            return Reservation(granted = false, usedChars = usedChars, maxChars = maxChars)
        }
        usedChars += amount
        return Reservation(granted = true, usedChars = usedChars, maxChars = maxChars)
    }

    /**
     * 提交一次预留（实际占用 [actualChars]，与预留量可能不同）。
     * 预留时已计入 [reservedChars]，此处按差额校正：多退少补。
     */
    fun commit(reservedChars: Long, actualChars: Long) {
        if (!enabled) return
        val delta = actualChars.coerceAtLeast(0L) - reservedChars.coerceAtLeast(0L)
        usedChars = (usedChars + delta).coerceAtLeast(0L)
    }

    /** 回滚一次预留（工具执行失败/异常）。 */
    fun rollback(reservedChars: Long) {
        if (!enabled) return
        usedChars = (usedChars - reservedChars.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /** 剩余可用字符数；未启用时为 [Long.MAX_VALUE]。 */
    fun remainingChars(): Long = if (!enabled) Long.MAX_VALUE else (maxChars - usedChars).coerceAtLeast(0L)
}
