package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.remote.ContainerImageDownloader
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 默认容器的首启准备进度，供设置页展示。 */
sealed interface DefaultContainerState {
    /** 尚未开始或无需准备（已是 Ubuntu / 已准备过）。 */
    data object Idle : DefaultContainerState

    /** 正在下载 Ubuntu rootfs。 */
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : DefaultContainerState

    /** 已就绪，Ubuntu 已导入并切为默认容器。 */
    data object Ready : DefaultContainerState

    /** 准备失败，已回退内置 Alpine。[message] 仅供日志排查用。 */
    data class Failed(val message: String) : DefaultContainerState
}

/**
 * 首次启动自动把默认容器备成 Ubuntu。
 *
 * 内置 Alpine 是 musl，其 OpenJDK 在容器里无法加载（实测报 libjli.so 缺失），编译 Android 一类
 * 需要 JVM 的工作必须先换 glibc 发行版。这里在首启后台下载 Ubuntu rootfs 并导入为默认容器；
 * Alpine 原封不动保留作离线兜底，任一步失败都静默回退，不阻塞任何流程。
 *
 * 只跑一次：标记写入后不再触发；已有 Ubuntu 容器（用户自己导入过）时也直接跳过。
 * 已装机用户不受影响——只有首次启动（无标记、无 Ubuntu）才会走这里。
 */
@Singleton
class DefaultContainerBootstrap @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: ContainerImageCatalog,
    private val downloader: ContainerImageDownloader,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "DefaultContainerBootstrap"
        const val PREFS = "container_bootstrap"
        const val KEY_DONE = "default_bootstrap_done"

        /** 首启准备的目标镜像 id（对应 assets/container-images.json）。 */
        const val TARGET_ENTRY_ID = "ubuntu-24.04"

        /** 下载源尝试顺序：国内源优先，官方源兜底。 */
        val SOURCE_ORDER = listOf("huawei", "aliyun", "official")

        /** 判定「已有 Ubuntu 容器」的发行版标签。 */
        const val UBUNTU_MARKER = "Ubuntu"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<DefaultContainerState>(DefaultContainerState.Idle)
    val state: StateFlow<DefaultContainerState> = _state.asStateFlow()

    /**
     * 幂等入口：已在准备/已就绪/已标记过时直接返回。
     * 失败只记日志并置 [DefaultContainerState.Failed]，不抛异常——启动期不能被它拖垮。
     */
    fun startIfNeeded() {
        if (isDone()) return
        if (_state.value !is DefaultContainerState.Idle) return
        scope.launch { run() }
    }

    private fun isDone(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    private fun markDone() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }

    private suspend fun run() {
        try {
            val entry = catalog.load().firstOrNull { it.id == TARGET_ENTRY_ID }
            if (entry == null) {
                // 目录里没有目标镜像：标记完成，避免每次启动重试无意义扫描。
                FileLogger.w(TAG, "镜像目录缺少 $TARGET_ENTRY_ID，跳过首启准备")
                markDone()
                return
            }

            if (hasUbuntuProfile()) {
                FileLogger.i(TAG, "已存在 Ubuntu 容器，跳过首启准备")
                markDone()
                _state.value = DefaultContainerState.Ready
                return
            }

            val (url, sourceId) = resolveUrl(entry) ?: run {
                FileLogger.w(TAG, "所有下载源都无 $TARGET_ENTRY_ID 的可用地址，回退内置 Alpine")
                _state.value = DefaultContainerState.Failed("无可用下载源")
                return
            }

            FileLogger.i(TAG, "首启准备 Ubuntu：source=$sourceId url=$url")
            _state.value = DefaultContainerState.Downloading(0, 0)
            val fileName = "download_${entry.id}_${entry.version}_${System.currentTimeMillis()}.tar.gz"
            val uri = downloader.download(url, fileName) { read, total ->
                _state.value = DefaultContainerState.Downloading(read, total)
            }

            val profile = ContainerProfile(
                id = "custom-ubuntu-bootstrap",
                name = "${entry.name} ${entry.version}",
                rootfsSource = RootfsSource.LocalFile(uri),
                shellPath = null,
                extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS,
                isBuiltin = false
            )
            withContext(Dispatchers.IO) {
                containerSettingsRepository.upsertCustomProfile(profile)
                containerSettingsRepository.setActiveProfile(profile.id)
                containerSettingsRepository.setDefaultContainerId(profile.id)
            }
            markDone()
            _state.value = DefaultContainerState.Ready
            FileLogger.i(TAG, "默认容器已备成 Ubuntu（${profile.id}）")
        } catch (e: Exception) {
            // 失败一律回退：不写标记（下次启动可重试），默认容器仍是内置 Alpine。
            FileLogger.w(TAG, "首启准备 Ubuntu 失败，回退内置 Alpine: ${e.message}", e)
            _state.value = DefaultContainerState.Failed(e.message ?: "未知错误")
        }
    }

    /** 现有自定义容器里是否已有 Ubuntu（用户自己导入的也算，避免重复下载）。 */
    private suspend fun hasUbuntuProfile(): Boolean =
        containerSettingsRepository.customProfilesFlow.first()
            .any { it.name.startsWith(UBUNTU_MARKER) }

    /** 按 [SOURCE_ORDER] 找第一个能拼出地址的源，返回 URL 与该源 id。 */
    private fun resolveUrl(entry: ContainerImageEntry): Pair<String, String>? {
        val abi = ContainerImageCatalog.CURRENT_ABI
        for (sourceId in SOURCE_ORDER) {
            val url = catalog.urlFor(entry, sourceId, abi)
            if (url != null) return url to sourceId
        }
        return null
    }
}
