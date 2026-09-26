package com.aicode.feature.settings.domain.model

/**
 * 模型目录过滤器。
 *
 * 处理两类真实问题：
 * 1. **网关返回 HTML 而非 JSON 模型列表**（登录页/错误页被当成目录）——[looksLikeHtml] 识别；
 * 2. **目录里混入非对话模型**（embedding/tts/whisper/image/video 等）——[isChatModel] 过滤。
 *
 * 过滤词保守，避免误杀带 multimodal 的对话模型（如 `gpt-4o` 之类命名）。
 */
object ModelCatalogFilter {

    /** 粗判文本是否为 HTML（首段出现 HTML 特征标签/声明）。 */
    fun looksLikeHtml(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val head = text.trimStart().take(512).lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return true
        return Regex("<(html|head|body|div|script|title)\\b").containsMatchIn(head)
    }

    /** 非对话模型的关键词（按词边界匹配，避免 `image` 误伤 `imagenet` 之类）。 */
    private val NON_CHAT_PATTERNS = listOf(
        Regex("""(^|[-_/])embedding(s)?($|[-_/])"""),
        Regex("""(^|[-_/])embed(s)?($|[-_/])"""),
        Regex("""(^|[-_/])tts($|[-_/])"""),
        Regex("""(^|[-_/])whisper($|[-_/])"""),
        Regex("""(^|[-_/])audio($|[-_/])"""),
        Regex("""(^|[-_/])rerank($|[-_/])"""),
        Regex("""(^|[-_/])moderation($|[-_/])"""),
        Regex("""(^|[-_/])image-generation($|[-_/])"""),
        Regex("""(^|[-_/])dall-e($|[-_/])"""),
        Regex("""(^|[-_/])stable-diffusion($|[-_/])"""),
        Regex("""(^|[-_/])video($|[-_/])"""),
        Regex("""(^|[-_/])speech($|[-_/])"""),
    )

    fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT_PATTERNS.none { it.containsMatchIn(lower) }
    }

    /** 过滤一组模型 id：剔除非对话模型并去重（保持首次出现顺序）。 */
    fun filterChatModels(ids: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        return ids.filter { isChatModel(it) && seen.add(it) }
    }
}
