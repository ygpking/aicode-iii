package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.core.security.SecretVault
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.EndpointSanitizer
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.model.sanitized
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao,
    private val agentDatabase: AgentDatabase,
    private val secretVault: SecretVault
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
        private val json = Json { ignoreUnknownKeys = true }

        private fun encodeMap(map: Map<String, String>): String =
            if (map.isEmpty()) "" else json.encodeToString(map)

        private fun decodeMap(raw: String): Map<String, String> =
            if (raw.isBlank()) emptyMap()
            else runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? {
        return aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    /**
     * 保存提供商。排序值以数据库当前值为准（重排可能异步持久化，UI 传入的
     * sortOrder 可能陈旧，直接使用会撤销刚完成的排序）；新提供商取最大排序 +1。
     */
    override suspend fun saveProvider(provider: AIProviderConfig) {
        // baseUrl 安全清洗：把粘贴带来的全角/零宽字符转半角、拒绕危险 scheme（file:/javascript: 会被
        // 拼进 OkHttp 请求）；非法地址直接拒写（保留原值），避免存下打不通或有风险的地址。
        val sanitizedProvider = when (val check = EndpointSanitizer.sanitize(provider.baseUrl)) {
            is com.aicode.feature.settings.domain.model.UrlCheck.Ok -> provider.copy(baseUrl = check.sanitized)
            is com.aicode.feature.settings.domain.model.UrlCheck.Rejected -> {
                if (provider.baseUrl.isNotBlank()) {
                    FileLogger.e(TAG, "拒绝保存非法 baseUrl（${check.reason}）provider=${provider.id}")
                    return
                }
                provider
            }
        }
        agentDatabase.withTransaction {
            val current = aiProviderDao.getProviderById(sanitizedProvider.id)
            // 防数据销毁：既有密文解不开（Keystore 不可用/密钥变更）则拒写，不把真实 Key 覆盖成不可恢复的值。
            // apiKey 与 proxyPassword 同为 SecretVault 加密字段，需一并校验：否则 Keystore 失效时
            // 保存 provider 会把解不开的代理密码静默覆盖成新密文。
            if (current != null &&
                (!secretVault.canSafelyOverwrite(current.apiKey) ||
                    !secretVault.canSafelyOverwrite(current.proxyPassword))
            ) {
                FileLogger.e(TAG, "既有密文无法解密，拒绝写入以免覆盖真实凭据 provider=${sanitizedProvider.id}")
                return@withTransaction
            }
            val sortOrder = current?.sortOrder ?: (aiProviderDao.getMaxSortOrder() + 1)
            FileLogger.i(TAG, "保存提供商 id=${sanitizedProvider.id} name=${sanitizedProvider.name} sortOrder=$sortOrder")
            aiProviderDao.insertProvider(sanitizedProvider.copy(sortOrder = sortOrder).sanitized().toEntity())
        }
    }

    /**
     * 按传入顺序重排提供商。只写 id + sortOrder 两列，
     * 避免整行 REPLACE 覆盖并发修改的其它字段（如 apiKey/模型列表）。
     */
    override suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        FileLogger.d(TAG, "重排提供商 共 ${providers.size} 个")
        agentDatabase.withTransaction {
            providers.forEachIndexed { index, p -> aiProviderDao.updateSortOrder(p.id, index) }
        }
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "删除提供商 id=$id")
        aiProviderDao.deleteProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "切换模型 provider=$id model=$model")
        aiProviderDao.setSelectedModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "更新模型列表 provider=$id 共 ${models.size} 个")
        aiProviderDao.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "设置提供商状态 provider=$id isEnabled=$isEnabled")
        aiProviderDao.setProviderEnabled(id, isEnabled)
    }

    private fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = try { ProviderType.valueOf(type) } catch (e: Exception) { ProviderType.OPENAI },
            apiKey = secretVault.decrypt(apiKey).orEmpty(),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys.split("\n").mapNotNull { secretVault.decrypt(it.trim()) }.filter { it.isNotEmpty() },
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(keyRotationStrategy) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes
                .split(",").mapNotNull { it.trim().toIntOrNull() }.distinct(),
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = runCatching { ProxyType.valueOf(proxyType) }.getOrDefault(ProxyType.HTTP),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = secretVault.decrypt(proxyPassword).orEmpty(),
            customHeaders = decodeMap(customHeaders),
            scriptParams = decodeMap(scriptParams)
        ).sanitized()
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = secretVault.encrypt(apiKey).orEmpty(),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys.joinToString("\n") { secretVault.encrypt(it).orEmpty() },
            keyRotationStrategy = keyRotationStrategy.name,
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes.joinToString(","),
            baseUrl = baseUrl,
            useFullUrl = useFullUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = proxyType.name,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = secretVault.encrypt(proxyPassword).orEmpty(),
            customHeaders = encodeMap(customHeaders),
            scriptParams = encodeMap(scriptParams)
        )
    }
}
