package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具输出脱敏器测试：覆盖各类凭据规则 + 不误伤普通文本。
 */
class ToolOutputScrubberTest {

    @Test
    fun scrubsOpenAiStyleKey() {
        val out = ToolOutputScrubber.scrub("env: OPENAI_API_KEY=sk-abcdefghijklmnop1234567890")
        assertFalse(out.contains("sk-abcdefghijklmnop1234567890"))
        assertTrue(out.contains("[REDACTED_"))
    }

    @Test
    fun scrubsBearerToken() {
        val out = ToolOutputScrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertTrue(out.contains("[REDACTED_BEARER]"))
    }

    @Test
    fun scrubsKeyValueSecrets() {
        val out = ToolOutputScrubber.scrub("api_key=supersecretvalue123\npassword: hunter2hunter2")
        assertFalse(out.contains("supersecretvalue123"))
        assertFalse(out.contains("hunter2hunter2"))
    }

    @Test
    fun scrubsAwsGithubJwt() {
        assertTrue(ToolOutputScrubber.scrub("AKIAIOSFODNN7EXAMPLE").contains("[REDACTED_AWS_KEY]"))
        assertTrue(
            ToolOutputScrubber.scrub("ghp_0123456789abcdefghijklmnopqrstuvwxyz").contains("[REDACTED_GITHUB_TOKEN]")
        )
        assertTrue(
            ToolOutputScrubber.scrub("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.SflKxwRJSM").contains("[REDACTED_JWT]")
        )
    }

    @Test
    fun scrubsUrlCredentials() {
        val out = ToolOutputScrubber.scrub("remote https://alice:ghp_secrettoken@github.com/repo.git")
        assertFalse(out.contains("alice:ghp_secrettoken"))
        assertTrue(out.contains("[REDACTED_CRED]@"))
    }

    @Test
    fun scrubsPrivateKeyBlock() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val out = ToolOutputScrubber.scrub("key:\n$pem\nend")
        assertTrue(out.contains("[REDACTED_PRIVATE_KEY]"))
        assertFalse(out.contains("MIIEowIBAAKCAQEA"))
    }

    @Test
    fun leavesOrdinaryTextUntouched() {
        val plain = "build finished in 42s, 10 warnings, exit code 0"
        assertEquals(plain, ToolOutputScrubber.scrub(plain))
        assertFalse(ToolOutputScrubber.hasSecret(plain))
    }
}
