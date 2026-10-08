package com.aicode.feature.agent.domain.mcp

import com.aicode.core.text.NameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展包贡献的 MCP server：合并层级与只读判定（本轮新增能力的回归护栏）。
 *
 * 两类不变量：
 * 1. **层级次序**：作用域优先（项目 > 全局），同作用域内扩展 > 目录（与技能/子代理一致）；
 * 2. **只读**：扩展来源的 server 不能被删/改/启停——它在 mcp.json 里本就不存在，
 *    写路径只会改到目录配置而改不动它（静默失效），故按名字查出来就直接拒绝。
 *
 * 与 [McpNameNormalizationTest] 同一风格：纯逻辑复刻，不构造真实仓库。
 */
class McpExtensionContributionTest {

    private fun keyOf(name: String) = NameKey.of(name)

    /** 与 McpConfigRepository.merge 相同的次序：global → globalExt → project → projectExt。 */
    private fun merge(
        global: List<Pair<String, McpOrigin>>,
        globalExt: List<Pair<String, McpOrigin>>,
        project: List<Pair<String, McpOrigin>>,
        projectExt: List<Pair<String, McpOrigin>>
    ): Map<String, Pair<McpScope, McpOrigin>> {
        val byName = LinkedHashMap<String, Pair<McpScope, McpOrigin>>()
        global.forEach { byName[keyOf(it.first)] = McpScope.GLOBAL to it.second }
        globalExt.forEach { byName[keyOf(it.first)] = McpScope.GLOBAL to it.second }
        project.forEach { byName[keyOf(it.first)] = McpScope.PROJECT to it.second }
        projectExt.forEach { byName[keyOf(it.first)] = McpScope.PROJECT to it.second }
        return byName
    }

    /** 同作用域内扩展压过目录：否则扩展装的 server 会被同名目录条目静默替换掉。 */
    @Test
    fun sameScope_extensionOverridesDirectory() {
        val merged = merge(
            global = listOf("srv" to McpOrigin.DIRECTORY),
            globalExt = listOf("srv" to McpOrigin.EXTENSION),
            project = emptyList(),
            projectExt = emptyList()
        )
        assertEquals(1, merged.size)
        assertEquals(McpScope.GLOBAL to McpOrigin.EXTENSION, merged["srv"])
    }

    /** 项目级目录条目压过全局扩展：作用域优先于承载方式（与子代理同一裁决）。 */
    @Test
    fun projectDirectory_overridesGlobalExtension() {
        val merged = merge(
            global = emptyList(),
            globalExt = listOf("srv" to McpOrigin.EXTENSION),
            project = listOf("srv" to McpOrigin.DIRECTORY),
            projectExt = emptyList()
        )
        assertEquals(McpScope.PROJECT to McpOrigin.DIRECTORY, merged["srv"])
    }

    /** 项目级扩展最高：它是「离当前项目最近」的一层。 */
    @Test
    fun projectExtension_winsOverAll() {
        val merged = merge(
            global = listOf("srv" to McpOrigin.DIRECTORY),
            globalExt = listOf("srv" to McpOrigin.EXTENSION),
            project = listOf("srv" to McpOrigin.DIRECTORY),
            projectExt = listOf("srv" to McpOrigin.EXTENSION)
        )
        assertEquals(McpScope.PROJECT to McpOrigin.EXTENSION, merged["srv"])
    }

    /** 不同名字互不吞并，合并后都保留。 */
    @Test
    fun distinctNames_coexist() {
        val merged = merge(
            global = listOf("a" to McpOrigin.DIRECTORY),
            globalExt = listOf("b" to McpOrigin.EXTENSION),
            project = emptyList(),
            projectExt = emptyList()
        )
        assertEquals(setOf("a", "b"), merged.keys)
    }

    /**
     * 只读判定：名字与作用域都命中扩展贡献时才算只读。
     * 作用域不同（扩展是全局、操作指向项目）时不算——那是另一个作用域的同名条目，
     * 误判会让用户无法管理自己的同名 server。
     */
    @Test
    fun readOnly_onlyWhenNameAndScopeBothMatchExtension() {
        val extServers = listOf(Triple("srv", McpScope.GLOBAL, McpOrigin.EXTENSION))

        fun isReadOnly(name: String, scope: McpScope) = extServers.any {
            keyOf(it.first) == keyOf(name) && it.second == scope && it.third == McpOrigin.EXTENSION
        }

        assertTrue("同名同作用域命中也应只读（含大小写/空白差异）", isReadOnly(" SRV ", McpScope.GLOBAL))
        assertFalse("作用域不同不应误判为只读", isReadOnly("srv", McpScope.PROJECT))
        assertFalse("名字不同不应误判为只读", isReadOnly("other", McpScope.GLOBAL))
    }

    /**
     * 扩展 mcp.json 的条目格式是 `{name: {...}}`（无外层 `mcpServers`），
     * 与目录 mcp.json 的 `{"mcpServers": {...}}` 不同——接线时若走错解析入口会得到空表。
     */
    @Test
    fun extensionEntry_hasNoOuterMcpServersWrapper() {
        val extRaw = """{"srv":{"command":"python3","args":["-m","srv"]}}"""
        val dirRaw = """{"mcpServers":{"srv":{"command":"python3"}}}"""

        val extObj = kotlinx.serialization.json.Json.parseToJsonElement(extRaw)
            .let { it as kotlinx.serialization.json.JsonObject }
        val dirObj = kotlinx.serialization.json.Json.parseToJsonElement(dirRaw)
            .let { it as kotlinx.serialization.json.JsonObject }

        assertTrue("扩展条目直接就是 name→config", extObj["srv"] is kotlinx.serialization.json.JsonObject)
        assertEquals("扩展格式里不该出现 mcpServers 键", null, extObj["mcpServers"])
        assertTrue("目录格式才带 mcpServers 外层", dirObj["mcpServers"] is kotlinx.serialization.json.JsonObject)
    }
}
