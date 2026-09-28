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

    /**
     * 请求本身非法（400/404/422，或响应体根本不是 JSON）：上游拒收未知字段、Base URL 配错返回网页等。
     * 确定性失败——同一请求重发必得同样结果，重试纯属白烧。
     */
    INVALID_REQUEST,

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
        // 笼统的 4xx 状态码先存着不下判：`HTTP 400: prompt is too long` 这种要靠文本信号
        // 定为 CONTEXT_OVERFLOW，不能因状态码是 400 就归成泛泛的 INVALID_REQUEST。
        var generic4xx: Int? = null
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is HttpException -> {
                    statusKind(current.code())?.let { return it }
                    if (generic4xx == null && current.code() in 400..499) generic4xx = current.code()
                }
                is StreamApiException -> codeKind(current.code)?.let { return it }
            }
            current = current.cause
            depth++
        }
        // 文本信号比状态码具体，优先。UNKNOWN 表示文本没给出任何线索。
        val text = textKind(error.message ?: error.toString())
        if (text != ProviderFailureKind.UNKNOWN) return text
        // 响应体不是合法 JSON：请求已送达，只是响应不是 API 应答（Base URL 配错返回了网页 / 错误页）。
        // 与 RetryPolicy.isRetriableNetworkError 同源，免得「重试层判不可重试、自愈层却当临时故障重试」。
        if (isMalformedJsonResponse(error)) return ProviderFailureKind.INVALID_REQUEST
        if (generic4xx != null) return ProviderFailureKind.INVALID_REQUEST
        return ProviderFailureKind.UNKNOWN
    }

    private fun statusKind(code: Int): ProviderFailureKind? = when {
        code == 413 -> ProviderFailureKind.CONTEXT_OVERFLOW
        code == 401 || code == 403 -> ProviderFailureKind.AUTH_FAILED
        code == 429 -> ProviderFailureKind.RATE_LIMITED
        // 其余 4xx 不在此定判：上游拒收未知字段、路径不存在、参数语义错误均属「请求本身非法」，
        // 但 400 也可能承载上下文超限等更具体的语义，故交给 classify 末尾结合文本信号兜底。
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

            c.contains("invalid_request") || c.contains("unknown_field") ->
                ProviderFailureKind.INVALID_REQUEST

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
            msg.contains("invalid_request") || msg.contains("unknown field") ->
                ProviderFailureKind.INVALID_REQUEST

            else -> ProviderFailureKind.UNKNOWN
        }
    }

    private const val MAX_CAUSE_DEPTH = 5
}
