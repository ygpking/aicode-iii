package com.aicode.feature.settings.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetCatalogTest {

    private fun preset(
        id: String = "openai",
        name: String = "OpenAI",
        type: String = "OPENAI",
        baseUrl: String = "https://api.openai.com/",
    ) = ProviderPreset(id = id, name = name, type = type, baseUrl = baseUrl)

    // ---------- 数组格式（当前） ----------

    @Test
    fun parsesPlainArray() {
        val json = """[{"id":"openai","name":"OpenAI","type":"OPENAI","baseUrl":"https://api.openai.com/"}]"""
        val result = ProviderPresetCatalog.parse(json)
        assertEquals(1, result!!.size)
        assertEquals("openai", result.single().id)
    }

    @Test
    fun emptyArrayYieldsEmptyListNotNull() {
        // 空数组是合法内容（只是没预设），返回空列表让调用方决定回退策略。
        assertEquals(emptyList<ProviderPreset>(), ProviderPresetCatalog.parse("[]"))
    }

    @Test
    fun blankOrGarbageYieldsNull() {
        assertNull(ProviderPresetCatalog.parse(""))
        assertNull(ProviderPresetCatalog.parse("   "))
        assertNull(ProviderPresetCatalog.parse("not json at all"))
    }

    // ---------- 借封皮 + 版本门 ----------

    @Test
    fun parsesEnvelopeWithVersion() {
        val json = """{"schemaVersion":1,"providers":[{"id":"a","name":"A","type":"OPENAI","baseUrl":"https://a.com/"}]}"""
        val result = ProviderPresetCatalog.parse(json)
        assertEquals(1, result!!.size)
    }

    @Test
    fun envelopeWithoutVersionIsTreatedAsV0() {
        val json = """{"providers":[{"id":"a","name":"A","type":"OPENAI","baseUrl":"https://a.com/"}]}"""
        assertEquals(1, ProviderPresetCatalog.parse(json)!!.size)
    }

    @Test
    fun envelopeWithFutureVersionIsRejected() {
        // 高于当前支持版本 → 拒绝（返回 null，调用方回退），避免把未知格式当已知用。
        val json = """{"schemaVersion":99,"providers":[{"id":"a","name":"A","type":"OPENAI","baseUrl":"https://a.com/"}]}"""
        assertNull(ProviderPresetCatalog.parse(json))
    }

    // ---------- 加载校验 ----------

    @Test
    fun validateDropsEmptyIdNameType() {
        val result = ProviderPresetCatalog.validate(
            listOf(
                preset(id = "", name = "X"),
                preset(id = "ok", name = "", type = "OPENAI"),
                preset(id = "ok2", name = "Y", type = ""),
                preset(id = "keep"),
            ),
        )
        assertEquals(listOf("keep"), result.map { it.id })
    }

    @Test
    fun validateDeduplicatesByIdKeepingFirst() {
        val result = ProviderPresetCatalog.validate(
            listOf(
                preset(id = "dup", name = "First"),
                preset(id = "dup", name = "Second"),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("First", result.single().name)
    }

    @Test
    fun validateDropsUnsafeBaseUrl() {
        val result = ProviderPresetCatalog.validate(
            listOf(
                preset(id = "evil", baseUrl = "file:///etc/passwd"),
                preset(id = "evil2", baseUrl = "javascript:alert(1)"),
                preset(id = "evil3", baseUrl = "http://api.public.com/"),
                preset(id = "safe", baseUrl = "https://api.safe.com/"),
                preset(id = "local", baseUrl = "http://localhost:11434/"),
            ),
        )
        assertEquals(listOf("safe", "local"), result.map { it.id })
    }

    @Test
    fun validateKeepsBlankBaseUrl() {
        // baseUrl 为空不危险，只是待用户填写，应保留。
        val result = ProviderPresetCatalog.validate(listOf(preset(id = "blank", baseUrl = "")))
        assertEquals(listOf("blank"), result.map { it.id })
    }

    @Test
    fun validateIsAppliedThroughParse() {
        val json = """
            [
              {"id":"a","name":"A","type":"OPENAI","baseUrl":"file:///x"},
              {"id":"a","name":"A2","type":"OPENAI","baseUrl":"https://a.com/"},
              {"id":"","name":"","type":"","baseUrl":""}
            ]
        """.trimIndent()
        val result = ProviderPresetCatalog.parse(json)
        assertTrue(result != null && result.size == 1)
        assertEquals("A2", result!!.single().name)
    }
}
