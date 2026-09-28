package com.aicode.feature.agent.domain.tool

/**
 * 命令输出的通用去噪：与具体命令无关，只做确定无损的三件事——
 * 1) 清洗 ANSI 转义序列（颜色/光标控制码，纯显示噪音，去掉不损信息）；
 * 2) 折叠进度条的 `\r` 回车回刷（同一行反复重绘，只保留其最终态）；
 * 3) 折叠连续完全相同的行为「<行>\n...[上一行重复 N 次]...」（重复计数即原信息）。
 *
 * 为什么需要：编译/安装/测试类输出常有成百上千行进度与样板，即便总字符数不到
 * [ToolOutputStore] 的截断阈值，也会白白占满上下文。此步在脱敏之后、截断之前运行，
 * 让「进模型的正文」先去噪。
 *
 * 为何不做按命令定制的语义摘要：那需为每种命令预写解析规则，覆盖面有天花板且可能误删。
 * 这里只做命令无关、可预测的去噪，宁可少压也不误删。纯函数、无 IO，便于单测。
 */
internal object ToolOutputCompressor {

    /** CSI/SGR 等 ANSI 转义序列。 */
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** 连续相同行达到此条数才折叠，避免对偶发的两三行重复过度处理。 */
    private const val FOLD_THRESHOLD = 3

    data class Result(
        val text: String,
        val ansiStripped: Boolean,
        val linesFolded: Int,
    )

    fun compress(text: String): Result {
        if (text.isEmpty()) return Result(text, ansiStripped = false, linesFolded = 0)

        val noAnsi = ANSI.replace(text, "")
        val ansiStripped = noAnsi.length != text.length

        // 每一「行」里若含 \r（进度条回刷），只取最后一次回刷后的最终态。
        val lines = noAnsi.split('\n').map { it.substringAfterLast('\r') }

        val out = ArrayList<String>(lines.size)
        var folded = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            var j = i + 1
            while (j < lines.size && lines[j] == line) j++
            val count = j - i
            if (count >= FOLD_THRESHOLD) {
                out += line
                out += "...[上一行重复 $count 次]..."
                folded += count - 1
            } else {
                for (k in i until j) out += lines[k]
            }
            i = j
        }

        return Result(
            text = out.joinToString("\n"),
            ansiStripped = ansiStripped,
            linesFolded = folded,
        )
    }
}
