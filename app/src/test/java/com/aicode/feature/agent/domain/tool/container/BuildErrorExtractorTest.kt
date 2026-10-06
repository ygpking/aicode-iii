package com.aicode.feature.agent.domain.tool.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BuildErrorExtractor]：失败输出的关键错误行提取。
 */
class BuildErrorExtractorTest {

    @Test
    fun kotlin_error_line_matched() {
        val output = """
            > Task :app:compileUniversalDebugKotlin
            e: file:///root/workspace/app/src/main/java/Foo.kt:285:68 Null cannot be a value of a non-null type 'List<String>'
            > Task :app:compileUniversalDebugKotlin FAILED
        """.trimIndent()
        val summary = BuildErrorExtractor.extract(output)!!
        assert(summary.contains("e: file:///root/workspace"))
        assert(summary.contains("FAILED"))
    }

    @Test
    fun gradle_failure_header_matched() {
        val summary = BuildErrorExtractor.extract(
            "What went wrong:\nFAILURE: Build failed with an exception.\n* Where:"
        )!!
        assert(summary.contains("FAILURE: Build failed"))
    }

    @Test
    fun gcc_style_error_matched() {
        val summary = BuildErrorExtractor.extract(
            "main.c:12:5: error: expected ';' before 'return'\nmake: *** [Makefile:8: main.o] Error 1"
        )!!
        assert(summary.contains("error: expected ';'"))
    }

    @Test
    fun test_failure_line_matched() {
        val summary = BuildErrorExtractor.extract(
            "TurnGovernorTest > segment_end_returns_continue FAILED\n    AssertionError at TurnGovernorTest.kt:88"
        )!!
        assert(summary.contains("FAILED"))
        // 断言行不是错误行，不单独命中
        assert(!summary.contains("AssertionError at"))
    }

    @Test
    fun plain_success_output_not_matched() {
        assertNull(BuildErrorExtractor.extract("Build succeeded in 3.2s\nAll checks passed, no error found in logs"))
        assertNull(BuildErrorExtractor.extract(""))
    }

    @Test
    fun error_word_in_middle_not_matched() {
        // 只有 error: 行首格式才命中，普通句子里的 "error" 不触发
        assertNull(BuildErrorExtractor.extract("the error was handled gracefully"))
    }

    @Test
    fun more_than_max_lines_truncated_with_count() {
        val output = (1..15).joinToString("\n") { "e: file:///w/F.kt:$it:1 some error" }
        val summary = BuildErrorExtractor.extract(output)!!
        assert(summary.contains("共 15 条"))
        assert(summary.contains("其余 5 条"))
        assertEquals(12, summary.lines().size) // 标题 + 10 条 + 省略行
    }

    @Test
    fun npm_error_matched() {
        val summary = BuildErrorExtractor.extract("npm ERR! code ELIFECYCLE\nnpm ERR! errno 1")!!
        assert(summary.contains("npm ERR! code ELIFECYCLE"))
    }
}
