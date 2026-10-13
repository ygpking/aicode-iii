package com.aicode.feature.agent.domain.session

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionModeDataStore by preferencesDataStore(
    name = "session_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 「已有会话默认模式迁移」是否已执行过的标记位。抽象成接口以便单测替身。 */
interface AutoModeMigrationFlag {
    suspend fun isApplied(): Boolean
    suspend fun markApplied()
}

@Singleton
class AutoModeMigrationFlagStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AutoModeMigrationFlag {
    private companion object {
        val DONE_KEY = booleanPreferencesKey("auto_mode_default_applied")
    }

    override suspend fun isApplied(): Boolean =
        context.sessionModeDataStore.data.first()[DONE_KEY] == true

    override suspend fun markApplied() {
        context.sessionModeDataStore.edit { it[DONE_KEY] = true }
    }
}
