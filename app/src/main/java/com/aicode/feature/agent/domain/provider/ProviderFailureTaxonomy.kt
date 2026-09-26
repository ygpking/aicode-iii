package com.aicode.feature.agent.domain.provider

import retrofit2.HttpException

/**
 * provider 失败的规范化分类（自愈闭环的判定输入）。
 */
internal enum class ProviderFailureKind {
    /** 上下文超限（413 / context_length_exceeded / prompt is too long 等）。 */
    CONTEXT_OVERFLOW,

    /** 输出预算非法（max_tokens 超出模型上限）。 */
    INVALID_OUTPUT_BUDGET,

    /** 模型不支持图片输入。 */
    UNSUPPORTED_VISION,

    /** 鉴权失败（401/403）——不可自愈，需用户改配置。 */
    AUTH_FAILED,

    /** 限流（429）——交给多 Key 切换，不在此自愈。 */
    RATE_LIMITED,

    /** 其它/未知。 */
    UNKNOWN,
}

/**
 * provider 错误分类器：把上游错误收敛为可决策的类别。
 *
 * 输入是 [Throwable]（可能被 [enrichWithHttpErrorBody] 包成 `IllegalStateException` 并把原异常挂在
 * cause 上），故沿 cause 链找 [HttpException] 状态码与 [StreamApiException] 错误码，找不到再退回
 * 文案匹配。纯函数、零 IO。识别顺序即优先级：**先结构信号、后文案**。
 */
internal object ProviderFailureTaxonomy {

    fun classify(error: Throwable): ProviderFailureKind {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is HttpException -> statusKind(current.code())?.let { return it }
                is StreamApiException -> codeKind(current.code)?.let { return it }
            }
            current = current.cause
            depth++
        }
        return textKind(error.message ?: error.toString())
    }

    private fun statusKind(code: Int): ProviderFailureKind? = when {
        code == 413 -> ProviderFailureKind.CONTEXT_OVERFLOW
        code == 401 || code == 403 -> ProviderFailureKind.AUTH_FAILED
        code == 429 -> ProviderFailureKind.RATE_LIMITED
        else -> null
    }

    private fun codeKind(code: String?): ProviderFailureKind? {
        val c = code?.lowercase() ?: return null
        return when {
            c.contains("context_length") || c.contains("context_window") ||
                c.contains("too_many_tokens") || c.contains("max_tokens_exceeded") ->
                ProviderFailureKind.CONTEXT_OVERFLOW

            c.contains("invalid_output") || c.contains("output_token") ->
                ProviderFailureKind.INVALID_OUTPUT_BUDGET

            c.contains("invalid_image") || c.contains("unsupported_image") ->
                ProviderFailureKind.UNSUPPORTED_VISION

            c.contains("rate_limit") -> ProviderFailureKind.RATE_LIMITED
            else -> null
        }
    }

    private fun textKind(text: String): ProviderFailureKind {
        val msg = text.lowercase()
        return when {
            msg.contains("context length") || msg.contains("maximum context") ||
                msg.contains("context window") || msg.contains("too many tokens") ||
                msg.contains("prompt is too long") || msg.contains("tokens exceed") ->
                ProviderFailureKind.CONTEXT_OVERFLOW

            msg.contains("max_tokens") && (msg.contains("invalid") || msg.contains("less than") || msg.contains("greater")) ->
                ProviderFailureKind.INVALID_OUTPUT_BUDGET

            msg.contains("does not support image") || msg.contains("image input") ||
                msg.contains("unsupported image") || msg.contains("invalid image") ->
                ProviderFailureKind.UNSUPPORTED_VISION

            msg.contains("rate limit") || msg.contains("too many requests") -> ProviderFailureKind.RATE_LIMITED
            msg.contains("invalid api key") || msg.contains("unauthorized") || msg.contains("forbidden") ->
                ProviderFailureKind.AUTH_FAILED

            else -> ProviderFailureKind.UNKNOWN
        }
    }

    private const val MAX_CAUSE_DEPTH = 5
}
