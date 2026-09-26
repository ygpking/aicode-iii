package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgValidatorTest {

    private val readSpec = ToolSpec(
        name = "readFile",
        required = setOf("path"),
        properties = setOf("path", "start_line", "end_line"),
    )

    @Test
    fun declaredArgsPass() {
        val result = ToolArgValidator.validate(
            readSpec,
            mapOf("path" to JsonPrimitive("/a"), "start_line" to JsonPrimitive(1)),
        )
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun missingRequiredIsReported() {
        val result = ToolArgValidator.validate(readSpec, mapOf("start_line" to JsonPrimitive(1)))
        val problems = result as ValidationResult.Problems
        assertTrue(problems.messages.single().contains("path"))
    }

    @Test
    fun nullValueCountsAsMissing() {
        val result = ToolArgValidator.validate(readSpec, mapOf("path" to JsonNull))
        assertTrue(result is ValidationResult.Problems)
    }

    @Test
    fun extraArgsAreAllowed() {
        // AiCode 工具按名读取参数、无视未声明项，因此不拦多余参数（避免误判本可成功的调用）。
        val result = ToolArgValidator.validate(
            readSpec,
            mapOf("path" to JsonPrimitive("/a"), "bogus" to JsonPrimitive("x")),
        )
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun onlyMissingIsReportedWhenBothPresent() {
        val result = ToolArgValidator.validate(readSpec, mapOf("bogus" to JsonPrimitive("x")))
        val problems = result as ValidationResult.Problems
        assertEquals(1, problems.messages.size)
        assertTrue(problems.messages.single().contains("path"))
    }

    @Test
    fun noRequiredAlwaysPasses() {
        val spec = ToolSpec("list", required = emptySet(), properties = setOf("args"))
        assertEquals(ValidationResult.Ok, ToolArgValidator.validate(spec, emptyMap()))
    }
}
