package com.aicode.feature.agent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.agent.domain.session.AutoModeMigrationFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionModeDataStore by preferencesDataStore(
    name = "session_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** [AutoModeMigrationFlag] 的 DataStore 实现。 */
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
