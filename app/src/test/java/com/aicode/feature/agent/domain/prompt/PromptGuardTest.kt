package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGuardTest {

    private val known = setOf(
        "AICODE_SKILLS", "AICODE_MEMORY", "AICODE_SUBAGENTS",
        "AICODE_PROJECT_RULES", "AICODE_WORKSPACE", "AICODE_DATE",
    )
    private val reserved = setOf("INSTRUCTION")

    // ---------- 占位符校验 ----------

    @Test
    fun knownVariablesPass() {
        assertEquals(
            PlaceholderCheck.Ok,
            PromptPlaceholderChecker.check("hi {{AICODE_SKILLS}} and {{AICODE_DATE}}", known, reserved),
        )
    }

    @Test
    fun reservedVariablesPass() {
        assertEquals(
            PlaceholderCheck.Ok,
            PromptPlaceholderChecker.check("compress {{INSTRUCTION}}", known, reserved),
        )
    }

    @Test
    fun misspelledVariableIsCaught() {
        val result = PromptPlaceholderChecker.check("{{AICODE_SKIL}}", known, reserved)
        assertEquals(listOf("AICODE_SKIL"), (result as PlaceholderCheck.UnknownVariables).names)
    }

    @Test
    fun multipleUnknownVariablesAreDeduplicated() {
        val result = PromptPlaceholderChecker.check(
            "{{FOO}} {{BAR}} {{FOO}}",
            known,
            reserved,
        )
        assertEquals(listOf("FOO", "BAR"), (result as PlaceholderCheck.UnknownVariables).names)
    }

    @Test
    fun noPlaceholdersIsOk() {
        assertEquals(PlaceholderCheck.Ok, PromptPlaceholderChecker.check("plain text", known, reserved))
    }

    // ---------- 前缀缓存审计 ----------

    @Test
    fun detectsIsoTimestamp() {
        assertTrue(PromptStabilityAuditor.audit("now: 2026-09-24 10:00").any { it.rule == "iso-timestamp" })
    }

    @Test
    fun detectsUuid() {
        assertTrue(
            PromptStabilityAuditor.audit("id=550e8400-e29b-41d4-a716-446655440000")
                .any { it.rule == "uuid" },
        )
    }

    @Test
    fun detectsTimeMarker() {
        assertTrue(PromptStabilityAuditor.audit("Current time: ...").any { it.rule == "time-marker" })
        assertTrue(PromptStabilityAuditor.audit("当前时间是 ...").any { it.rule == "time-marker" })
    }

    @Test
    fun detectsRandomAndTurnCounter() {
        assertTrue(PromptStabilityAuditor.audit("use a random value").any { it.rule == "random" })
        assertTrue(PromptStabilityAuditor.audit("turn count: 3").any { it.rule == "turn-counter" })
    }

    @Test
    fun stableTextYieldsNoFindings() {
        val stable = "You are a coding assistant. Follow the rules above at all times."
        assertTrue(PromptStabilityAuditor.audit(stable).isEmpty())
    }
}
