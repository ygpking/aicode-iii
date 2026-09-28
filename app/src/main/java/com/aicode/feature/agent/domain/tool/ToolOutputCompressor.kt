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
    val ANSI = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** 连续相同行达到此条数才折叠，避免对偶发的两三行重复过度处理。 */
    const val FOLD_THRESHOLD = 3

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

    /**
     * 去噪说明文案。**单点维护**：[compress]（入库时整体去噪）与 [LineFolder]（命令执行期逐行去噪）
     * 共用同一份话术，避免两处漂移。
     *
     * [spilledPath] 非空才提「可回读落盘文件」——未落盘时那句会误导模型去找不存在的文件。
     * 此时改为声明「重复计数即为原信息」，如实说明折叠无损。
     */
    fun foldNote(linesFolded: Int, ansiStripped: Boolean, spilledPath: String?): String {
        if (linesFolded <= 0 && !ansiStripped) return ""
        return buildString {
            append("\n\n...[已去噪：")
            if (linesFolded > 0) append("折叠重复行 $linesFolded 行")
            if (linesFolded > 0 && ansiStripped) append("、")
            if (ansiStripped) append("清除 ANSI 控制码")
            if (spilledPath != null) append("；原始输出可回读落盘文件") else append("；重复计数即为原信息")
            append("]...")
        }
    }
}

/**
 * 逐行去噪器：[ToolOutputCompressor.compress] 的**流式**版本，三条规则完全一致。
 *
 * 为什么需要它：整段去噪只能作用于「已经拿到的完整文本」；而命令输出是先经
 * [com.aicode.feature.agent.domain.container.BoundedOutput] 限幅（头尾各 2 万字符）
 * 才交到入库环节的，中段成千上万行重复在去噪之前就被丢了——折叠计数失真、一个字符也省不下。
 * 改成逐行喂入即让去噪看到完整输出流。
 *
 * 内存：只留一行 pending 与一个计数，与其下游一致，不随输入增长。
 * 非线程安全，由单一读取协程串行喂入。
 */
internal class LineFolder {

    private var pending: String? = null
    private var count = 0

    /** 已折叠（被省略）的行数，即原信息中消失的行数。 */
    var linesFolded = 0
        private set

    /** 是否清除了 ANSI 控制码。 */
    var ansiStripped = false
        private set

    /**
     * 送入**一整行**（不含换行符），返回可立即写出的行。
     * 含 `\r` 的行按最后一次回刷取最终态（进度条语义）。
     */
    fun feed(line: String): List<String> {
        val noAnsi = ToolOutputCompressor.ANSI.replace(line, "")
        if (noAnsi.length != line.length) ansiStripped = true
        val current = noAnsi.substringAfterLast('\r')
        if (current == pending) {
            count++
            return emptyList()
        }
        val out = flush()
        pending = current
        count = 1
        return out
    }

    /** 结束输入，吐出最后一批行。可重复调用（第二次起返回空）。 */
    fun finish(): List<String> = flush()

    private fun flush(): List<String> {
        val line = pending ?: return emptyList()
        val n = count
        pending = null
        count = 0
        return if (n >= ToolOutputCompressor.FOLD_THRESHOLD) {
            linesFolded += n - 1
            listOf(line, "...[上一行重复 $n 次]...")
        } else {
            List(n) { line }
        }
    }
}
