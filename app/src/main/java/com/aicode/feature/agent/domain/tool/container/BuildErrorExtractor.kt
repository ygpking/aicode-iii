package com.aicode.feature.agent.domain.tool.container

/**
 * 命令失败时从输出中提取关键错误行，附在结果末尾。
 *
 * ## 为什么需要
 *
 * 编译/测试失败时，真正的错误行往往被几百行进度输出稀释；输出又经 [BoundedOutput]
 * 只保留开头+结尾，中段的错误行（Kotlin 报错在 `e: ` 行、Gradle 的 `FAILURE:` 段）
 * 可能整个被截掉，模型只看到 BUILD FAILED 却不知道错在哪，只能盲目重试。
 *
 * ## 做法与边界
 *
 * 仿 [ContainerBuildGuard] 的保守立场：只**追加**摘要、不删除原文；
 * 只匹配编译/测试工具的确切错误行格式，宁可漏提也不错提（普通命令输出里
 * 偶然含 "error" 一词不应触发）。仅对失败命令（退出码非 0 或超时）调用。
 *
 * 纯字符串处理、不访问文件系统，便于单测。
 */
internal object BuildErrorExtractor {

    /**
     * 错误行模式 → 提取时的标注。模式刻意窄：
     * - `e: file:line:col`：Kotlin 编译器错误行（无此精确格式不匹配）；
     * - `error:` 前缀：GCC/Clang/Rust/Gradle javac 等的错误行；
     * - `FAILURE: Build failed` / `BUILD FAILED`：Gradle/Maven/Ant 失败头；
     * - `FAILED` 行（行首，含测试名）：Gradle 测试报告行；
     * - `Traceback (most recent call last):`：Python 堆栈头（后续行跟着取）；
     * - `npm ERR!`：npm 错误行。
     */
    private val LINE_PATTERNS: List<Regex> = listOf(
        Regex("""^e: \S+:\d+:\d+"""),
        Regex("""^\S+:[\d\s]+:\s*(?:error|Error|ERROR):\s"""),
        Regex("""^(?:error|Error|ERROR):\s"""),
        Regex("""^(?:FAILURE: Build failed|BUILD FAILED)"""),
        Regex("""^.{0,200}\sFAILED\s*$"""),
        Regex("""^Traceback \(most recent call last\):"""),
        Regex("""^npm ERR!"""),
    )

    /** 摘要最多保留的错误行数，超出截断并注明总数。 */
    const val MAX_LINES = 10

    /**
     * 从输出中提取错误行；无命中返回 null（调用方不加任何内容）。
     *
     * [output] 是已经过限幅/折叠的最终文本——摘要只可能比原文少，不会凭空多。
     */
    fun extract(output: String): String? {
        val lines = output.lines()
        val hits = lines.filterIndexed { _, line ->
            LINE_PATTERNS.any { it.containsMatchIn(line) }
        }
        if (hits.isEmpty()) return null

        return buildString {
            append("[关键错误行（共 ${hits.size} 条，超出 $MAX_LINES 条已截断）]")
            hits.take(MAX_LINES).forEach { append("\n> ").append(it.trim().take(300)) }
            if (hits.size > MAX_LINES) append("\n> ...（其余 ${hits.size - MAX_LINES} 条见上方原文/日志）")
        }
    }
}
