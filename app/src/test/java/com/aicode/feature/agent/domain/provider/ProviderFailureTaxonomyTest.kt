package com.aicode.feature.agent.domain.provider

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ProviderFailureTaxonomyTest {

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    @Test
    fun http413IsContextOverflow() {
        assertEquals(ProviderFailureKind.CONTEXT_OVERFLOW, ProviderFailureTaxonomy.classify(httpException(413)))
    }

    @Test
    fun contextLengthCodeIsContextOverflow() {
        assertEquals(
            ProviderFailureKind.CONTEXT_OVERFLOW,
            ProviderFailureTaxonomy.classify(StreamApiException("context_length_exceeded", "too long")),
        )
    }

    @Test
    fun contextTextIsContextOverflow() {
        assertEquals(
            ProviderFailureKind.CONTEXT_OVERFLOW,
            ProviderFailureTaxonomy.classify(IllegalStateException("HTTP 400: prompt is too long")),
        )
    }

    @Test
    fun invalidOutputBudgetIsClassified() {
        assertEquals(
            ProviderFailureKind.INVALID_OUTPUT_BUDGET,
            ProviderFailureTaxonomy.classify(StreamApiException("invalid_output", "bad max_tokens")),
        )
        assertEquals(
            ProviderFailureKind.INVALID_OUTPUT_BUDGET,
            ProviderFailureTaxonomy.classify(IllegalStateException("max_tokens is less than the minimum")),
        )
    }

    @Test
    fun unsupportedVisionIsClassified() {
        assertEquals(
            ProviderFailureKind.UNSUPPORTED_VISION,
            ProviderFailureTaxonomy.classify(IllegalStateException("this model does not support image input")),
        )
    }

    @Test
    fun authFailuresAreClassified() {
        assertEquals(ProviderFailureKind.AUTH_FAILED, ProviderFailureTaxonomy.classify(httpException(401)))
        assertEquals(ProviderFailureKind.AUTH_FAILED, ProviderFailureTaxonomy.classify(httpException(403)))
    }

    @Test
    fun rateLimitIsClassified() {
        assertEquals(ProviderFailureKind.RATE_LIMITED, ProviderFailureTaxonomy.classify(httpException(429)))
    }

    @Test
    fun unknownTextIsUnknown() {
        assertEquals(ProviderFailureKind.UNKNOWN, ProviderFailureTaxonomy.classify(IllegalStateException("something odd")))
    }

    @Test
    fun genericClientErrorIsInvalidRequest() {
        // 上游拒收未知字段：重发同一请求必然再失败，不能再当临时故障反复重试。
        // 形态与 enrichWithHttpErrorBody 一致：HttpException 挂在 cause 上。
        assertEquals(
            ProviderFailureKind.INVALID_REQUEST,
            ProviderFailureTaxonomy.classify(
                IllegalStateException("HTTP 400: 未知请求字段：prompt_cache_key", httpException(400)),
            ),
        )
        assertEquals(ProviderFailureKind.INVALID_REQUEST, ProviderFailureTaxonomy.classify(httpException(404)))
        assertEquals(ProviderFailureKind.INVALID_REQUEST, ProviderFailureTaxonomy.classify(httpException(422)))
    }

    @Test
    fun malformedJsonBodyIsInvalidRequest() {
        // enrichWithHttpErrorBody 会把「响应体不是合法 JSON」包成 IllegalStateException 并挂 cause，
        // 与 RetryPolicy.isRetriableNetworkError 的判定同源。
        val malformed = IllegalStateException(
            "服务器返回的内容不是有效的 JSON",
            com.google.gson.stream.MalformedJsonException("Use JsonReader.setStrictness"),
        )
        assertEquals(ProviderFailureKind.INVALID_REQUEST, ProviderFailureTaxonomy.classify(malformed))
    }

    @Test
    fun textSignalWinsOverGeneric400() {
        // 回归：400 不能因状态码就归成泛泛的 INVALID_REQUEST，
        // 否则上下文超限会被吞掉，上层再不会触发「压缩后重试」自愈。
        assertEquals(
            ProviderFailureKind.CONTEXT_OVERFLOW,
            ProviderFailureTaxonomy.classify(
                IllegalStateException("HTTP 400: prompt is too long", httpException(400)),
            ),
        )
        assertEquals(
            ProviderFailureKind.AUTH_FAILED,
            ProviderFailureTaxonomy.classify(httpException(401)),
        )
    }

    @Test
    fun connectionRefusedStaysUnknownSoItRetriesNextRound() {
        // 网关被杀是可恢复的：必须留在 UNKNOWN（transient），
        // 否则会与「请求非法」混淆，把可自愈的断连也一并关停。
        assertEquals(
            ProviderFailureKind.UNKNOWN,
            ProviderFailureTaxonomy.classify(
                java.net.ConnectException("Failed to connect to /127.0.0.1:18788"),
            ),
        )
    }

    @Test
    fun wrappedCauseIsUnwrapped() {
        // enrichWithHttpErrorBody 会把 HttpException 包进 IllegalStateException 并挂 cause。
        val wrapped = IllegalStateException("HTTP 413: request too large", httpException(413))
        assertEquals(ProviderFailureKind.CONTEXT_OVERFLOW, ProviderFailureTaxonomy.classify(wrapped))
    }

    @Test
    fun deeplyWrappedCauseStillFound() {
        val deep = IllegalStateException("outer", IllegalStateException("mid", httpException(401)))
        assertEquals(ProviderFailureKind.AUTH_FAILED, ProviderFailureTaxonomy.classify(deep))
    }
}
