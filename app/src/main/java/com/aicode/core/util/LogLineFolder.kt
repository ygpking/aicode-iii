package com.aicode.core.util

/**
 * 把多行文本折叠为**严格一行**，供日志落盘使用。
 *
 * 背景（实测 bug）：日志落盘点原先直接拼接 `stackTraceToString()`，异常堆栈的多行、
 * 命令正文的多行脚本都会把一条日志拆成几十行落盘。被拆出的行不带时间戳/级别前缀，
 * 于是 `grep "ERROR"`、`grep "某个类名"` 会把**内容**当日志匹配进来；用 Shell 跑这类
 * grep 时命令文本本身又被记进日志，下一轮 grep 会把自己的历史当成新证据——自污染闭环。
 * 实测某天日志 14122/20269 行（70%）不是日志行，其中命令正文 4849 行、异常堆栈 591 行。
 *
 * 折叠而非丢弃：换行替换为可见的 [LINE_SEPARATOR] 标记，保留原始缩进信息，
 * 排查时仍能看出堆栈层级，但一条日志只占一行。超长文本按 [maxChars] 截断并标注原始长度，
 * 避免超大堆栈撑爆磁盘（完整内容仍可从 logcat 或按需复现获得）。
 */
object LogLineFolder {

    /** 换行的可见替身。选记号而非空格，便于在日志里一眼看出「这里原本换行了」。 */
    const val LINE_SEPARATOR = " ⏎ "

    /**
     * 折叠为单行并限幅。
     *
     * @param text 原始多行文本（换行会被替换）。
     * @param maxChars 折叠后允许的最大字符数（不含截断提示），超出则截断。
     */
    fun fold(text: String, maxChars: Int): String {
        // CRLF 先归一到 LF，否则 \r 会留下孤立控制字符，在部分查看器里显示为乱码。
        val oneLine = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace("\n", LINE_SEPARATOR)
        return if (oneLine.length <= maxChars) {
            oneLine
        } else {
            oneLine.take(maxChars) + "…(折叠后共${oneLine.length}字符，已截断)"
        }
    }
}
