package com.aicode.feature.virtualscreen.presentation

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.virtualscreen.domain.VirtualScreenController
import com.aicode.feature.virtualscreen.domain.a11y.VirtualScreenA11yService
import com.aicode.feature.virtualscreen.domain.model.VirtualScreenSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.aicode.core.util.runCatchingCancellable

/** 设置页「虚拟屏」的状态快照。 */
data class VirtualScreenSettingsState(
    /** 无障碍服务是否已在系统设置里被勾选（用户可见的开关状态）。 */
    val a11yEnabledInSettings: Boolean = false,
    /** 无障碍服务是否已真正连上（勾选后系统可能尚未绑定，两者不同）。 */
    val a11yConnected: Boolean = false,
    /** host daemon 是否活着。用于区分「从未启动」与「启动过但已死」。 */
    val daemonAlive: Boolean = false,
    /**
     * 活动虚拟屏会话（每个 AI 会话一块屏）。
     *
     * 多会话后不再只可能是「一个残留屏」，故用列表：设置页显示数量与包名，并可一键全部清理。
     */
    val activeSessions: List<VirtualScreenSession> = emptyList(),
    val checking: Boolean = true
) {
    /** 完全就绪：无障碍可用即可操作。 */
    val usable: Boolean get() = a11yConnected
}

/**
 * 虚拟屏设置页状态与操作入口。
 *
 * ## 为什么需要这个入口
 *
 * 无障碍服务**必须由用户在系统设置里手动开启**（应用无权自行开启），而且实测发现
 * **MIUI 会在重装应用或 `force-stop` 后把本服务从已启用列表里移除**。没有一个能自助体检、
 * 一键跳转的入口，用户遇到「只能看不能点」时无从下手。
 */
@HiltViewModel
class VirtualScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VirtualScreenController
) : ViewModel() {

    private companion object {
        const val TAG = "VDSettings"
    }

    private val _state = MutableStateFlow(VirtualScreenSettingsState())
    val state: StateFlow<VirtualScreenSettingsState> = _state.asStateFlow()

    /** 重新体检。页面恢复（含从系统设置返回）与手动刷新时调用。 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            val enabled = isA11yEnabledInSettings()
            // isRunning 是「系统真的把服务连上了」；勾选但未连接时（如刚勾选、进程刚起）会短暂为 false。
            val connected = VirtualScreenA11yService.isRunning
            val daemon = runCatchingCancellable { controller.isDaemonAlive() }.getOrDefault(false)
            val active = controller.activeSessions()
            _state.update {
                it.copy(
                    a11yEnabledInSettings = enabled,
                    a11yConnected = connected,
                    daemonAlive = daemon,
                    activeSessions = active,
                    checking = false
                )
            }
            FileLogger.i(
                TAG,
                "体检: enabled=$enabled connected=$connected daemon=$daemon 活动会话=${active.size}" +
                    active.joinToString("") { " [${it.packageName}@${it.displayId}]" }
            )
        }
    }

    /**
     * 关闭全部残留会话。
     *
     * 设置页显示的「有活动会话」通常意味着上一轮 AI 回合没走到 `close`（如被中断）。
     * 全部清掉，免得用户以为屏幕上多了个看不见的显示器。
     */
    fun closeActiveSession() {
        viewModelScope.launch {
            controller.closeAll(null)
            refresh()
        }
    }

    /** 释放 daemon（回收它记录在册的全部虚拟屏）。 */
    fun shutdownDaemon() {
        viewModelScope.launch {
            controller.shutdown()
            refresh()
        }
    }

    /**
     * 无障碍服务是否出现在系统的已启用列表里。
     *
     * 不能只看 `VirtualScreenA11yService.isRunning`：服务进程可能还没起来（勾选后系统异步绑定），
     * 那时 isRunning 为 false 会把「已开启但还没连上」误报成「没开启」，让用户反复去开关。
     */
    private fun isA11yEnabledInSettings(): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        if (raw.isNullOrBlank()) return false
        val target = ComponentName(context, VirtualScreenA11yService::class.java)
        return raw.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == target
        }
    }
}
