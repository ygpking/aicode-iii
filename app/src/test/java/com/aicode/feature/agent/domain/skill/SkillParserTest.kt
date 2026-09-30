package com.aicode.feature.agent.domain.skill

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private fun parseSerialized(
        name: String,
        description: String,
        requiredTools: List<String> = emptyList(),
        instructions: String
    ): Skill {
        val dir = java.nio.file.Files.createTempDirectory("skill-parser-test").toFile()
        return try {
            File(dir, "SKILL.md").writeText(
                SkillParser.serialize(
                    name = name,
                    description = description,
                    requiredTools = requiredTools,
                    instructions = instructions
                )
            )
            val parsed = SkillParser.parse(TestFileAccessProvider(), dir.absolutePath)
            assertNotNull(parsed)
            parsed!!
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serialize_roundTripsThroughParse() {
        val skill = parseSerialized(
            name = "pdf-report",
            description = "生成 PDF 报告时使用",
            requiredTools = listOf("Bash", "writeFile"),
            instructions = "## 步骤\n\n1. 读模板\n2. 渲染"
        )

        assertEquals("pdf-report", skill.name)
        assertEquals("生成 PDF 报告时使用", skill.description)
        assertEquals(listOf("Bash", "writeFile"), skill.requiredTools)
        assertEquals("## 步骤\n\n1. 读模板\n2. 渲染", skill.instructions)
    }

    @Test
    fun serialize_keepsYamlValidWithSpecialChars() {
        val skill = parseSerialized(
            name = "tricky",
            description = "用于: 处理 \"引号\" 与 # 井号 的场景",
            instructions = "正文"
        )

        assertEquals("用于: 处理 \"引号\" 与 # 井号 的场景", skill.description)
        assertEquals("正文", skill.instructions)
    }

    @Test
    fun serialize_flattensMultilineDescription() {
        val skill = parseSerialized(
            name = "multi",
            description = "第一行\n第二行",
            instructions = "正文"
        )

        assertEquals("第一行 第二行", skill.description)
    }

    @Test
    fun serialize_omitsRequiredToolsWhenEmpty() {
        val text = SkillParser.serialize(
            name = "plain",
            description = "d",
            requiredTools = emptyList(),
            instructions = "正文"
        )

        assertTrue(!text.contains("required_tools"))
    }

    /**
     * 回归：技能库里真实存在过的坏法——description 是未加引号的裸标量，值里含 `: `。
     * 原先 YAML 整块解析失败 → description 变空，而技能仍在列表里：模型据此判断是否启用，
     * 于是该技能**静默失效**。现在应自动补引号救回，而不是降级成空描述。
     */
    @Test
    fun parse_repairsUnquotedColonInPlainScalar() {
        val skill = SkillParser.parseText(
            text = """
                |---
                |name: cgo-stub-header-cross-compile
                |description: 容器里没有 NDK 时编译验证 —— 触发：cgo 报 "jni.h: No such file or directory"、或 undefined: C.xxx。
                |---
                |
                |正文
            """.trimMargin(),
            fallbackName = "fallback"
        )

        assertEquals("cgo-stub-header-cross-compile", skill.name)
        // 关键断言：描述必须被救回（而非空串），否则技能等于废掉
        assertTrue("description 不应为空，实际='${skill.description}'", skill.description.isNotBlank())
        assertTrue(skill.description.contains("jni.h: No such file or directory"))
        assertEquals("正文", skill.instructions)
    }

    /** 自动修复不得误伤合法写法：已引号包裹的冒号、列表都应原样解出。 */
    @Test
    fun parse_keepsValidFrontmatterIntact() {
        val skill = SkillParser.parseText(
            text = """
                |---
                |name: quoted
                |description: "a: b"
                |required_tools: [Bash, writeFile]
                |---
                |
                |正文
            """.trimMargin(),
            fallbackName = "fallback"
        )

        assertEquals("quoted", skill.name)
        assertEquals("a: b", skill.description)
        assertEquals(listOf("Bash", "writeFile"), skill.requiredTools)
    }

    /** 真正解析不了时不得崩溃；name 回退兜底值，函数仍需正常返回。 */
    @Test
    fun parse_survivesUnrepairableFrontmatter() {
        val skill = SkillParser.parseText(
            text = "---\n:  bad\n name\n---\n正文",
            fallbackName = "fallback-name"
        )

        assertEquals("fallback-name", skill.name)
        assertEquals("正文", skill.instructions)
    }

    /** 无 frontmatter 时不应报错，name 取兜底值。 */
    @Test
    fun parse_withoutFrontmatterUsesFallbackName() {
        val skill = SkillParser.parseText("只有正文", fallbackName = "plain")

        assertEquals("plain", skill.name)
        assertEquals("", skill.description)
        assertEquals("只有正文", skill.instructions)
    }

    /**
     * 回归：空 frontmatter（`---\n---`）。闭合符紧跟起始符时 end == 3，旧代码 substring(4, 3)
     * 越界抛 StringIndexOutOfBoundsException，会连带整个技能列表/斜杠命令一起挂掉。
     */
    @Test
    fun parse_emptyFrontmatterDoesNotCrash() {
        val skill = SkillParser.parseText("---\n---\nbody", fallbackName = "fallback")

        assertEquals("fallback", skill.name)
        assertEquals("body", skill.instructions)
    }

    @Test
    fun parse_emptyFrontmatterWithoutBody() {
        val withNewline = SkillParser.parseText("---\n---\n", fallbackName = "fb")
        assertEquals("fb", withNewline.name)
        assertEquals("", withNewline.instructions)

        val withoutNewline = SkillParser.parseText("---\n---", fallbackName = "fb")
        assertEquals("fb", withoutNewline.name)
        assertEquals("", withoutNewline.instructions)
    }

    /** 空 frontmatter + 正文中含分隔符：正文必须完整保留，不能被截断或吞掉。 */
    @Test
    fun parse_emptyFrontmatterKeepsBodyWithSeparators() {
        val skill = SkillParser.parseText("---\n---\n# 标题\n\n---\n尾注", fallbackName = "fb")

        assertEquals("fb", skill.name)
        assertTrue(skill.instructions.contains("# 标题"))
        assertTrue(skill.instructions.contains("尾注"))
    }
}
