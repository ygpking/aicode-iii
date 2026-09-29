package com.aicode.feature.virtualscreen.domain.model

/**
 * 一次虚拟屏会话，`displayId` 由 host 在 OPEN 时动态分配（**不可假定为固定值**）。
 */
data class VirtualScreenSession(
    val displayId: Int,
    val packageName: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    /**
     * 发起方会话 id（AI 会话/标签 id，`AgentContext.sessionId`）。
     *
     * **必须记录归属**：控制器按它索引会话，`dump`/`click`/`close` 都据此定位自己那块屏。
     * 此前不记归属、只留一个 `current` 单例，导致「B 会话的操作用到 A 的屏、B 的 close 关掉
     * A 的会话」这类静默串扰。
     */
    val ownerSessionId: String,
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
