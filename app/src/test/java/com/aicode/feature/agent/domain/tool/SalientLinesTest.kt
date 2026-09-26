package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalientLinesTest {

    @Test
    fun picksErrorAndStackTraceLines() {
        val text = buildString {
            appendLine("compiling module…")
            appendLine("note: using cached deps")
            appendLine("ERROR: src/main/Foo.kt:42: unresolved reference 'bar'")
            appendLine("  at com.aicode.Foo.run(Foo.kt:42)")
            appendLine("build finished")
        }
        val out = SalientLines.extract(text)!!
        assertTrue(out.contains("ERROR: src/main/Foo.kt:42"))
        assertTrue(out.contains("Foo.kt:42"))
    }

    @Test
    fun returnsNullWhenNothingSalient() {
        val text = "line one\nline two\nline three\n"
        assertNull(SalientLines.extract(text))
    }

    @Test
    fun respectsMaxLines() {
        val text = (1..10).joinToString("\n") { "error number $it" }
        val out = SalientLines.extract(text, maxLines = 3)!!
        assertEquals(3, out.lines().size)
    }

    @Test
    fun truncatesOverlongLine() {
        val text = "ERROR: " + "x".repeat(1000)
        val out = SalientLines.extract(text, maxCharsPerLine = 50)!!
        assertTrue(out.length <= 60)
    }

    @Test
    fun detectsExitCodeAndFailure() {
        val text = "step 1 ok\nstep 2 failed with exit code 2\n"
        val out = SalientLines.extract(text)!!
        assertTrue(out.contains("exit code 2"))
    }
}
