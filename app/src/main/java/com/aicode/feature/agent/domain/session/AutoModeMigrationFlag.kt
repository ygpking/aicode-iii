package com.aicode.feature.agent.domain.session

/** 「已有会话默认模式迁移」是否已执行过的标记位。抽象成接口以便单测替身。 */
interface AutoModeMigrationFlag {
    suspend fun isApplied(): Boolean
    suspend fun markApplied()
}
