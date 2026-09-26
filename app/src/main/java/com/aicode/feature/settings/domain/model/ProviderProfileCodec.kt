package com.aicode.feature.settings.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 单个 provider 的**可移植档案**（导入导出契约）。
 *
 * 只包含分享给别人还有意义的字段：类型、名称、Base URL、模型列表与协议开关。
 * **不含任何凭据**（apiKey/apiKeys/代理密码）——导入后由用户自行填写 Key；
 * 也不含本地状态（id、排序、启用开关、面板脚本路径等）。
 *
 * 带 [schemaVersion]，导入时按版本门判断兼容性（缺失视为 v0，超前版本拒绝）。
 */
@Serializable
data class ProviderProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val type: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String = "",
    val models: List<String> = emptyList(),
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
) {
    companion object {
        /** 当前档案格式版本。 */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * provider 档案的编解码与校验（纯函数、零 IO）。
 *
 * 导出：从 [AIProviderConfig] 提取可移植字段（剥掉凭据与本地状态）。
 * 导入：解析 + 版本门 + 基本校验（type 合法、name/baseUrl 非空、baseUrl 安全）。
 */
object ProviderProfileCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val compactJson = Json { ignoreUnknownKeys = true }

    /** 把 provider 配置导出为可分享的档案（不含凭据）。 */
    fun export(config: AIProviderConfig): ProviderProfile = ProviderProfile(
        type = config.type.name,
        name = config.name,
        baseUrl = config.baseUrl,
        defaultModel = config.defaultModel,
        models = config.models,
        useFullUrl = config.useFullUrl,
        useResponseApi = config.useResponseApi,
    )

    fun encode(profile: ProviderProfile): String = json.encodeToString(ProviderProfile.serializer(), profile)

    /** 直接导出为 JSON 文本。 */
    fun exportToJson(config: AIProviderConfig): String = encode(export(config))

    /**
     * 解析并校验档案。返回 null 表示不可用（空/非法 JSON/版本超前/字段非法/URL 不安全）。
     */
    fun decode(text: String?): ProviderProfile? {
        if (text.isNullOrBlank()) return null
        val profile = runCatching {
            compactJson.decodeFromString(ProviderProfile.serializer(), text)
        }.getOrNull() ?: return null
        return if (isValid(profile)) profile else null
    }

    private fun isValid(profile: ProviderProfile): Boolean {
        if (profile.schemaVersion > ProviderProfile.CURRENT_SCHEMA_VERSION) return false
        if (profile.type.isBlank() || profile.name.isBlank() || profile.baseUrl.isBlank()) return false
        runCatching { ProviderType.valueOf(profile.type) }.getOrNull() ?: return false
        // 复用保存期的同一套 URL 安全校验，避免导入成为绕过点。
        return EndpointSanitizer.sanitize(profile.baseUrl) is UrlCheck.Ok
    }

    /**
     * 把档案应用为一条新的 provider 配置：忽略档案里的本地状态，id 由调用方提供（如 UUID），
     * 凭据留空待用户填写。
     */
    fun toConfig(profile: ProviderProfile, id: String): AIProviderConfig {
        val type = ProviderType.valueOf(profile.type)
        return AIProviderConfig(
            id = id,
            name = profile.name,
            type = type,
            apiKey = "",
            baseUrl = EndpointSanitizer.sanitize(profile.baseUrl).let {
                (it as? UrlCheck.Ok)?.sanitized ?: profile.baseUrl
            },
            defaultModel = profile.defaultModel,
            models = profile.models,
            selectedModel = profile.defaultModel,
            useFullUrl = profile.useFullUrl,
            useResponseApi = profile.useResponseApi,
        )
    }
}
