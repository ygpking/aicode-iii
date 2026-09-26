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
