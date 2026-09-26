package com.aicode.feature.agent.domain.tool

/**
 * 工具输出脱敏器：把工具结果中可能出现的凭据替换为保留存在性的占位符。
 *
 * 为什么需要：工具可能执行 `env`、`cat .env`、`git remote -v` 等，输出里会带 API Key、
 * Token、私钥、URL 内嵌凭据。这些内容既会进模型上下文，也会落盘到 `tool-output/`；
 * 若不在**进入 [ToolOutputStore] 之前**统一清洗，密钥就可能被持久化并回传给模型。
 *
 * 口径：保留 `[REDACTED_*]` 占位（便于排查「此处原本有敏感值」），尽量保守避免误伤
 * 普通文本。纯函数、无 IO。
 */
object ToolOutputScrubber {

    private val RULES: List<Pair<Regex, String>> = listOf(
        // PEM 私钥块
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to "[REDACTED_PRIVATE_KEY]",
        // AWS Access Key ID
        Regex("\\bAKIA[0-9A-Z]{16}\\b") to "[REDACTED_AWS_KEY]",
        // GitHub Token
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b") to "[REDACTED_GITHUB_TOKEN]",
        // JWT
        Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b") to "[REDACTED_JWT]",
        // Authorization: Bearer xxx
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{12,}") to "[REDACTED_BEARER]",
        // URL 内嵌凭据 user:pass@host
        Regex("(?i)\\b(https?://)([^/@\\s:]+):([^/@\\s]+)@") to "$1[REDACTED_CRED]@",
        // OpenAI 风格 sk- key
        Regex("\\bsk-[A-Za-z0-9_\\-]{16,}\\b") to "[REDACTED_API_KEY]",
        // key=value / key: value 形式的密钥字段
        Regex(
            "(?i)\\b(api[_-]?key|secret|token|password|passwd|pwd)\\b(\\s*[:=]\\s*)[\"']?([A-Za-z0-9._\\-/+=]{8,})[\"']?"
        ) to "$1$2[REDACTED_SECRET]",
    )

    /** 返回脱敏后的文本；无命中则原样返回。 */
    fun scrub(text: String): String {
        var out = text
        for ((pattern, replacement) in RULES) {
            out = pattern.replace(out, replacement)
        }
        return out
    }

    /** 是否命中任一规则（供诊断/测试）。 */
    fun hasSecret(text: String): Boolean = RULES.any { it.first.containsMatchIn(text) }
}
