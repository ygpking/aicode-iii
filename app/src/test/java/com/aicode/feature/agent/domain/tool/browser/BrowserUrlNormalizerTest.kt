package com.aicode.feature.agent.domain.tool.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlNormalizerTest {

    // ---------- normalize ----------

    @Test
    fun normalUrlUnchanged() {
        assertEquals("https://www.example.com/path?q=1", BrowserUrlNormalizer.normalize("https://www.example.com/path?q=1"))
    }

    @Test
    fun fullwidthColonAndSlashConvertedToHalfwidth() {
        // 中文输入法常见：https：／／ → https://
        assertEquals("https://example.com", BrowserUrlNormalizer.normalize("https：／／example.com"))
    }

    @Test
    fun fullwidthDotAndQuestionMarksConverted() {
        assertEquals("example.com/a?b=c", BrowserUrlNormalizer.normalize("example．com／a？b＝c"))
    }

    @Test
    fun zeroWidthAndDirectionalCharsStripped() {
        val dirty = "https://exa\u200Bmple\u200E.com\uFEFF"
        assertEquals("https://example.com", BrowserUrlNormalizer.normalize(dirty))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("example.com", BrowserUrlNormalizer.normalize("  example.com  "))
    }

    @Test
    fun keepsNormalAsciiIntact() {
        val url = "http://192.168.1.1:8080/api/v1?x=1&y=2#frag"
        assertEquals(url, BrowserUrlNormalizer.normalize(url))
    }

    // ---------- hasExplicitScheme ----------

    @Test
    fun httpAndHttpsHaveScheme() {
        assertTrue(BrowserUrlNormalizer.hasExplicitScheme("http://a.com"))
        assertTrue(BrowserUrlNormalizer.hasExplicitScheme("https://a.com"))
    }

    @Test
    fun otherSchemesDetected() {
        assertTrue(BrowserUrlNormalizer.hasExplicitScheme("about:blank"))
        assertTrue(BrowserUrlNormalizer.hasExplicitScheme("data:text/html,x"))
        assertTrue(BrowserUrlNormalizer.hasExplicitScheme("mailto:a@b.com"))
    }

    @Test
    fun hostPortIsNotScheme() {
        // `localhost:8080` 的冒号后是纯数字 → 不是 scheme，应交给 https 拼接
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme("localhost:8080"))
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme("192.168.1.1:8080/x"))
    }

    @Test
    fun hostWithPathIsNotScheme() {
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme("example.com:/path"))
    }

    @Test
    fun bareDomainHasNoScheme() {
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme("example.com"))
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme("www.baidu.com/s?wd=x"))
    }

    @Test
    fun emptyOrLeadingColonHasNoScheme() {
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme(""))
        assertFalse(BrowserUrlNormalizer.hasExplicitScheme(":foo"))
    }
}
