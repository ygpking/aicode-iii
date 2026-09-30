package com.aicode.feature.agent.domain.container

/**
 * 容器命令的 sleep 拦截：禁止用固定延时等待外部状态。
 *
 * ## 为什么需要
 *
 * 模型在等待外部状态（构建、单测、CI、子代理落盘、服务就绪）时，习惯在命令里写
 * `sleep N` 当「等一等再查」——白等还易撞工具超时线被杀（超时只产 WARN，模型感知
 * 不到代价，下一轮照犯）。平台的正路是 `terminal` + notify 的事件驱动等待；本守卫
 * 把超过阈值的长 sleep 从机制上堵住，并把替代方案直接回给模型。
 *
 * ## 边界
 *
 * 只拦截「作为独立命令出现的 sleep」且换算秒数 > [MAX_SLEEP_SECONDS]。
 * 短间隔重试（`for ... sleep 3`）、引用内文本（`echo "sleep 60"`）不受影响。
 *
 * 纯字符串处理、不访问文件系统，便于单测（与 [ContainerBuildGuard] 同一模式）。
 */
internal object CommandSleepGuard {

    /** 超过该秒数的独立 sleep 一律拦截。30s 内视为合法短间隔（重试/轮询）。 */
    const val MAX_SLEEP_SECONDS = 30.0

    /** 匹配作为独立命令出现的 sleep：行首或 `;`/`&&`/`||` 之后，支持小数与 s/m/h/d 单位。 */
    private val SLEEP_RE = Regex("""(^|[;&|]\s*)\bsleep\s+(\d+(?:\.\d+)?)([smhd]?)""", RegexOption.MULTILINE)

    /**
     * @return null = 放行；非 null = 拦截原因（含替代引导），调用方不得执行命令。
     */
    fun blockReason(command: String): String? {
        val m = SLEEP_RE.find(stripQuoted(command)) ?: return null
        val value = m.groupValues[2].toDoubleOrNull() ?: return null
        val unit = m.groupValues[3]
        val seconds = when (unit) {
            "m" -> value * 60
            "h" -> value * 3600
            "d" -> value * 86400
            else -> value // "s" 或无单位（GNU sleep 默认秒）
        }
        if (seconds <= MAX_SLEEP_SECONDS) return null
        return "命令被拦截：检测到独立 sleep $value$unit（${seconds}s），超过 ${MAX_SLEEP_SECONDS}s 上限。" +
            "固定延时等待外部状态是被禁止的坏模式（白等还易撞工具超时被杀）。" +
            "替代方案：1) 长任务（构建/测试/CI/服务）用 terminal(action=\"start\", notify=true) 后台跑，结束主动通知；" +
            "2) 等子代理等其完成通知，不轮询文件；" +
            "3) 必须轮询时短间隔（sleep ≤ ${MAX_SLEEP_SECONDS.toInt()}s、单条命令总时长 < 60s）。" +
            "原命令未执行。"
    }

    /**
     * 把单/双引号包裹的内容替换为等长空格，匹配位置与原文对齐，回显文本里的
     * `; sleep 60` 不再被误判为独立命令（双引号内 `\"` 转义不关闭引号）。
     */
    private fun stripQuoted(command: String): String {
        val sb = StringBuilder(command.length)
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    if (c == '\\' && quote == '"' && i + 1 < command.length) {
                        sb.append(' '); i += 2
                    } else {
                        sb.append(' ')
                        if (c == quote) quote = null
                        i++
                    }
                }
                c == '\'' || c == '"' -> { quote = c; sb.append(' '); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}