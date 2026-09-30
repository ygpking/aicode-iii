package com.aicode.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NameKey] 的归一契约（根因 R2 的回归护栏）。
 *
 * 历史事故（`e5be8bc`）：禁用名单「写入按原样、启用按小写 remove」→ 大小写不一致时删不掉。
 * 更隐蔽的一半：**存侧 `trim().lowercase()`、查侧只有 `lowercase()`** —— 首尾空白会让
 * 名字匹配不上。收敛到 NameKey 后两侧同一变换。
 */
class NameKeyTest {

    @Test
    fun of_isCaseAndWhitespaceInsensitive() {
        assertEquals(NameKey.of("Foo"), NameKey.of("foo"))
        assertEquals(NameKey.of(" foo "), NameKey.of("FOO"))
        assertEquals(NameKey.of("\tFoo\n"), NameKey.of("foo"))
    }

    /**
     * 回归点：查侧曾漏 trim。存的是 `" foo"` 的归一键、查的是 `"foo"`，
     * 若不 trim 二者不等 → 禁用/启用静默失效。
     */
    @Test
    fun of_makesStoreAndQuerySidesAgree() {
        val stored = NameKey.of(" foo ")   // 存侧
        val queried = NameKey.of("foo")    // 查侧
        assertEquals("存/查两侧必须得到同一个键", stored, queried)
    }

    @Test
    fun of_keepsInteriorWhitespaceDistinct() {
        assertNotEquals(NameKey.of("foo bar"), NameKey.of("foobar"))
    }

    @Test
    fun of_isIdempotent() {
        val once = NameKey.of(" Foo ")
        assertEquals(once, NameKey.of(once))
        assertTrue(once == once.lowercase())
    }

    /** 空/空白输入归一到空串，不抛异常。 */
    @Test
    fun of_blankYieldsEmpty() {
        assertEquals("", NameKey.of(""))
        assertEquals("", NameKey.of("   "))
    }
}
