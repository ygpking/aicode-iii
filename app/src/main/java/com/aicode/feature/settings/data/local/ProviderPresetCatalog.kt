package com.aicode.feature.settings.data.local

import com.aicode.feature.settings.domain.model.EndpointSanitizer
import com.aicode.feature.settings.domain.model.UrlCheck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `providers.json`（provider 预设列表）的解析与加载校验（版本门 + 内容校验）。
 *
 * 背景：该文件既可内置（assets），也可由仓库远程下发并缓存到磁盘——属**外部输入**。
 * 旧实现直接 `decodeFromString`，无版本约束、无 id/URL 校验；远程文件一旦被污染或格式演进，
 * 可能塞进重复/非法条目，甚至把危险 baseUrl（`file:`/`javascript:`）绕过保存期校验带进配置。
 *
 * 版本门保持**宽松**（与 `RepoDataFetcher` 的磁盘/内置回退语义对齐，避免因旧文件缺版本号而回退）：
 * - 顶层为数组（当前格式）：视为 v0，放行；
 * - 顶层为借封皮 `{ "schemaVersion": N, "providers": [...] }`：N 缺失视为 v0 放行，仅当 N 高于
 *   当前支持版本时拒绝（返回 null，调用方回退）。
 *
 * 纯函数、零 IO，便于单测。
 */
internal object ProviderPresetCatalog {

    /** 当前支持的最高 schema 版本。 */
    const val CURRENT_SCHEMA_VERSION = 1

    @Serializable
    private data class Envelope(
        val schemaVersion: Int = 0,
        val providers: List<ProviderPreset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析并校验。返回 null 表示内容不可用（空/解析失败/版本不支持），调用方应回退到下一来源。
     */
    fun parse(content: String): List<ProviderPreset>? {
        val raw = decodeRaw(content) ?: return null
        return validate(raw)
    }

    private fun decodeRaw(content: String): List<ProviderPreset>? {
        if (content.isBlank()) return null
        // 先按数组（当前格式）；失败再按借封皮 `{schemaVersion, providers}`。
        runCatching { json.decodeFromString<List<ProviderPreset>>(content) }.getOrNull()?.let { return it }
        val envelope = runCatching { json.decodeFromString<Envelope>(content) }.getOrNull() ?: return null
        if (envelope.schemaVersion > CURRENT_SCHEMA_VERSION) return null
        return envelope.providers
    }

    /**
     * 加载校验：丢弃 id/name/type 为空、id 重复（首次出现优先）、baseUrl 不安全的条目。
     * baseUrl 为空的条目保留（不危险，仅待用户填写）。
     */
    fun validate(presets: List<ProviderPreset>): List<ProviderPreset> {
        val seen = HashSet<String>()
        val out = ArrayList<ProviderPreset>(presets.size)
        for (preset in presets) {
            if (preset.id.isBlank() || preset.name.isBlank() || preset.type.isBlank()) continue
            if (!isSafeBaseUrl(preset.baseUrl)) continue
            // 仅在条目通过校验后才占用 id，避免一个非法条目「毒化」后续同 id 的合法条目。
            if (!seen.add(preset.id)) continue
            out += preset
        }
        return out
    }

    private fun isSafeBaseUrl(url: String): Boolean =
        url.isBlank() || EndpointSanitizer.sanitize(url) is UrlCheck.Ok
}
