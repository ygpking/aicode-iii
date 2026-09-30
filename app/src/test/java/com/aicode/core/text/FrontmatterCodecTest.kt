package com.aicode.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrontmatterCodec] 的边界回归。
 *
 * 重点是**空 frontmatter**：`---\n---` 曾让三份副本各自越界崩溃（`substring(4, 3)`），
 * 且上一轮只修了一处、同一崩溃随即在另一份里复发（commit `9661b83`）。收敛后此边界只此一处。
 */
class FrontmatterCodecTest {

    @Test
    fun noFrontmatter_returnsNullBlockAndWholeBody() {
        val split = FrontmatterCodec.split("just body\nno frontmatter")
        assertNull(split.block)
        assertEquals("just body\nno frontmatter", split.body)
    }

    @Test
    fun unclosedFrontmatter_treatedAsNoMetadata() {
        val text = "---\nname: x\n"
        val split = FrontmatterCodec.split(text)
        assertNull(split.block)
        assertEquals(text, split.body)
    }

    /** 曾崩溃的边界：`end == 3`，不得抛 StringIndexOutOfBoundsException。 */
    @Test
    fun emptyFrontmatter_doesNotCrashAndYieldsEmptyBlock() {
        val split = FrontmatterCodec.split("---\n---\nbody text")
        assertNull("空 frontmatter 视为无元数据", split.block)
        assertEquals("body text", split.body)
    }

    @Test
    fun emptyFrontmatter_withoutTrailingNewline() {
        val split = FrontmatterCodec.split("---\n---")
        assertNull(split.block)
        assertEquals("", split.body)
    }

    @Test
    fun normalFrontmatter_extractsBlockAndBody() {
        val split = FrontmatterCodec.split("---\nname: demo\ndescription: hi\n---\nbody here")
        assertEquals("name: demo\ndescription: hi", split.block)
        assertEquals("body here", split.body)
    }

    @Test
    fun crlf_isNormalized() {
        val split = FrontmatterCodec.split("---\r\nname: demo\r\n---\r\nbody")
        assertEquals("name: demo", split.block)
        assertEquals("body", split.body)
    }

    @Test
    fun parse_readsSimpleYaml() {
        val meta = FrontmatterCodec.parse("name: demo\ndescription: hello")
        assertEquals("demo", meta["name"])
        assertEquals("hello", meta["description"])
    }

    @Test
    fun parse_withRepair_fixesUnquotedColon() {
        // 未加引号的裸标量值里含 ": " —— YAML 会误判，repair=true 时应补引号后成功。
        val block = "name: demo\ndescription: 触发: 报错"
        val meta = FrontmatterCodec.parse(block, repair = true)
        assertEquals("demo", meta["name"])
        assertEquals("触发: 报错", meta["description"])

        // 不启用 repair 时保持原行为（解析失败 → 空表）。
        assertTrue(FrontmatterCodec.parse(block, repair = false).isEmpty())
    }
}
