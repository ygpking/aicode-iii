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
 *
 * 另一类边界是「**视觉上空**」的块（`---\n\n---`、仅空白行）：它在旧实现下虽不崩溃，
 * 却会得到一个空 `block`，使调用方多走一套「有元数据」流程（多一次解析、误报 name 回退告警）。
 */
class FrontmatterCodecTest {

    @Test
    fun noFrontmatter_returnsNullBlockAndWholeBody() {
        val split = FrontmatterCodec.split("just body\nno frontmatter")
        assertNull(split.block)
        assertEquals("just body\nno frontmatter", split.body)
    }

    /**
     * 未闭合（缺结束符）不再静默当「无元数据」：2026-09-30 一次外部批量重写记忆文件时漏写闭合符，
     * 17/20 条记忆的 description/triggers 被静默丢弃（当时无任何日志）。现为「尽力恢复 + 告警」。
     */
    @Test
    fun unclosedFrontmatter_recoversMetaAndWarns() {
        val text = "---\nname: x\ndescription: hi\ntriggers: [a, b]\n# 正文\n\n内容"
        val warns = mutableListOf<String>()
        val split = FrontmatterCodec.split(text) { warns.add(it) }
        assertEquals(FrontmatterCodec.Status.UNCLOSED, split.status)
        assertEquals("name: x\ndescription: hi\ntriggers: [a, b]", split.block)
        assertEquals("# 正文\n\n内容", split.body)
        assertEquals(listOf(FrontmatterCodec.UNCLOSED), warns)
    }

    /** 未闭合但起始符后并无可识别的 `key: 值` 行 → 无从恢复，退回「无元数据」，仍须告警。 */
    @Test
    fun unclosedWithoutMetaLines_fallsBackToNoMetadata() {
        val warns = mutableListOf<String>()
        val split = FrontmatterCodec.split("---\njust body\nmore") { warns.add(it) }
        assertNull(split.block)
        assertEquals(FrontmatterCodec.Status.UNCLOSED, split.status)
        assertEquals(listOf(FrontmatterCodec.UNCLOSED), warns)
    }

    /** 恢复后正文与元数据分离：此前 `memory read` 会把 `---\nname: ...` 一并当正文显示给模型。 */
    @Test
    fun unclosedRecovery_separatesBodyFromMeta() {
        val split = FrontmatterCodec.split("---\nname: x\nbody text")
        assertEquals("name: x", split.block)
        assertEquals("body text", split.body)
    }

    /** 正常块但正文又以 `---` 开头 → 疑似整块重复追加，只告警、不改行为。 */
    @Test
    fun nestedFrontmatter_warnsButKeepsBehavior() {
        val text = "---\nname: a\ndescription: \"\"\n---\n---\nname: a\ndescription: real"
        val warns = mutableListOf<String>()
        val split = FrontmatterCodec.split(text) { warns.add(it) }
        assertEquals(FrontmatterCodec.Status.OK, split.status)
        assertEquals("name: a\ndescription: \"\"", split.block)
        assertEquals(listOf(FrontmatterCodec.NESTED), warns)
    }

    /** 正常文件与空 frontmatter 都不得告警（空 frontmatter 是合法写法）。 */
    @Test
    fun normalAndEmptyFrontmatter_doNotWarn() {
        val warns = mutableListOf<String>()
        FrontmatterCodec.split("---\nname: demo\n---\nbody") { warns.add(it) }
        FrontmatterCodec.split("---\n---\nbody") { warns.add(it) }
        FrontmatterCodec.split("no frontmatter") { warns.add(it) }
        assertTrue("正常/空/无 frontmatter 均不应告警，实际: $warns", warns.isEmpty())
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

    /** `---\n\n---`：含空白行的空 frontmatter，语义应与 `---\n---` 一致（无元数据）。 */
    @Test
    fun blankLineEmptyFrontmatter_treatedAsNoMetadata() {
        val split = FrontmatterCodec.split("---\n\n---\nbody text")
        assertNull("含空白行的空块同样视为无元数据", split.block)
        assertEquals("body text", split.body)
    }

    /** `---\n   \n---`：仅含空格的空 frontmatter。 */
    @Test
    fun whitespaceOnlyFrontmatter_treatedAsNoMetadata() {
        val split = FrontmatterCodec.split("---\n   \n---\nbody")
        assertNull(split.block)
        assertEquals("body", split.body)
    }

    /** 只含注释的块：YAML 视为「无元数据」，不得谎报「解析失败」。 */
    @Test
    fun commentOnlyBlock_doesNotWarnButStaysAsBlock() {
        val split = FrontmatterCodec.split("---\n# just a comment\n---\nbody")
        assertEquals("块本身仍保留给调用方", "# just a comment", split.block)

        val warns = mutableListOf<String>()
        val meta = FrontmatterCodec.parse(split.block!!) { m, _ -> warns.add(m) }
        assertTrue("注释块应解析为空表而非报错", meta.isEmpty())
        assertTrue("注释块是合法输入，不应产生告警（旧实现亦然）", warns.isEmpty())
    }

    /** 空串块的解析同样不告警（旧实现用 `?: emptyMap()`，无日志）。 */
    @Test
    fun blankBlock_parseDoesNotWarn() {
        val warns = mutableListOf<String>()
        val meta = FrontmatterCodec.parse("   ") { m, _ -> warns.add(m) }
        assertTrue(meta.isEmpty())
        assertTrue("纯空白块不是解析失败", warns.isEmpty())
    }

    /** 真失败仍必须告警：未闭合的流式映射是确定的 YAML 语法错误。 */
    @Test
    fun realParseFailure_stillWarns() {
        val warns = mutableListOf<String>()
        val meta = FrontmatterCodec.parse("{a: 1") { m, _ -> warns.add(m) }
        assertTrue(meta.isEmpty())
        assertEquals("语法错误必须告警，不得静默", listOf(FrontmatterCodec.FAILED), warns)
    }
}
