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
        // 标记位读失败时视为「已应用」而跳过本次迁移：读不到标记就无从判断迁移是否已做过，
        // 此时宁可少做一次，也不在状态不可信时批量改用户会话的模式。下次启动会重新尝试读取。
        // （DataStore 损坏与读取期 IO 异常由 preferencesCorruptionHandler 兜成「丢弃重建」，
        //   不会抛到这里；能到这里的只有 store 初始化 / 并发访问一类的运行时异常。）
        val applied = runCatchingCancellable { flagStore.isApplied() }
            .onFailure { FileLogger.w(TAG, "读取迁移标记失败，本次跳过: ${it.message}") }
            .getOrDefault(true)
        if (applied) return@withContext
        // 标记未置位才落到这里，重跑有两个来源：① 置位失败（markApplied 抛异常）；
        // ② 标记文件损坏被重置为默认值（isApplied() 返回 false，不是抛异常）。
        // 两者后果相同：用户手动切回 BUILD 的会话会被再次拉回 AUTO。
        runCatchingCancellable {
            val changed = sessionUseCase.switchRootSessionsToAuto()
            flagStore.markApplied()
            FileLogger.i(TAG, "已将 $changed 个根会话默认模式切到 AUTO")
        }.onFailure { e ->
            FileLogger.w(TAG, "切换已有会话默认模式失败（下次启动重试）: ${e.message}")
        }
    }
}
