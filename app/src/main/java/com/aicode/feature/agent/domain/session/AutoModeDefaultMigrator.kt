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
        // 标记位读失败时视为「已应用」而跳过本次迁移：宁可少做一次，也不在标记不可信时
        // 批量改用户会话的模式（标记写不进时每次启动都会重跑，反复把用户手动切回 BUILD 的
        // 会话拉回 AUTO）。下次启动会重新尝试读取。
        val applied = runCatchingCancellable { flagStore.isApplied() }
            .onFailure { FileLogger.w(TAG, "读取迁移标记失败，本次跳过: ${it.message}") }
            .getOrDefault(true)
        if (applied) return@withContext
        runCatchingCancellable {
            val changed = sessionUseCase.switchRootSessionsToAuto()
            flagStore.markApplied()
            FileLogger.i(TAG, "已将 $changed 个根会话默认模式切到 AUTO")
        }.onFailure { e ->
            FileLogger.w(TAG, "切换已有会话默认模式失败（下次启动重试）: ${e.message}")
        }
    }
}
