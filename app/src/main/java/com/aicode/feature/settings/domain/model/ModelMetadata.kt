package com.aicode.feature.settings.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    /**
     * models.dev `temperature`：该模型是否接受自定义 temperature。
     * false（含元数据缺失）表示服务端把采样温度固定住了，请求里必须不带该字段——
     * kimi-k3 / gpt-5 系等模型带了会直接 400。
     */
    val supportsCustomTemperature: Boolean = false,
    val modelType: ModelType = ModelType.CHAT,
    val supportsImageOutput: Boolean = false,
    val source: Source = Source.INFERRED,
    /** models.dev 思考强度档位（reasoning_options 中 effort 类型的 values，如 ["low","medium","high"]）；null/空 = 无档位（不显示思考强度选择）。 */
    val reasoningEffortOptions: List<String>? = null,
    /**
     * models.dev `reasoning_options` 里的 `toggle` 项：该模型声明支持**开关思考**（而非只能调档位）。
     * 实测目录里三种形态：
     * - `[effort]`：只能调档位（如 gpt-5.4、glm-5.3-flash）；
     * - `[toggle]`：只能开关、无档位（如 glm-5.1、qwq-plus）；
     * - `[toggle, effort]`：两者都支持（如 deepseek-v4-pro、qwen3.5-flash）。
     * 注意：这是「服务端列出该项」的事实，**不等于保证能关**（如 glm-5.3 系强制思考，
     * 目录里仍带 toggle）。故本项目只用它来判断「该不该给这个模型展示档位/开关提示」，
     * 不据此单方面发送关闭字段。
     */
    val supportsReasoningToggle: Boolean = false,
    /** models.dev cost：输入单价（USD/1M tokens）。 */
    val inputCostUsdPerM: Double? = null,
    /** models.dev cost：输出单价（USD/1M tokens）。 */
    val outputCostUsdPerM: Double? = null,
    /** models.dev cost：缓存读取单价（USD/1M tokens）。 */
    val cacheReadCostUsdPerM: Double? = null,
    /** models.dev cost：缓存写入单价（USD/1M tokens），通常高于普通输入价。 */
    val cacheWriteCostUsdPerM: Double? = null
) {
    enum class ModelType { CHAT, EMBEDDING }

    enum class Source {
        MODELS_DEV,
        INFERRED
    }
}

/**
 * 模型元数据缓存的键。
 * 元数据不能只按模型名索引：自定义单价按「提供商ID:模型名」存储，不同类型渠道对同一模型名的
 * 自动匹配结果也不同，只按模型名会串台（拿别的渠道的单价/能力）。
 */
fun modelMetadataKey(providerId: String, model: String): String = "$providerId:$model"

/**
 * 合并自定义元数据与自动解析（拉取/内置）元数据，自定义优先；窗口未填时保留自动值。
 * [base] 为空时构造兜底元数据（窗口 0，能力全 false）。
 */
fun mergeModelMetadata(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata {
    val a = base ?: ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 0,
        inputTokens = null,
        outputTokens = null
    )
    val c = custom ?: return a
    return a.copy(
        modelType = c.modelType,
        supportsVision = c.supportsVision,
        supportsImageOutput = c.supportsImageOutput,
        supportsTools = c.supportsTools,
        supportsReasoning = c.supportsReasoning,
        supportsReasoningToggle = c.supportsReasoningToggle,
        reasoningEffortOptions = c.reasoningEffortOptions ?: a.reasoningEffortOptions,
        contextTokens = c.contextTokens.takeIf { it > 0 } ?: a.contextTokens,
        inputTokens = c.inputTokens ?: a.inputTokens,
        outputTokens = c.outputTokens ?: a.outputTokens,
        inputCostUsdPerM = c.inputCostUsdPerM ?: a.inputCostUsdPerM,
        outputCostUsdPerM = c.outputCostUsdPerM ?: a.outputCostUsdPerM,
        cacheReadCostUsdPerM = c.cacheReadCostUsdPerM ?: a.cacheReadCostUsdPerM,
        cacheWriteCostUsdPerM = c.cacheWriteCostUsdPerM ?: a.cacheWriteCostUsdPerM
    )
}

