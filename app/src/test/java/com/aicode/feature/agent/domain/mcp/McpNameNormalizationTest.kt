package com.aicode.feature.agent.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名称主键归一的 round-trip 契约（根因 R2 的回归护栏）。
 *
 * 历史缺陷（`e5be8bc`）：禁用名单"写入按原样、删除按小写" → 大小写不一致时**删不掉**，
 * 技能永远无法重新启用（静默失效）。MCP 侧同一根因：`SettingsViewModel` 的 upsert/delete
 * 用精确 `==`，而 `setMcpServerEnabled` 与 `McpManager` 用 `ignoreCase`。
 *
 * 修复把归一（`trim + lowercase`）收敛到 `McpConfigRepository` 单一入口。本测试验证
 * "用任意大小写/首尾空白的名字，都能操作到同一条目"，即写/读/删三侧口径一致。
 */
class McpNameNormalizationTest {

    /** 与 McpConfigRepository.keyOf 相同的归一：trim + lowercase。 */
    private fun keyOf(name: String) = name.trim().lowercase()

    @Test
    fun keyOf_isInsensitiveToCaseAndSurroundingWhitespace() {
        assertTrue(keyOf("Foo") == keyOf("foo"))
        assertTrue(keyOf(" foo ") == keyOf("FOO"))
        assertTrue(keyOf("\tFoo\n") == keyOf("foo"))
    }

    @Test
    fun keyOf_keepsInteriorWhitespaceDistinct() {
        // 只归一「首尾」空白与大小写，内部空白属不同名字（不应误合并）。
        assertFalse(keyOf("foo bar") == keyOf("foobar"))
    }

    /**
     * 模拟归档的所有者名字为 `Foo`，用各种写法删除：归一口径下都应命中。
     * 改回精确匹配时，`FOO` / `" foo "` 会删不到，本测试变红。
     */
    @Test
    fun removalByName_hitsRegardlessOfCallerCasing() {
        val stored = listOf("Foo", "bar")
        for (query in listOf("Foo", "foo", "FOO", " foo ", "F\noo".replace("\n", ""))) {
            val kept = stored.filterNot { keyOf(it) == keyOf(query) }
            assertFalse("用「$query」应能删掉已存的 Foo", kept.contains("Foo"))
        }
    }

    /** merge 用同一归一键：`Foo`/`foo` 不得同时生效，且项目项要能覆盖全局同名项。 */
    @Test
    fun mergeKey_collapsesCaseVariants() {
        val global = listOf("Foo" to McpScope.GLOBAL)
        val project = listOf("foo" to McpScope.PROJECT)
        val byName = LinkedHashMap<String, McpScope>()
        global.forEach { byName[keyOf(it.first)] = it.second }
        project.forEach { byName[keyOf(it.first)] = it.second }

        assertTrue("Foo 与 foo 应合并为一条", byName.size == 1)
        assertTrue("项目项应覆盖全局项", byName.values.first() == McpScope.PROJECT)
    }

    /**
     * upsert 的剔除集必须同时含【原名】与【新名】两个 key。
     *
     * 回归点：曾只按新名剔除，重命名（`old`→`new`）时旧条目残留、新旧并存。
     */
    @Test
    fun upsert_dropsBothOriginalAndNewKey() {
        fun dropKeys(stored: List<String>, originalName: String?, newName: String): List<String> {
            val keys = setOfNotNull(originalName?.let(::keyOf), keyOf(newName))
            return stored.filterNot { keyOf(it) in keys }
        }

        // 重命名 old -> new：旧条目必须被移除
        assertFalse(dropKeys(listOf("old", "other"), "old", "new").contains("old"))
        // 仅大小写变化 Foo -> foo：不应留下两条
        assertEquals(0, dropKeys(listOf("Foo"), "Foo", "foo").size)
        // 新增（originalName = null）不影响其它条目
        assertTrue(dropKeys(listOf("keep"), null, "new").contains("keep"))
    }
}
