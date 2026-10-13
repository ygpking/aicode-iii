package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性把已有根会话的模式切到 AUTO，配合「新会话默认 AUTO」让默认模式对老用户同样成立。
 *
 * 只跑一次：否则用户手动切回 BUILD 的会话会在下次启动被再次拉回 AUTO。
 * 真正的库操作在 [SessionUseCase.switchRootSessionsToAuto]（domain 层不得新增直连 DAO）。
 */
@Singleton
class AutoModeDefaultMigrator @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val flagStore: AutoModeMigrationFlag
) {
    private companion object {
        const val TAG = "AutoModeDefaultMigrator"
    }

    /** 幂等：已跑过直接返回；失败不写标记，下次启动重试。 */
    suspend fun applyIfNeeded() = withContext(Dispatchers.IO) {
        if (flagStore.isApplied()) return@withContext
        runCatchingCancellable {
            val changed = sessionUseCase.switchRootSessionsToAuto()
            flagStore.markApplied()
            FileLogger.i(TAG, "已将 $changed 个根会话默认模式切到 AUTO")
        }.onFailure { e ->
            FileLogger.w(TAG, "切换已有会话默认模式失败（下次启动重试）: ${e.message}")
        }
    }
}
