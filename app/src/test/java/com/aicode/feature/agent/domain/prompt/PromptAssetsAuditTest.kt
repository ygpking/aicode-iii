package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对**真实提示词资产**（`app/src/main/assets/prompts/`）做占位符契约审计。
 *
 * 为什么必须扫真实资产：`PromptPlaceholderChecker` 原本只在单测里对手写字符串调用，
 * **生产代码从不调用它，测试也从不读 assets** —— 于是「有守卫、但守卫从未对着真实数据跑过」。
 * 真机上正是如此炸的：`70-skills-and-mcp.md` 用一行说明文字介绍占位符用法
 * （`` `{{AICODE_SKILLS}}` ``），`renderVariables` 无条件 replace，把整行说明撑成上万字符，
 * 同时让「是否已显式使用该变量」的判定误判，本该追加到末尾的清单被塞进那句说明中间。
 *
 * 本测试把这类问题挡在提交前：
 * 1. 未知变量名（拼错）——`renderVariables` 只按已知名字替换，拼错的会原样注入模型；
 * 2. 已知变量写在行内代码/代码块里——通常是「举例说明」，会被误替换（本文件的主因）；
 * 3. 变量名清单与 [SystemPromptProvider] 的常量保持一致，避免两处漂移。
 */
class PromptAssetsAuditTest {

    private val promptsDir = File("src/main/assets/prompts")

    /** 递归收集所有提示词片段（`.md`）。 */
    private fun allPromptFiles(): List<File> {
        assertTrue(
            "未找到提示词资产目录：${promptsDir.absolutePath}（测试工作目录应为 app/）",
            promptsDir.isDirectory
        )
        return promptsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .toList()
            .sortedBy { it.path }
    }

    @Test
    fun assetsExist() {
        val files = allPromptFiles()
        assertTrue("提示词资产目录为空，测试失去意义", files.isNotEmpty())
        // 至少应包含数字基线片段与 agent/ 子目录
        assertTrue("缺少内置基线片段", files.any { it.name.startsWith("70-") })
        assertTrue("缺少 agent/ 子目录片段", files.any { it.parentFile.name == "agent" })
    }

    @Test
    fun noUnknownPlaceholdersInRealAssets() {
        val known = SystemPromptProvider.RENDERABLE_VARIABLES
        // 保留变量：由压缩流程等另行替换，不属于本审计范围。
        val reserved = setOf("INSTRUCTION")

        val problems = mutableListOf<String>()
        for (file in allPromptFiles()) {
            val result = PromptPlaceholderChecker.check(file.readText(), known, reserved)
            if (result is PlaceholderCheck.UnknownVariables) {
                problems += "${file.path} 含未知变量: ${result.names.joinToString()}"
            }
        }
        assertTrue(
            "提示词资产含未知占位符（会被原样注入模型，且不报错）：\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /**
     * 真实资产里**不允许**把可渲染变量写进行内代码/代码块——那是「举例说明」的形态，
     * 会被 `renderVariables` 误当成真实取值点，把说明文字替换成整段内容。
     */
    @Test
    fun noRenderablePlaceholdersInsideCodeSpans() {
        val known = SystemPromptProvider.RENDERABLE_VARIABLES

        val problems = mutableListOf<String>()
        for (file in allPromptFiles()) {
            val result = PromptPlaceholderChecker.checkQuoted(file.readText(), known)
            if (result is PlaceholderCheck.QuotedVariables) {
                problems += "${file.path} 在行内代码/代码块里写了可渲染变量: ${result.names.joinToString()}"
            }
        }
        assertTrue(
            "提示词资产把这些变量写进了代码跨度——它们会被误替换（介绍用法请改用不含双花括号的写法）：\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /**
     * `usesStandaloneVar` 的判据必须是「占位符独占一行」，而非「子串是否出现」。
     *
     * 这是 BUG-2 的根因：内置片段里的说明文字含占位符字面量，
     * 子串判定会误认为「用户已显式使用该变量」→ 本该追加的清单不再追加。
     */
    @Test
    fun standaloneVarDetection_requiresOwnLine() {
        val token = "{{AICODE_SKILLS}}"

        assertTrue(
            "独占一行应判定为已使用",
            SystemPromptProvider.usesStandaloneVar("前置\n$token\n后置", token)
        )
        assertTrue(
            "行首行尾有空白仍算独占一行",
            SystemPromptProvider.usesStandaloneVar("前置\n   $token   \n后置", token)
        )
        assertTrue(
            "整篇只有该占位符也应判定为已使用",
            SystemPromptProvider.usesStandaloneVar(token, token)
        )

        // 以下都是「说明文字」，不能判定为已使用——否则触发 BUG-2
        assertTrue(
            "行内引用（在句子中间）不应判定为已使用",
            !SystemPromptProvider.usesStandaloneVar("可用 $token 取回对应内容。", token)
        )
        assertTrue(
            "写进行内代码（举例说明）不应判定为已使用",
            !SystemPromptProvider.usesStandaloneVar("片段中可用 `$token` 取回。", token)
        )
        assertTrue(
            "其它变量独占一行不应误命中本变量",
            !SystemPromptProvider.usesStandaloneVar("{{AICODE_MEMORY}}", token)
        )
        assertTrue(
            "前后缀不同的变量名不应误命中",
            !SystemPromptProvider.usesStandaloneVar("{{AICODE_SKILLS_X}}", token)
        )
    }

    /**
     * `RENDERABLE_VARIABLES` 必须与 [SystemPromptProvider] 实际用于替换的常量一一对应，
     * 否则审计会漏掉某个变量（改了常量却忘了同步集合）。
     */
    @Test
    fun renderableVariablesMatchReplacementConstants() {
        val declared = SystemPromptProvider.RENDERABLE_VARIABLES
        val actual = setOf(
            SystemPromptProvider.SKILLS_VAR,
            SystemPromptProvider.MEMORY_VAR,
            SystemPromptProvider.SUBAGENTS_VAR,
            SystemPromptProvider.PROJECT_RULES_VAR,
            SystemPromptProvider.WORKSPACE_VAR,
            SystemPromptProvider.DATE_VAR,
        ).map { it.removeSurrounding("{{", "}}") }.toSet()

        assertEquals(
            "RENDERABLE_VARIABLES 与实际替换用的变量常量不一致（审计会漏检）",
            actual.sorted(),
            declared.sorted()
        )
    }
}
