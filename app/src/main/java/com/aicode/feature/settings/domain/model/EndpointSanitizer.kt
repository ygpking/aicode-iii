package com.aicode.feature.settings.domain.model

/**
 * URL 清洗/校验结果。
 */
sealed interface UrlCheck {
    data class Ok(val sanitized: String) : UrlCheck
    data class Rejected(val reason: String) : UrlCheck
}

/**
 * Provider baseUrl 的清洗与安全校验。
 *
 * 处理真实痛点：
 * - 用户从网页/文档粘贴 URL 常带**全角字符**（全角破折号/点/冒号）与**零宽字符**；
 * - 危险 scheme（`file:`/`javascript:`/`data:`）会被拼进 HTTP 请求，须拒绝；
 * - 私网地址允许明文 http（本地开发），公网地址要求 https。
 */
object EndpointSanitizer {

    /** 零宽与不可见字符。 */
    private val ZERO_WIDTH = Regex("[\u200B-\u200F\u202A-\u202E\uFEFF]")

    /** 常见全角→半角映射字符。 */
    private val FULLWIDTH_MAP = mapOf(
        '\uFF0D' to '-', // －
        '\u2010' to '-', // ‐
        '\u2011' to '-',
        '\u2012' to '-',
        '\u2013' to '-', // –
        '\u2014' to '-', // —
        '\uFF0E' to '.', // ．
        '\u3002' to '.', // 。
        '\uFF1A' to ':', // ：
        '\uFF0F' to '/', // ／
        '\uFF20' to '@', // ＠
    )

    private val ALLOWED_SCHEMES = setOf("http", "https")

    fun sanitize(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck.Rejected("空 URL")

        val cleaned = buildString {
            for (ch in trimmed) {
                if (ZERO_WIDTH.matches(ch.toString())) continue
                append(FULLWIDTH_MAP[ch] ?: ch)
            }
        }.trim().trimEnd('/')

        val scheme = cleaned.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return UrlCheck.Rejected("缺少 scheme")
        if (scheme !in ALLOWED_SCHEMES) return UrlCheck.Rejected("不安全的 scheme: $scheme")

        val afterScheme = cleaned.substringAfter("://")
        if (afterScheme.isEmpty()) return UrlCheck.Rejected("缺少主机名")

        // 拒绝 userinfo（user:pass@host）
        val authority = afterScheme.substringBefore('/')
        if (authority.contains('@')) return UrlCheck.Rejected("URL 不应包含 userinfo 凭据")

        if (scheme == "http" && !isPrivateHost(authority)) {
            return UrlCheck.Rejected("公网地址必须使用 https")
        }

        return UrlCheck.Ok(cleaned)
    }

    /** 主机是否为私网/本机（允许明文 http）。 */
    private fun isPrivateHost(authority: String): Boolean {
        val host = authority.substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "127.0.0.1" || host.startsWith("127.")) return true
        if (host == "::1" || host == "[::1]") return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        if (host.startsWith("169.254.")) return true
        return false
    }
}
