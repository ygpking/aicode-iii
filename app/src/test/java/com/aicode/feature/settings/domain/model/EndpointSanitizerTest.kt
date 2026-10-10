package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSanitizerTest {

    @Test
    fun acceptsHttpsUrl() {
        val result = EndpointSanitizer.sanitize("https://api.openai.com/v1")
        assertEquals(UrlCheck.Ok("https://api.openai.com/v1"), result)
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals(
            UrlCheck.Ok("https://api.openai.com/v1"),
            EndpointSanitizer.sanitize("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun normalizesFullwidthChars() {
        // 全角破折号/点/斜杠被归一为半角。
        val result = EndpointSanitizer.sanitize("https：//api．example．com／v1")
        assertEquals(UrlCheck.Ok("https://api.example.com/v1"), result)
    }

    @Test
    fun stripsZeroWidthChars() {
        val result = EndpointSanitizer.sanitize("https://api\u200B.example.com/v1")
        assertEquals(UrlCheck.Ok("https://api.example.com/v1"), result)
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertTrue(EndpointSanitizer.sanitize("file:///etc/passwd") is UrlCheck.Rejected)
        assertTrue(EndpointSanitizer.sanitize("javascript:alert(1)") is UrlCheck.Rejected)
        assertTrue(EndpointSanitizer.sanitize("data:text/html,<h1>x</h1>") is UrlCheck.Rejected)
    }

    @Test
    fun rejectsUserinfo() {
        val result = EndpointSanitizer.sanitize("https://user:pass@api.example.com/v1")
        assertTrue(result is UrlCheck.Rejected)
    }

    @Test
    fun rejectsPublicPlainHttp() {
        assertTrue(EndpointSanitizer.sanitize("http://api.openai.com/v1") is UrlCheck.Rejected)
    }

    @Test
    fun allowsPrivatePlainHttp() {
        assertEquals(
            UrlCheck.Ok("http://localhost:8080/v1"),
            EndpointSanitizer.sanitize("http://localhost:8080/v1"),
        )
        assertEquals(
            UrlCheck.Ok("http://192.168.1.10:11434/v1"),
            EndpointSanitizer.sanitize("http://192.168.1.10:11434/v1"),
        )
    }

    @Test
    fun allowsLoopbackIpv6Literal() {
        // 回归：`substringBefore(':')` 对 `[::1]:8787` 会取到 `"["`，使本机 IPv6 地址
        // 被误判成公网、误报「公网地址必须使用 https」。带端口与不带端口两种形态都要放行。
        assertEquals(
            UrlCheck.Ok("http://[::1]:8787/mcp"),
            EndpointSanitizer.sanitize("http://[::1]:8787/mcp"),
        )
        assertEquals(
            UrlCheck.Ok("http://[::1]/v1"),
            EndpointSanitizer.sanitize("http://[::1]/v1"),
        )
    }

    @Test
    fun stillRejectsPublicPlainHttpAfterIpv6Handling() {
        // 反向锁：修正括号剥离后，公网地址仍必须被拒（不能因放宽而漏网）。
        assertTrue(EndpointSanitizer.sanitize("http://8.8.8.8/v1") is UrlCheck.Rejected)
        assertTrue(EndpointSanitizer.sanitize("http://[2001:4860:4860::8888]:8787/v1") is UrlCheck.Rejected)
    }

    @Test
    fun rejectsEmptyOrMissingScheme() {
        assertTrue(EndpointSanitizer.sanitize("") is UrlCheck.Rejected)
        assertTrue(EndpointSanitizer.sanitize("   ") is UrlCheck.Rejected)
        assertTrue(EndpointSanitizer.sanitize("api.openai.com/v1") is UrlCheck.Rejected)
    }
}
