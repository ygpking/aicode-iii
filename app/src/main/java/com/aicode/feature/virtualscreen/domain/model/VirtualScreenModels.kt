package com.aicode.feature.virtualscreen.domain.model

/** 一次虚拟屏会话，`displayId` 由 host 在 OPEN 时动态分配（**不可假定为固定值**）。 */
data class VirtualScreenSession(
    val displayId: Int,
    val packageName: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** 创建时刻（elapsedRealtime），用于展示会话时长。 */
    val startedAt: Long = android.os.SystemClock.elapsedRealtime()
)

/** 虚拟屏 daemon 的运行态，供上层做状态提示与降级判断。 */
enum class VirtualScreenDaemonState {
    /** 尚未拉起或已被判定不可用。 */
    STOPPED,

    /** 正在拉起（含投递 dex 与等待端口就绪）。 */
    STARTING,

    /** 已就绪，可下发指令。 */
    READY,

    /** 已就绪但无权限（Shizuku 未授权/未运行），需用户处理。 */
    NO_PERMISSION
}
