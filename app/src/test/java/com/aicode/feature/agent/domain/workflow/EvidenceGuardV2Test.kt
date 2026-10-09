package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三值裁决（PASS/FAIL/SUSPECT）、退出码与失败信号、测试作弊检测的验证。 */
class EvidenceGuardV2Test {

    private fun rec(
        tool: String,
        isError: Boolean = false,
        path: String? = null,
        command: String? = null,
        tampered: Boolean = false,
        outputTail: String? = null,
    ) = EvidenceGuard.ToolRecord(tool, isError, path, command, tampered, outputTail)

    // ── 三值裁决（PASS/FAIL/SUSPECT）────────────────────────────────────────────

    @Test
    fun `有真凭证判 PASS`() {
        val records = listOf(rec("Bash", command = "sh gradlew :app:testUniversalDebugUnitTest"))
        assertEquals(EvidenceGuard.Verdict.PASS, EvidenceGuard.verdict("测试通过。", records))
    }

    @Test
    fun `无凭证判 FAIL`() {
        assertEquals(EvidenceGuard.Verdict.FAIL, EvidenceGuard.verdict("测试通过。", emptyList()))
    }

    @Test
    fun `检测到测试作弊判 SUSPECT`() {
        val records = listOf(rec("editFile", path = "app/src/test/FooTest.kt", tampered = true))
        assertEquals(EvidenceGuard.Verdict.SUSPECT, EvidenceGuard.verdict("已修复该问题。", records))
    }

    // ── 阻断项：失败命令不得成为凭证 ──────────────────────────────

    @Test
    fun `失败的构建输出不算验证凭证`() {
        // 工具层把非零退出包装成 ToolResult.Success，isError 恒 false，
        // 故必须靠输出尾部的失败信号判断——否则「BUILD FAILED 后声称测试通过」会被放行。
        val failed = listOf(
            rec("Bash", command = "sh gradlew test", outputTail = "FAILURE: Build failed with an exception.")
        )
        assertEquals(EvidenceGuard.Verdict.FAIL, EvidenceGuard.verdict("测试全部通过。", failed))
        assertTrue(EvidenceGuard.evaluate("测试全部通过。", failed).isNotEmpty())
    }

    @Test
    fun `失败的测试输出不算验证凭证`() {
        val failed = listOf(
            rec("Bash", command = "sh gradlew testUniversalDebugUnitTest", outputTail = "3 tests failed\nAssertionError")
        )
        assertTrue(EvidenceGuard.evaluate("测试通过。", failed).isNotEmpty())
    }

    @Test
    fun `成功输出不误伤`() {
        val ok = listOf(
            rec("Bash", command = "sh gradlew test", outputTail = "BUILD SUCCESSFUL in 1m 2s\n42 tests completed")
        )
        assertEquals(EvidenceGuard.Verdict.PASS, EvidenceGuard.verdict("测试通过。", ok))
    }

    @Test
    fun `失败信号识别`() {
        assertTrue(CommandOutcome.hasFailureSignal("BUILD FAILED in 3s"))
        assertTrue(CommandOutcome.hasFailureSignal("Execution failed for task ':app:compileUniversalDebugKotlin'"))
        assertTrue(CommandOutcome.hasFailureSignal("COMPILATION ERROR"))
        assertTrue(CommandOutcome.hasFailureSignal("3 tests failed"))
        // 非构建型验证脚本（复核指出的原版共同盲区）
        assertTrue(CommandOutcome.hasFailureSignal("Traceback (most recent call last):\n  File \"a.py\""))
        assertTrue(CommandOutcome.hasFailureSignal("Error: assertion failed at line 3"))
        // 零计数成功输出不算失败（复核构造的反例）
        assertEquals(false, CommandOutcome.hasFailureSignal("Error: 0 warnings emitted, all checks passed"))
        assertEquals(false, CommandOutcome.hasFailureSignal("BUILD SUCCESSFUL"))
        assertEquals(false, CommandOutcome.hasFailureSignal(null))
    }

    @Test
    fun `成功的 Failures 0 不误判为失败`() {
        // 复核构造的反例：maven surefire 成功输出含 `Failures: 0`，
        // 裸 `failures?:\s*\d` 会把成功当失败（原版 truthguard 的宽模式）。
        assertEquals(false, CommandOutcome.hasFailureSignal("[INFO] Tests run: 42, Failures: 0, Errors: 0"))
    }

    // ── 构建配置层作弊（tamper 第 6 条）──────────────────────────

    @Test
    fun `构建配置禁用测试被检出`() {
        val gradle = """
            android { testOptions { unitTests.isIgnoreFailures = true } }
        """.trimIndent()
        val found = TamperDetector.inspectBuildConfig("app/build.gradle.kts", gradle)
        assertTrue(found.any { it.contains("禁用") })
    }

    @Test
    fun `正常构建配置不误报`() {
        val gradle = "android { compileSdk = 34 }"
        assertEquals(emptyList<String>(), TamperDetector.inspectBuildConfig("app/build.gradle.kts", gradle))
    }

    @Test
    fun `非配置文件不查禁用设置`() {
        assertEquals(
            emptyList<String>(),
            TamperDetector.inspectBuildConfig("app/src/main/Foo.kt", "isIgnoreFailures = true")
        )
    }

    @Test
    fun `上限与上游一致为 3`() {
        assertEquals(3, EvidenceGuard.MAX_BLOCKS)
    }

    // ── 退出码语义（对照 truthguard check-exit-code.sh 的例外表）──────

