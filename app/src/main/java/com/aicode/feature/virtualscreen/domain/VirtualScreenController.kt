package com.aicode.feature.virtualscreen.domain

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.virtualscreen.domain.model.VirtualScreenSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏会话编排：把 host 的原子指令（OPEN/LAUNCH/CLOSE）组合成「一次可用的调试会话」。
 *
 * 与 [VirtualScreenHostManager] 的分工：后者只管「daemon 活着且能收指令」，本类管
 * 「开到哪一步、失败半途要不要回滚、关的时候有没有清干净」。
 *
 * ## 多会话
 *
 * 按发起方会话 id（[VirtualScreenSession.ownerSessionId]）各持一块虚拟屏，可并行调试不同应用。
 * 所有操作都必须带 `scope`，由它定位到**自己**那块屏——此前只有单个 `current` 时，
 * 后一个会话的 `dump`/`close` 会落到前一个会话的屏上，且完全静默。
 *
 * ## 为什么禁止同一应用跨会话并存
 *
 * Android 对同一 uid 只有单实例：两块虚拟屏投同一个包，看到的是**同一个 activity 实例**，
 * 且关屏时的 `am force-stop` 会一并杀掉另一块屏上的它（宿主 `close()` 按包名停进程）。
 * 与其产生「关 A 屏顺带杀掉 B 屏」的隐蔽副作用，不如在建屏时就拒绝并说明原因。
 * 同一会话内重复 `open` 同一包则直接复用已有会话。
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

        /**
         * 同时在用的虚拟屏上限。
         *
         * 必要性：会话结束（聊天被删/被关）时**没有钩子会自动关屏**，屏只靠发起方的
         * `close` 或设置页「全部回收」清理。单会话时代最多漏一块；多会话后每多一个
         * 用过虚拟屏的聊天就多漏一块，会在系统里累积出多块不可见显示器。
         * 上限让泄漏有界，且失败信息明确（而不是默默把系统塞满）。
         */
        const val MAX_ACTIVE_SESSIONS = 4
    }

    /**
     * 活动会话表：`ownerSessionId -> 会话`。
     *
     * 用 [Mutex] 串行化 open/close 等改动：`open` 是 suspend 且中间有多次 await（建屏、投屏），
     * 两个会话同时开屏会交错，导致同一 displayId 被重复登记或半途态泄漏。读路径
     * （[sessionFor]/[activeSessions]）不加锁，靠 `@Volatile` 保证可见性即可。
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, VirtualScreenSession>()
    private val mutationMutex = Mutex()

    /** 当前有虚拟屏的会话数，供状态展示。 */
    val activeCount: Int get() = sessions.size

    /** 全部活动会话快照（按创建顺序），供设置页展示残留会话。 */
    fun activeSessions(): List<VirtualScreenSession> =
        sessions.values.sortedBy { it.startedAt }

    /** 取指定会话自己的那块屏。返回 null 表示该会话还没开屏。 */
    fun sessionFor(scope: String?): VirtualScreenSession? =
        scope?.let { sessions[it] }

    /**
     * 开一块虚拟屏并启动目标应用。
     *
     * 任一环节失败都会**回滚已创建的屏**：否则失败一次就在系统里留下一块无主虚拟屏，
     * 用户侧表现为「屏幕莫名多了一个（不可见的）显示器」，而重试还会再积一块。
     *
     * @param scope 发起方会话 id（`AgentContext.sessionId`），用于隔离各会话的屏并挂事件轨迹。
     * @return 成功的会话；失败返回 null（原因见 [lastError]）。返回 `Result` 而非抛异常，
     *   是因为「Shizuku 未授权」这类失败是**正常业务分支**，不该由调用方用 try/catch 处理。
     */
    suspend fun open(
        packageName: String,
        scope: String?,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI
    ): Result<VirtualScreenSession> = mutationMutex.withLock {
        if (scope.isNullOrBlank()) {
            lastError = "缺少会话标识，无法开屏"
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }

        // 同一会话重复 open 同一应用：复用已有屏，避免无谓地重建（重建会 force-stop 掉当前界面）。
        sessions[scope]?.let { existing ->
            if (existing.packageName == packageName) {
                EventTrace.recordFor(scope, "VD", "open 复用已有屏 displayId=${existing.displayId}")
                return@withLock Result.success(existing)
            }
            lastError = "本会话已在调试 ${existing.packageName}（displayId=${existing.displayId}），" +
                "请先 close 再切换应用"
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }

        // 同一应用不允许跨会话并存（见类注释：force-stop 会互相牵连）。
        sessions.entries.firstOrNull { it.value.packageName == packageName }?.let { (otherScope, other) ->
            lastError = "$packageName 已在另一个会话的虚拟屏中打开（displayId=${other.displayId}）。" +
                "同一应用只能占一块虚拟屏，请先让那个会话关闭后再试。"
            EventTrace.recordFor(scope, "VD", "open 拒绝：$packageName 已被会话 $otherScope 占用")
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }

        if (sessions.size >= MAX_ACTIVE_SESSIONS) {
            lastError = "同时打开的虚拟屏已达上限（$MAX_ACTIVE_SESSIONS 块）：" +
                sessions.values.joinToString("、") { it.packageName } +
                "。请先关闭不用的（或在设置 → 软件权限 里一键全部回收）。"
            EventTrace.recordFor(scope, "VD", "open 拒绝：已达会话上限 $MAX_ACTIVE_SESSIONS")
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }

        val readyError = hostManager.ensureReady()
        if (readyError != null) {
            lastError = readyError
            return@withLock Result.failure(IllegalStateException(readyError))
        }

        EventTrace.recordFor(scope, "VD", "open 请求 pkg=$packageName ${width}x$height@$dpi")

        val openResp = hostManager.command("OPEN $width $height $dpi $DEFAULT_FLAGS", TAG_OPENED)
        val displayId = openResp?.removePrefix(TAG_OPENED)?.trim()?.toIntOrNull()
        if (displayId == null) {
            lastError = "创建虚拟屏失败（无有效 displayId）"
            EventTrace.recordFor(scope, "VD", "open 失败：未取得 displayId")
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }
        FileLogger.i(TAG, "虚拟屏已创建 displayId=$displayId session=$scope")

        val launchResp = hostManager.command("LAUNCH $displayId $packageName", TAG_LAUNCHED)
        if (launchResp == null) {
            // 半途失败必须回滚，否则留下无主屏。
            hostManager.command("CLOSE $displayId", TAG_CLOSED)
            lastError = "应用 $packageName 投屏失败，已回收虚拟屏"
            EventTrace.recordFor(scope, "VD", "launch 失败，已回滚 displayId=$displayId")
            return@withLock Result.failure(IllegalStateException(lastError!!))
        }

        val session = VirtualScreenSession(
            displayId = displayId,
            packageName = packageName,
            width = width,
            height = height,
            dpi = dpi,
            ownerSessionId = scope
        )
        sessions[scope] = session
        lastError = null
        EventTrace.recordFor(scope, "VD", "open 完成 displayId=$displayId pkg=$packageName")
        Result.success(session)
    }

    /**
     * 关闭指定会话自己的虚拟屏。
     *
     * @return true 表示已关闭（含「本来就没有」的幂等情形），false 表示关闭指令失败。
     */
    suspend fun close(scope: String?): Boolean = mutationMutex.withLock {
        val session = scope?.let { sessions[it] } ?: return@withLock true
        val resp = hostManager.command("CLOSE ${session.displayId}", TAG_CLOSED)
        if (resp != null) sessions.remove(scope)
        val ok = resp != null
        EventTrace.recordFor(
            scope, "VD",
            if (ok) "close 完成 displayId=${session.displayId}" else "close 失败 displayId=${session.displayId}"
        )
        if (!ok) lastError = "关闭虚拟屏失败（displayId=${session.displayId}）"
        ok
    }

    /**
     * 关闭全部会话（设置页「清理残留会话」与 daemon 停机前使用）。
     *
     * 返回成功关闭的数量。逐个关而非直接 `shutdown` daemon：daemon 可能还有其他用途，
     * 且这样能保留每个会话各自的 CLOSE 轨迹。
     */
    suspend fun closeAll(scope: String? = null): Int = mutationMutex.withLock {
        var closed = 0
        for (id in sessions.keys.toList()) {
            val session = sessions[id] ?: continue
            if (hostManager.command("CLOSE ${session.displayId}", TAG_CLOSED) != null) {
                sessions.remove(id)
                closed++
            }
        }
        if (closed > 0) EventTrace.recordFor(scope, "VD", "closeAll 关闭 $closed 个会话")
        closed
    }

    /**
     * 清理孤儿屏：进程被杀时 `release` 不会执行，会留下屏。
     *
     * 只处理**本 daemon 记录在册**的屏（`LIST`），不按屏名全局扫描：
     * 按名扫会误伤同名的其他会话，而 `LIST` 是权威来源。
     * 同时排除**所有仍登记在册的会话**的屏——多会话下若只排除某一个，
     * 会把兄弟会话正在用的屏当孤儿回收掉。
     */
    suspend fun reclaimOrphans(scope: String?): Int = mutationMutex.withLock {
        val resp = hostManager.command("LIST", TAG_LIST) ?: return@withLock 0
        val known = sessions.values.map { it.displayId }.toSet()
        val ids = resp.removePrefix(TAG_LIST).trim()
            .split(' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it !in known }
        if (ids.isEmpty()) return@withLock 0
        var reclaimed = 0
        for (id in ids) {
            if (hostManager.command("CLOSE $id", TAG_CLOSED) != null) reclaimed++
        }
        EventTrace.recordFor(scope, "VD", "回收孤儿屏 $reclaimed 个（${ids.joinToString()}）")
        reclaimed
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
        sessions.clear()
    }
}
