package com.aicode.feature.agent.domain.tool

/**
 * 文本相似度工具：Levenshtein 编辑距离及其归一化相似度。
 * 供工具名纠错（[ToolArgValidator]）与编辑模糊匹配（EditMatcher 的 block anchor 档）共用。
 */
internal object TextSimilarity {

    /** 标准 Levenshtein 编辑距离。 */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** 归一化相似度 ∈ [0,1]：`1 - 编辑距离 / 较长串长度`；两串皆空视为 1。 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - editDistance(a, b).toDouble() / maxLen
    }
}
