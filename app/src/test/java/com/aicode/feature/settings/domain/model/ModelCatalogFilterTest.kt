package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogFilterTest {

    @Test
    fun detectsHtml() {
        assertTrue(ModelCatalogFilter.looksLikeHtml("<!DOCTYPE html><html><body>hi</body></html>"))
        assertTrue(ModelCatalogFilter.looksLikeHtml("<html><head></head></html>"))
        assertTrue(ModelCatalogFilter.looksLikeHtml("<div>login</div>"))
    }

    @Test
    fun jsonIsNotHtml() {
        assertFalse(ModelCatalogFilter.looksLikeHtml("""{"data":[{"id":"gpt-4o"}]}"""))
        assertFalse(ModelCatalogFilter.looksLikeHtml(""))
        assertFalse(ModelCatalogFilter.looksLikeHtml(null))
    }

    @Test
    fun filtersNonChatModels() {
        assertFalse(ModelCatalogFilter.isChatModel("text-embedding-3-small"))
        assertFalse(ModelCatalogFilter.isChatModel("whisper-1"))
        assertFalse(ModelCatalogFilter.isChatModel("tts-1"))
        assertFalse(ModelCatalogFilter.isChatModel("dall-e-3"))
        assertFalse(ModelCatalogFilter.isChatModel("gpt-4o-audio-preview"))
    }

    @Test
    fun keepsChatModels() {
        assertTrue(ModelCatalogFilter.isChatModel("gpt-4o"))
        assertTrue(ModelCatalogFilter.isChatModel("claude-3-5-sonnet"))
        assertTrue(ModelCatalogFilter.isChatModel("deepseek-chat"))
        // 不误伤：带 image 但本身是对话模型（如 gpt-4o 变体）不在黑名单词表内
        assertTrue(ModelCatalogFilter.isChatModel("gpt-4o-mini"))
    }

    @Test
    fun doesNotOverFilterWordBoundaries() {
        // `imagenet` 含 image 子串，但非词边界，不应被过滤
        assertTrue(ModelCatalogFilter.isChatModel("imagenet-chat"))
    }

    @Test
    fun filterChatModelsRemovesAndDeduplicates() {
        val input = listOf(
            "gpt-4o",
            "text-embedding-3-small",
            "gpt-4o",
            "whisper-1",
            "claude-3-5-sonnet",
        )
        assertEquals(listOf("gpt-4o", "claude-3-5-sonnet"), ModelCatalogFilter.filterChatModels(input))
    }
}
