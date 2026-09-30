package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun format_and_parse_roundtrip() {
        val name = "user_preference"
        val description = "User prefers dark mode and concise answers"
        val content = "# User Preferences\n- Dark mode: true\n- Conciseness: high"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("user_preference.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertTrue(memory != null)
        assertEquals("user_preference", memory?.name)
        assertEquals("User prefers dark mode and concise answers", memory?.description)
        assertEquals(MemoryScope.GLOBAL, memory?.scope)
        assertEquals(content.trim(), memory?.content)
    }

    @Test
    fun format_escapesSpecialCharactersInYaml() {
        val name = "special:key#name"
        val description = "Description with \"quotes\" and : colons"
        val content = "Memory body"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("special.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.PROJECT)
        assertTrue(memory != null)
        assertEquals("special:key#name", memory?.name)
        assertEquals("Description with \"quotes\" and : colons", memory?.description)
        assertEquals("Memory body", memory?.content)
    }

    /**
     * 空 frontmatter（`---\n---`）：end == 3，旧代码 substring(4, 3) 越界崩。
     * 应当作无元数据：name 回退文件名，正文保留。
     */
    @Test
    fun parse_emptyFrontmatterDoesNotCrash() {
        val file = tempFolder.newFile("empty_fm.md")
        file.writeText("---\n---\n正文内容")

        val memory = MemoryParser.parse(file, MemoryScope.PROJECT)
        assertTrue(memory != null)
        assertEquals("empty_fm", memory?.name)
        assertEquals("正文内容", memory?.content)
    }

    // ---------- triggers（召回门控用；不注入清单） ----------

    @Test
    fun format_and_parse_triggersRoundtrip() {
        val file = tempFolder.newFile("trg.md")
        file.writeText(
            MemoryParser.format(
                "build-env", "构建环境", "正文",
                triggers = listOf("发版", "正式版", "Release")
            )
        )
        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertEquals(listOf("发版", "正式版", "release"), memory?.triggers)
    }

    /** 存量 16 条记忆均无 triggers，必须缺省为空且不影响其他字段。 */
    @Test
    fun parse_absentTriggersIsEmptyAndKeepsOtherFields() {
        val file = tempFolder.newFile("notrg.md")
        file.writeText("---\nname: old\ndescription: 旧记忆\npinned: true\n---\n正文")

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertEquals(emptyList<String>(), memory?.triggers)
        assertEquals("旧记忆", memory?.description)
        assertEquals(true, memory?.pinned)
    }

    /** 未传 triggers 时 format 输出应与旧实现逐字节相同，保证不破存量测试与已有文件。 */
    @Test
    fun format_withoutTriggersIsByteIdenticalToOldForm() {
        assertEquals(
            "---\nname: a\ndescription: b\n---\nc",
            MemoryParser.format("a", "b", "c")
        )
        assertEquals(
            "---\nname: a\ndescription: b\npinned: true\n---\nc",
            MemoryParser.format("a", "b", "c", pinned = true)
        )
    }

    /** 单标量写法（非 YAML 列表）也应接受，容错不抛异常。 */
    @Test
    fun parse_triggersAcceptsScalarAndDropsBlank() {
        val file = tempFolder.newFile("scalar.md")
        file.writeText("---\nname: s\ndescription: d\ntriggers: 发版\n---\n正文")
        assertEquals(listOf("发版"), MemoryParser.parse(file, MemoryScope.GLOBAL)?.triggers)

        val file2 = tempFolder.newFile("blank.md")
        file2.writeText("---\nname: b\ndescription: d\ntriggers: [\"\", \"  \"]\n---\n正文")
        assertEquals(emptyList<String>(), MemoryParser.parse(file2, MemoryScope.GLOBAL)?.triggers)
    }
}
