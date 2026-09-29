package com.aicode.feature.virtualscreen.domain

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.virtualscreen.domain.model.VirtualScreenSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏会话编排：把 host 的原子指令（OPEN/LAUNCH/CLOSE）组合成「一次可用的调试会话」。
 *
 * 与 [VirtualScreenHostManager] 的分工：后者只管「daemon 活着且能收指令」，本类管
 * 「开到哪一步、失败半途要不要回滚、关的时候有没有清干净」。
 */
@Singleton
class VirtualScreenController @Inject constructor(
    private val hostManager: VirtualScreenHostManager
) {
    private companion object {
        const val TAG = "VirtualScreenCtrl"

        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 2400
        const val DEFAULT_DPI = 440

        /**
         * 与 host 侧 `DEFAULT_FLAGS` 一致：
         * `PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED`。
         *
         * **后面两个（0x800/0x1000）不能省**：实测只传前三个（0x109）时，设备锁屏会让锁屏窗口
         * （`KEYGUARD_DIALOG`）盖住虚拟屏，无障碍只能读到 8 个锁屏节点，投进去的 App 完全不可用。
         * 加上后，锁屏状态下仍能读到 110 个真实节点并正常 click/swipe。
         * 两个都是 `@hide` 常量，只能硬编码；需 `ADD_ALWAYS_UNLOCKED_DISPLAY`（实测 shell 持有）。
         */
        const val DEFAULT_FLAGS = 0x1 or 0x8 or 0x100 or 0x800 or 0x1000

        const val TAG_OPENED = "VDS_OPENED"
        const val TAG_LAUNCHED = "VDS_LAUNCHED"
        const val TAG_CLOSED = "VDS_CLOSED"
        const val TAG_LIST = "VDS_LIST"
        const val TAG_PONG = "VDS_PONG"
    }

    /** 当前活动会话。宿主侧同一时刻只保留一个会话，语义更简单，也避免了「关错屏」的歧义。 */
    @Volatile
    var current: VirtualScreenSession? = null
        private set

    /**
     * 开一块虚拟屏并启动目标应用。
     *
     * 任一环节失败都会**回滚已创建的屏**：否则失败一次就在系统里留下一块无主虚拟屏，
     * 用户侧表现为「屏幕莫名多了一个（不可见的）显示器」，而重试还会再积一块。
     *
     * @param scope 会话 id，用于把事件挂到当前回合的轨迹上（见 [EventTrace.recordFor]）。
     * @return 成功的会话；失败返回 null（原因见 [lastError]）。返回 `Result` 而非抛异常，
     *   是因为「Shizuku 未授权」这类失败是**正常业务分支**，不该由调用方用 try/catch 处理。
     */
    suspend fun open(
        packageName: String,
        scope: String?,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI
    ): Result<VirtualScreenSession> {
        if (current != null) {
            return Result.failure(IllegalStateException("已有虚拟屏会话在运行（displayId=${current?.displayId}），请先关闭"))
        }

        val readyError = hostManager.ensureReady()
        if (readyError != null) {
            lastError = readyError
            return Result.failure(IllegalStateException(readyError))
        }

        EventTrace.recordFor(scope, "VD", "open 请求 pkg=$packageName ${width}x$height@$dpi")

        val openResp = hostManager.command("OPEN $width $height $dpi $DEFAULT_FLAGS", TAG_OPENED)
        val displayId = openResp?.removePrefix(TAG_OPENED)?.trim()?.toIntOrNull()
        if (displayId == null) {
            lastError = "创建虚拟屏失败（无有效 displayId）"
            EventTrace.recordFor(scope, "VD", "open 失败：未取得 displayId")
            return Result.failure(IllegalStateException(lastError))
        }
        FileLogger.i(TAG, "虚拟屏已创建 displayId=$displayId")

        val launchResp = hostManager.command("LAUNCH $displayId $packageName", TAG_LAUNCHED)
        if (launchResp == null) {
            // 半途失败必须回滚，否则留下无主屏。
            hostManager.command("CLOSE $displayId", TAG_CLOSED)
            lastError = "应用 $packageName 投屏失败，已回收虚拟屏"
            EventTrace.recordFor(scope, "VD", "launch 失败，已回滚 displayId=$displayId")
            return Result.failure(IllegalStateException(lastError))
        }

        val session = VirtualScreenSession(displayId, packageName, width, height, dpi)
        current = session
        lastError = null
        EventTrace.recordFor(scope, "VD", "open 完成 displayId=$displayId pkg=$packageName")
        return Result.success(session)
    }

    /**
     * 关闭当前会话。
     *
     * 顺序由 host 保证：**先 `force-stop` 目标应用、再 `release()` 显示器**。
     * `DESTROY_CONTENT_ON_REMOVAL` 只销毁窗口内容、不删 Activity 任务记录，漏做 force-stop
     * 会把任务「搬家」到物理屏污染 Recent（实测残留 6 条）。
     */
    suspend fun close(scope: String?): Boolean {
        val session = current ?: return true
        val resp = hostManager.command("CLOSE ${session.displayId}", TAG_CLOSED)
        current = null
        val ok = resp != null
        EventTrace.recordFor(scope, "VD", if (ok) "close 完成 displayId=${session.displayId}" else "close 失败 displayId=${session.displayId}")
        if (!ok) lastError = "关闭虚拟屏失败（displayId=${session.displayId}）"
        return ok
    }

    /**
     * 清理孤儿屏：进程被杀时 `release` 不会执行，会留下屏。
     *
     * 只处理**本 daemon 记录在册**的屏（`LIST`），不按屏名全局扫描：
     * 按名扫会误伤同名的其他会话，而 `LIST` 是权威来源。
     * 无会话时也调用一次，用于回收「上次崩溃留下的、daemon 仍记着的」屏。
     */
    suspend fun reclaimOrphans(scope: String?): Int {
        val resp = hostManager.command("LIST", TAG_LIST) ?: return 0
        val ids = resp.removePrefix(TAG_LIST).trim()
            .split(' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it != current?.displayId }
        if (ids.isEmpty()) return 0
        var reclaimed = 0
        for (id in ids) {
            if (hostManager.command("CLOSE $id", TAG_CLOSED) != null) reclaimed++
        }
        EventTrace.recordFor(scope, "VD", "回收孤儿屏 $reclaimed 个（${ids.joinToString()}）")
        return reclaimed
    }

    @Volatile
    var lastError: String? = null
        private set

    /** 仅探活，不产生副作用；供设置页显示状态。 */
    suspend fun isDaemonAlive(): Boolean =
        hostManager.command("PING", TAG_PONG, 3_000L) != null

    /** 仅供测试/卸载清理：让 daemon 退出并释放全部屏。 */
    suspend fun shutdown() {
        hostManager.shutdown()
        current = null
    }
}
