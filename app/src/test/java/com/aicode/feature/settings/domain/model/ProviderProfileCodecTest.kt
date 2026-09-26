package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileCodecTest {

    private fun config(
        apiKey: String = "sk-secret",
        baseUrl: String = "https://api.openai.com/",
    ) = AIProviderConfig(
        id = "local-id",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        apiKeys = listOf("sk-a", "sk-b"),
        proxyPassword = "proxy-secret",
        baseUrl = baseUrl,
        defaultModel = "gpt-4o",
        models = listOf("gpt-4o", "gpt-4o-mini"),
        useResponseApi = true,
    )

    @Test
    fun exportDropsSecretsAndLocalState() {
        val json = ProviderProfileCodec.exportToJson(config())
        assertFalse(json.contains("sk-secret"))
        assertFalse(json.contains("sk-a"))
        assertFalse(json.contains("proxy-secret"))
        assertFalse(json.contains("local-id"))
        assertTrue(json.contains("api.openai.com"))
        assertTrue(json.contains("gpt-4o"))
    }

    @Test
    fun exportImportRoundTripsPortableFields() {
        val profile = ProviderProfileCodec.decode(ProviderProfileCodec.exportToJson(config()))
        assertEquals("OpenAI", profile!!.name)
        assertEquals("OPENAI", profile.type)
        assertEquals("https://api.openai.com/", profile.baseUrl)
        assertEquals("gpt-4o", profile.defaultModel)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), profile.models)
        assertTrue(profile.useResponseApi)
    }

    @Test
    fun toConfigProducesBlankCredentials() {
        val profile = ProviderProfileCodec.decode(ProviderProfileCodec.exportToJson(config()))!!
        val newConfig = ProviderProfileCodec.toConfig(profile, id = "new-id")
        assertEquals("new-id", newConfig.id)
        assertEquals("", newConfig.apiKey)
        assertTrue(newConfig.apiKeys.isEmpty())
        assertEquals(ProviderType.OPENAI, newConfig.type)
        assertEquals("gpt-4o", newConfig.effectiveModel)
    }

    @Test
    fun decodeRejectsBlankAndGarbage() {
        assertNull(ProviderProfileCodec.decode(null))
        assertNull(ProviderProfileCodec.decode(""))
        assertNull(ProviderProfileCodec.decode("not json"))
        assertNull(ProviderProfileCodec.decode("{}"))
    }

    @Test
    fun decodeRejectsUnknownType() {
        val json = """{"type":"MISTRAL","name":"X","baseUrl":"https://x.com/"}"""
        assertNull(ProviderProfileCodec.decode(json))
    }

    @Test
    fun decodeRejectsUnsafeBaseUrl() {
        val json = """{"type":"OPENAI","name":"X","baseUrl":"file:///etc/passwd"}"""
        assertNull(ProviderProfileCodec.decode(json))
    }

    @Test
    fun decodeRejectsFutureSchemaVersion() {
        val json = """{"schemaVersion":99,"type":"OPENAI","name":"X","baseUrl":"https://x.com/"}"""
        assertNull(ProviderProfileCodec.decode(json))
    }

    @Test
    fun decodeTreatsMissingVersionAsV0() {
        val json = """{"type":"OPENAI","name":"X","baseUrl":"https://x.com/"}"""
        assertEquals("X", ProviderProfileCodec.decode(json)!!.name)
    }

    @Test
    fun decodeIgnoresUnknownFields() {
        val json = """{"type":"OPENAI","name":"X","baseUrl":"https://x.com/","futureField":123}"""
        assertEquals("X", ProviderProfileCodec.decode(json)!!.name)
    }

    @Test
    fun decodeNormalizesBaseUrlOnImport() {
        val json = """{"type":"OPENAI","name":"X","baseUrl":"https://x.com/"}"""
        val newConfig = ProviderProfileCodec.toConfig(ProviderProfileCodec.decode(json)!!, "id")
        assertEquals("https://x.com", newConfig.baseUrl)
    }
}
