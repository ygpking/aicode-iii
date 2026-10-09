package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.compactionModelDataStore by preferencesDataStore(
    name = "compaction_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「压缩专用模型」选择（providerId + model 两字符串）。
 *
 * 上下文压缩默认跟随当前聊天模型；当用户在此指定一个专用模型后，
 * 压缩轮会临时切换到该专用模型发送摘要请求，发完恢复聊天模型。
 * providerId 为空（未配置）即视为「跟随当前聊天模型」。
 */
@Singleton
class CompactionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.compactionModelDataStore, "compaction_provider_id", "compaction_model",
    reasoningEffortKeyName = "compaction_reasoning_effort"
) {

    /** 写入压缩专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setCompactionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——回退到「跟随当前聊天模型」。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前压缩专用 providerId（冷读用）。 */
    suspend fun getCompactionProviderId(): String = readProviderId()

    /** 读取一次当前压缩专用 model（冷读用）。 */
    suspend fun getCompactionModel(): String = readModel()

    /**
     * 读取压缩请求使用的推理档位（冷读用）；空串 = 不指定（走服务端默认）。
     * 压缩是一次性摘要任务，把档位调低（或在不支持关闭的模型上降到最低合法档）
     * 能显著减少「思考耗尽输出预算、摘要为空」的风险。
     */
    suspend fun getCompactionReasoningEffort(): String = readReasoningEffort()

    /** 写入压缩请求的推理档位（空串 = 不指定）。 */
    suspend fun setCompactionReasoningEffort(effort: String) = setReasoningEffort(effort)
}