    @Test
    fun `grep 无匹配的退出码 1 不算失败`() {
        assertEquals(
            CommandOutcome.Outcome.SUCCESS,
            CommandOutcome.classify("git log | grep latest", 1)
        )
    }

    @Test
    fun `diff 有差异的退出码 1 不算失败`() {
        assertEquals(CommandOutcome.Outcome.SUCCESS, CommandOutcome.classify("diff a.txt b.txt", 1))
    }

    @Test
    fun `工具缺失判为无法判定而非失败`() {
        assertEquals(
            CommandOutcome.Outcome.UNRUNNABLE,
            CommandOutcome.classify("nonexistent-cmd run", 127)
        )
    }

    @Test
    fun `条件位里的非零退出码不算失败`() {
        assertEquals(
            CommandOutcome.Outcome.SUCCESS,
            CommandOutcome.classify("sh gradlew test || echo failed", 2)
        )
    }

    @Test
    fun `真失败的构建判 FAILURE`() {
        assertEquals(
            CommandOutcome.Outcome.FAILURE,
            CommandOutcome.classify("sh gradlew assembleUniversalDebug", 1)
        )
    }

    @Test
    fun `验证命令识别含真实 gradle 任务名`() {
        assertTrue(CommandOutcome.isVerifyCommand("sh gradlew :app:testUniversalDebugUnitTest"))
        assertTrue(CommandOutcome.isVerifyCommand("sh gradlew assembleUniversalDebug"))
        // latest 含 test 子串，但不在词首，不算验证命令
        assertEquals(false, CommandOutcome.isVerifyCommand("git log --oneline | grep latest"))
    }

    // ── 测试作弊检测（对照 proof tamper.py 的 6 条规则）───────────────

    @Test
    fun `删除测试用例被检出`() {
        val before = """
            class FooTest {
                @Test
                fun `用例一`() { assertEquals(1, 1) }
                @Test
                fun `用例二`() { assertEquals(2, 2) }
            }
        """.trimIndent()
        val after = """
            class FooTest {
                @Test
                fun `用例一`() { assertEquals(1, 1) }
            }
        """.trimIndent()
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", before, after)
        assertTrue(found.any { it.contains("删除") })
    }

    @Test
    fun `新增跳过标记被检出`() {
        val before = """
            class FooTest {
                @Test
                fun `用例一`() { assertEquals(1, 1) }
            }
        """.trimIndent()
        val after = """
            class FooTest {
                @Test
                @Ignore
                fun `用例一`() { assertEquals(1, 1) }
            }
        """.trimIndent()
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", before, after)
        assertTrue(found.any { it.contains("跳过") })
    }

    @Test
    fun `断言被削弱被检出`() {
        val before = """
            class FooTest {
                fun check() {
                    assertEquals(1, value)
                    assertTrue(flag)
                }
            }
        """.trimIndent()
        val after = """
            class FooTest {
                fun check() {
                    assertTrue(true)
                }
            }
        """.trimIndent()
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", before, after)
        assertTrue(found.any { it.contains("断言") })
    }

    @Test
    fun `测试命令被改成恒真被检出`() {
        val found = TamperDetector.inspectCommand("sh gradlew test || true", isVerifyCommand = true)
        assertTrue(found.any { it.contains("恒真") })
    }

    @Test
    fun `向零断言测试文件插入恒真断言被检出`() {
        // 对照原版 _gutted 的第一条分支：新增行里出现恒真断言即标记。
        // 此前的实现只在「旧文件有真断言」时比对，这种纯新增型检不出。
        val before = "class FooTest {\n    fun `a`() { println(1) }\n}"
        val after = "class FooTest {\n    fun `a`() { println(1) }\n    fun `b`() { assertTrue(true) }\n}"
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", before, after)
        assertTrue(found.any { it.contains("恒真断言") })
    }

    @Test
    fun `新建全恒真断言测试文件被检出`() {
        // 复核指出的接线层盲区：before=null（新文件）时删用例/减断言分支因 oldAsserts=0
        // 天然不触发，但新增恒真断言必须报。
        val after = "class FooTest {\n    @Test\n    fun `a`() { assertTrue(true) }\n}"
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", null, after)
        assertTrue(found.any { it.contains("恒真断言") })
    }

    @Test
    fun `旧文件已有同样恒真行时新增仍被检出`() {
        // 存在性过滤会漏掉这种：旧 1 条、新 2 条同文本。
        val before = "class FooTest {\n    fun `a`() { assertTrue(true) }\n}"
        val after = "class FooTest {\n    fun `a`() { assertTrue(true) }\n    fun `b`() { assertTrue(true) }\n}"
        val found = TamperDetector.inspect("app/src/test/FooTest.kt", before, after)
        assertTrue(found.any { it.contains("恒真断言") })
    }

    @Test
    fun `正常改测试不误报`() {
        val before = "class FooTest {\n    fun `a`() { assertEquals(1, 1) }\n}"
        val after = "class FooTest {\n    fun `a`() { assertEquals(1, 1) }\n    fun `b`() { assertEquals(2, 2) }\n}"
        assertEquals(emptyList<String>(), TamperDetector.inspect("app/src/test/FooTest.kt", before, after))
    }

    @Test
    fun `非测试文件不判作弊`() {
        val before = "class Foo { fun a() { println(1) } }"
        val after = "class Foo { fun a() { println(2) } }"
        assertEquals(emptyList<String>(), TamperDetector.inspect("app/src/main/Foo.kt", before, after))
    }
}
