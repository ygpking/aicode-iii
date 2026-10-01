package com.aicode.feature.settings.domain.model

/**
 * 新手引导演示用的兜底模型。
 *
 * 引导流程里用户还没配好 Key、模型列表拉不到时，用这条静态模型让演示能走完。
 * 之前该字面量在 MainActivity 与 ProviderEditorScreen 里各写一份（共 4 处），
 * 改动时易漏——收敛到这里单一维护。
 */
const val ONBOARDING_FALLBACK_MODEL = "deepseek-v4-flash"
