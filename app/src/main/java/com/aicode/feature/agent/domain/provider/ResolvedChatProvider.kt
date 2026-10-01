package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在**工作流之外**（工具内部的一次性 LLM 调用）解析并构造可用 provider 的唯一入口。
 *
 * 为什么存在（根因 R1「复制式传播」）：`ViewImageTool` 曾私有地持有一整套
 * 「resolveCurrentChatConfig + createStandaloneProvider」逻辑；记忆整理要发同类请求，
 * 若照抄一份，日后 provider 字段（超时、缓存断点、温度规则）每加一项就会有一处漏改——
 * provider 配置漏项是**静默失效**：请求能发出去，只是行为悄悄不同。
 *
 * 与 `StatefulAgentWorkflow` 的分工：工作流持有的是「本轮对话」的 provider 实例（带多 Key 切换、
 * 会话日志归档），本类构造的是**旁路**实例（一次性请求，如识图、记忆整理），两者互不影响。
 */
@Singleton
class ResolvedChatProvider @Inject constructor(
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val modelMetadataService: ModelMetadataService,
    private val sessionUseCase: SessionUseCase,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
) {

    /**
     * 解析「当前会话实际使用的模型配置」：会话绑定的 provider 优先，回退全局默认。
     * 未配置、已禁用或没有 Key 时返回 null（调用方据此给出可操作的提示，而不是发一个必失败的请求）。
     */
    suspend fun resolveCurrentChatConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                    return if (!boundModel.isNullOrBlank()) config.copy(selectedModel = boundModel) else config
                }
            }
        }
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    /** 按配置构造一次性 provider 实例（不注册多 Key 切换）。
     *  suspend：元数据解析与超时/重试设置读取都是挂起调用。 */
    suspend fun create(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        provider.apiKey = config.apiKey
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** 一步到位：解析 + 构造；未配置时返回 null。 */
    suspend fun resolve(sessionId: String?): AIProvider? =
        resolveCurrentChatConfig(sessionId)?.let { create(it, sessionId) }
}
