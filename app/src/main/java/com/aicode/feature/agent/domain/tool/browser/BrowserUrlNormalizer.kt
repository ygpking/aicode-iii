package com.aicode.feature.agent.domain.tool.browser

/**
 * 浏览器地址栏输入的归一化与 scheme 判定（纯函数、零 Android 依赖，便于单测）。
 *
 * 解决的问题：中文输入法/复制粘贴常带入**全角字符**（全角冒号 `：`、全角斜杠 `／`）与**零宽/方向控制字符**，
 * 直接交给 WebView 会因 scheme 无法识别报 `net::ERR_UNKNOWN_URL_SCHEME`。
 */
internal object BrowserUrlNormalizer {

    /** 零宽与方向控制字符。 */
    private val ZERO_WIDTH_CHARS = setOf(
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\uFEFF',
    )

    /** 常见全角字符 → 半角。 */
    private val FULLWIDTH_MAP = mapOf(
        '\uFF1A' to ':', // ：
        '\uFF0F' to '/', // ／
        '\uFF0E' to '.', // ．
        '\u3002' to '.', // 。
        '\uFF1F' to '?', // ？
        '\uFF1D' to '=', // ＝
        '\uFF06' to '&', // ＆
        '\uFF20' to '@', // ＠
        '\uFF03' to '#', // ＃
        '\uFF05' to '%', // ％
    )

    /** 去除零宽字符、全角转半角、trim。 */
    fun normalize(raw: String): String {
        val cleaned = buildString {
            for (ch in raw) {
                if (ch in ZERO_WIDTH_CHARS) continue
                append(FULLWIDTH_MAP[ch] ?: ch)
            }
        }
        return cleaned.trim()
    }

    /**
     * 输入是否已带显式 scheme（`xxx:` 形式）。
     *
     * 需排除两类「冒号但不是 scheme」的输入：
     * - `host:port[/path]`：冒号后到第一个 `/` 之前是全数字（如 `localhost:8080`）；
     * - `host:/path`：冒号后是单个 `/`（不是 scheme 分隔符 `//`）。
     */
    fun hasExplicitScheme(input: String): Boolean {
        val idx = input.indexOf(':')
        if (idx <= 0) return false
        val scheme = input.substring(0, idx)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return false
        val after = input.substring(idx + 1)
        if (after.isEmpty()) return false
        // `//` 是 scheme 分隔符；单个 `/` 说明是 `host:/...`，不是 scheme
        if (after.startsWith("/") && !after.startsWith("//")) return false
        // 冒号后到第一个 `/` 之间若为全数字，则是端口而非 scheme
        val beforeSlash = after.substringBefore('/')
        if (beforeSlash.isNotEmpty() && beforeSlash.all { it.isDigit() }) return false
        return true
    }
}
