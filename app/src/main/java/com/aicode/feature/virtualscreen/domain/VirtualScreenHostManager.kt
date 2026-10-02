package com.aicode.feature.virtualscreen.domain

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.virtualscreen.domain.model.VirtualScreenDaemonState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟屏宿主（daemon）的生命周期与指令通道管理。
 *
 * ## 为什么是 `app_process` 而不是 Shizuku UserService
 *
 * 实现期实测证伪了「UserService 建屏」这条路：UserService 运行在一个**不是合法应用进程**的
 * Java 进程里，拿不到可用 `Context`，而 `createVirtualDisplay` 必须传 Context，且其包名要与调用
 * uid 匹配。因此宿主改为 **`app_process` + 裸 dex**：在 shell(uid 2000) 进程里经
 * `ActivityThread.systemMain()` 取 systemContext，再 `createPackageContext("com.android.shell")` 换出
 * 与 uid 匹配的 context。详见 `docs/虚拟调试屏-落地方案.md`。
 *
 * ## 指令通道为什么是 socket
 *
 * Shizuku 的 `runCommand` 是**一次性**调用（命令跑完即返回，无法向长驻进程持续喂 stdin），
 * 故宿主监听 `127.0.0.1`，每次操作通过 `printf 'CMD\n' | toybox nc 127.0.0.1 <port>` 发送。
 * host 与 client 同为 shell(uid 2000)，**避开跨 uid 的 SELinux 限制**。
 *
 * ## dex 为什么从 APK 抽而不是从 app 私有目录
 *
 * 实测：`/data/user/0/<pkg>/files` 对 shell **完全不可读**（SELinux `app_data_file` 拦截，
 * 连父目录 stat 都 `Permission denied`）。而 `/data/app/**/base.apk` 是 `system:system 0644`，
 * shell 可读，且设备自带 `unzip`。故 dex 随 APK 打包在 `assets/virtualscreen/host.dex`，
 * 由 shell 用 `unzip -p` 抽到自己的私有目录 `/data/data/com.android.shell/files/` 再加载。
 *
 * ## 为什么需要「协议版本」
 *
 * daemon 经 `setsid` 脱离会话后**可跨 App 进程重启存活**（实测两次独立调用间 PING 通）。若 App
 * 升级改了 dex 而旧 daemon 仍在，只凭「PING 通」就复用会继续跑旧代码。故每次握手比对
 * [PROTOCOL]，不一致则发 EXIT 让旧 daemon 退出后重建。
 */
@Singleton
class VirtualScreenHostManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager
) {
    private companion object {
        const val TAG = "VirtualScreenHost"

        /** 与 `VirtualScreenHost.PROTOCOL` 对应；host 侧改协议时必须同步递增。 */
        const val PROTOCOL = 2

        /** 监听端口。仅绑 127.0.0.1；daemon 会自行判活，端口被占则退出。 */
        const val PORT = 19600

        /** shell 私有目录，shell(2000) 与 app_process 子进程都可读写。 */
        const val SHELL_DIR = "/data/data/com.android.shell/files/virtualscreen"

        /** dex 的 asset 路径。`AssetManager.open` 要的是这条无 `assets/` 前缀的写法。 */
        const val ASSET_PATH = "virtualscreen/host.dex"

        /** 同一文件在 zip 里的条目路径；`unzip -p` 必须带 `assets/` 前缀。 */
        const val APK_ENTRY_PATH = "assets/$ASSET_PATH"

        const val TAG_READY = "VDS_READY"
        const val TAG_LISTENING = "VDS_LISTENING"
        const val TAG_PONG = "VDS_PONG"
        const val TAG_OPENED = "VDS_OPENED"
        const val TAG_LAUNCHED = "VDS_LAUNCHED"
        const val TAG_CLOSED = "VDS_CLOSED"
        const val TAG_LIST = "VDS_LIST"
        const val TAG_BYE = "VDS_BYE"
        const val TAG_ERR = "VDS_ERR"

        /** 启动 daemon 的 exec 超时：含 dex 解压与 JVM 冷启，给足余量。 */
        const val LAUNCH_TIMEOUT_MS = 20_000L

        /** 单条指令超时。OPEN/LAUNCH 内部会起 Activity，留较宽裕。 */
        const val COMMAND_TIMEOUT_MS = 15_000L

        /** 等待端口就绪的总时长与轮询间隔。 */
        const val READY_TIMEOUT_MS = 8_000L
        const val READY_POLL_INTERVAL_MS = 300L

        /** 协议版本不符时最多重建几次。避免无上限重试把每次等待叠加成假死的体感。 */
        const val MAX_REBUILD_ATTEMPTS = 3
    }

    private val mutex = Mutex()

    @Volatile
    private var state: VirtualScreenDaemonState = VirtualScreenDaemonState.STOPPED

    /** 复用已就绪的 daemon。若为 null 或非 READY，[ensureReady] 会重新拉起。 */
    @Volatile
    private var ready: Boolean = false

    /** 最近一次错误原因，供工具在上层给用户可读提示（而非仅「失败」）。 */
    @Volatile
    var lastError: String? = null
        private set

    val daemonState: VirtualScreenDaemonState get() = state

    /**
     * 确保 daemon 就绪：投递 dex → 拉起 → 握手 → 探活。幂等，可重复调用。
     *
     * @return 就绪返回 null，否则返回可读的失败原因。
     */
    suspend fun ensureReady(): String? = mutex.withLock {
        if (ready) {
            // 已就绪仍探活一次：daemon 可能被系统清理（低内存）而无从感知，
            // 只在「上次确认就绪」上盲目相信会让后续每条指令都超时。
            if (ping()) return@withLock null
            FileLogger.w(TAG, "daemon 探活失败，判定已消失，重新拉起")
            ready = false
        }

        val stateError = checkShizuku()
        if (stateError != null) {
            state = VirtualScreenDaemonState.NO_PERMISSION
            lastError = stateError
            return@withLock stateError
        }

        // daemon 能跨 App 进程存活（setsid 脱离会话）。若它已在跑**且** dex 已是最新，
        // 就不必重投重拉——省掉一轮 dex 投递与进程启停，直接确认就绪即可。
        // `ready` 是内存态，App 重启就会丢；没有这一步的话每次冷开 App 都要白跑一遍全套。
        if (ping() && dexUpToDate()) {
            ready = true
            state = VirtualScreenDaemonState.READY
            FileLogger.i(TAG, "复用已在跑的 daemon（协议 $PROTOCOL）")
            return@withLock null
        }

        state = VirtualScreenDaemonState.STARTING
        lastError = null

        try {
            deployDex()
            ensureDaemonProcess()
            if (!awaitReady()) {
                val msg = "虚拟屏 daemon 启动后未就绪（端口 $PORT 无响应）"
                state = VirtualScreenDaemonState.STOPPED
                lastError = msg
                return@withLock msg
            }
            ready = true
            state = VirtualScreenDaemonState.READY
            FileLogger.i(TAG, "虚拟屏 daemon 就绪（协议 $PROTOCOL，端口 $PORT）")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：调用方（工具执行）被取消时应如实向上传播，
            // 否则会把取消伪装成「拉起 daemon 失败」，误导用户。
            state = VirtualScreenDaemonState.STOPPED
            throw e
        } catch (e: Exception) {
            val msg = "拉起虚拟屏 daemon 失败: ${e.message}"
            state = VirtualScreenDaemonState.STOPPED
            lastError = msg
            FileLogger.e(TAG, "拉起虚拟屏 daemon 失败", e)
            msg
        }
    }

    /**
     * 发送一条指令并返回其响应行。
     *
     * @param expect 期望的响应前缀（如 `VDS_OPENED`）；响应里若出现 `VDS_ERR` 一律按失败返回。
     * @return 成功的响应行；失败返回 null。
     */
    suspend fun command(
        line: String,
        expect: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): String? {
        val response = send(line, timeoutMs) ?: return null
        val matched = response.lineSequence().firstOrNull { it.startsWith(expect) }
        if (matched == null) {
            FileLogger.w(TAG, "指令 [$line] 响应异常，期望 $expect 前缀: ${response.take(200)}")
        }
        return matched
    }

    /** 发 `EXIT` 让 daemon 释放全部屏并退出；用于卸载/重建与调试。 */
    suspend fun shutdown() = mutex.withLock {
        if (ready || ping()) {
            send("EXIT", 5_000L)
        }
        ready = false
        state = VirtualScreenDaemonState.STOPPED
        Unit
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun checkShizuku(): String? = when (shizukuManager.state.value) {
        ShizukuState.READY -> null
        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku，虚拟屏不可用"
        ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行，请先启动 Shizuku"
        ShizukuState.PERMISSION_DENIED -> "本应用尚未获得 Shizuku 授权"
    }

    /**
     * 远端 dex 是否已与随包资源一致。给 [ensureReady] 的「复用已跑 daemon」快路径用；
     * 查不到（文件缺失/命令失败）按不一致处理，交由正常投递流程覆盖。
     */
    private suspend fun dexUpToDate(): Boolean {
        val assetMd5 = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_PATH).use { md5Hex(it.readBytes()) }
        }
        val result = shizukuManager.runCommand(
            "md5sum $SHELL_DIR/host.dex 2>/dev/null | cut -d' ' -f1",
            5_000L
        )
        val remote = result.output.trim().substringBefore('\n').trim()
        return remote.equals(assetMd5, ignoreCase = true)
    }

    /**
     * 把 APK 内的 dex 抽取到 shell 私有目录；已在位且内容一致时什么都不做。
     *
     * ## 为什么用 md5 而不是文件大小
     *
     * 原先只比大小。dex 字节数在改动后**可能恰好不变**（改字符串常量、调换指令等），
     * 实测确认过：两个不同版本的 host.dex 都是 11828 字节。此时会误判「已是最新」而跳过
     * 投递，新代码永远上不去。更危险的是反向：大小变了而旧 daemon 仍在跑——它已用旧 dex
     * 建好了方法表，文件被覆盖后 ArtMethod 解析会读到不一致的字节，实测表现为
     * `ThrowNoSuchMethodError` → **SIGSEGV 崩溃**。
     *
     * ## 为什么全部合到一条 shell 命令里
     *
     * 每次 `runCommand` 都是一次 Shizuku/Binder 往返（首次还可能触发 UserService 绑定）。
     * 拆成「md5 → PING → EXIT → unzip → md5」五次调用时，单次 open 的固定开销累计到肉眼可见
     * 的卡顿（用户实测到需要手动取消）。合成一条后**常见路径只需 1 次往返**。
     */
    private suspend fun deployDex() {
        val assetBytes = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_PATH).use(InputStream::readBytes)
        }
        val assetMd5 = md5Hex(assetBytes)
        val apkPath = context.applicationInfo.sourceDir

        // 顺序有语义：先比 md5，**只有内容不同才停 daemon**——否则每次 open 都会白重启一遍。
        val script = buildString {
            append("D=$SHELL_DIR; ")
            append("C=\$(md5sum \$D/host.dex 2>/dev/null | cut -d' ' -f1); ")
            append("if [ \"\$C\" = '$assetMd5' ]; then echo VDS_DEX_OK; exit 0; fi; ")
            // 内容不同：先让旧 daemon 退出（它持有旧 dex 的方法表，直接覆盖会崩）
            append("if printf 'PING\\n' | timeout 2 toybox nc -w 1 127.0.0.1 $PORT 2>/dev/null | grep -q $TAG_PONG; then ")
            append("printf 'EXIT\\n' | timeout 3 toybox nc 127.0.0.1 $PORT >/dev/null 2>&1; sleep 0.8; fi; ")
            append("mkdir -p \$D && unzip -p '$apkPath' '$APK_ENTRY_PATH' > \$D/host.dex; ")
            append("echo VDS_DEX_WRITTEN \$(md5sum \$D/host.dex 2>/dev/null | cut -d' ' -f1)")
        }
        val result = shizukuManager.runCommand(script, LAUNCH_TIMEOUT_MS)
        val out = result.output
        if (out.contains("VDS_DEX_OK")) {
            FileLogger.d(TAG, "dex 已是最新（md5=${assetMd5.take(8)}）")
            return
        }
        val written = out.lineSequence()
            .firstOrNull { it.startsWith("VDS_DEX_WRITTEN") }
            ?.removePrefix("VDS_DEX_WRITTEN")?.trim()
        if (written == null || !written.equals(assetMd5, ignoreCase = true)) {
            throw IllegalStateException(
                "dex 投递失败：期望 md5=$assetMd5，实得 ${written ?: "（无输出 exit=${result.exitCode}）"}"
            )
        }
        FileLogger.i(TAG, "dex 已投递到 $SHELL_DIR/host.dex（md5=${assetMd5.take(8)}）")
    }

    private fun md5Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 拉起 daemon 进程。
     *
     * **必须 `setsid` 脱离会话**：exec 调用返回后父进程即退出，若为普通子进程会被一并收走，
     * daemon 随之消失（实测必须 setsid+nohup 才能在下次独立调用时 PING 通）。
     * stdin 重定向到 /dev/null（父进程退出后 stdin 会 EOF）；daemon 靠命令行 `--server` 进服务模式，
     * 不依赖 stdin。
     */
    private suspend fun ensureDaemonProcess() {
        val cmd = "setsid nohup sh -c 'CLASSPATH=$SHELL_DIR/host.dex app_process " +
            "-Dpkg=com.android.shell /system/bin --nice-name=vdshost " +
            "com.aicode.vdscreen.VirtualScreenHost --server $PORT' " +
            "> $SHELL_DIR/host.log 2>&1 < /dev/null &"
        val result = shizukuManager.runCommand(cmd, LAUNCH_TIMEOUT_MS)
        FileLogger.d(TAG, "拉起 daemon，exit=${result.exitCode}")
    }

    /**
     * 轮询 `PING` 直到就绪或超时；顺带校验/纠偏协议版本。
     *
     * 版本不符时重建 daemon，但**重建次数有上限**：旧版 dex 写的 daemon 若因故
     * 反复回同一个版本（或拉起总失败），无上限重试会每次都耗掉完整的 [READY_TIMEOUT_MS]，
     * 对外表现为「点了没反应」。超限后如实报错，让上层能提示用户。
     */
    private suspend fun awaitReady(attempt: Int = 0): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val pong = send("PING", 2_000L)
            val line = pong?.lineSequence()?.firstOrNull { it.startsWith(TAG_PONG) }
            if (line != null) {
                val version = line.removePrefix(TAG_PONG).trim().toIntOrNull()
                if (version == PROTOCOL) return true
                if (attempt >= MAX_REBUILD_ATTEMPTS) {
                    lastError = "daemon 协议版本不匹配（实为 $version，期望 $PROTOCOL），重建 $attempt 次仍失败"
                    return false
                }
                // 版本不符：旧 daemon 仍在跑，让它退出后再走一遍拉起流程。
                FileLogger.w(TAG, "daemon 协议版本 $version != $PROTOCOL，重建（第 ${attempt + 1} 次）")
                send("EXIT", 3_000L)
                kotlinx.coroutines.delay(500)
                ensureDaemonProcess()
                return awaitReady(attempt + 1)
            }
            kotlinx.coroutines.delay(READY_POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun ping(): Boolean {
        val pong = send("PING", 2_000L) ?: return false
        val line = pong.lineSequence().firstOrNull { it.startsWith(TAG_PONG) } ?: return false
        return line.removePrefix(TAG_PONG).trim().toIntOrNull() == PROTOCOL
    }

    /**
     * 经 `toybox nc` 发一行指令并读取一行响应。
     *
     * 用 `nc` 而非自写客户端：设备自带，且省掉一份要与 APK 一起投递的可执行文件。
     * 指令经 stdin 传入、响应取自 stdout，故用 Shizuku 的 shell 执行最直接。
     */
    private suspend fun send(line: String, timeoutMs: Long): String? {
        if (line.contains('\n')) {
            FileLogger.w(TAG, "指令含换行，已拒绝: ${line.take(80)}")
            return null
        }
        // Shizuku 未就绪时直接返回：这是**常态**（用户还没授权 / 服务没启动），不是异常。
        // 不短路的话 runCommand → ensureBound 会抛 IllegalStateException，被下面的 catch 记成
        // 带完整堆栈的 WARN，而设置页每次恢复都会探活一次——实测（2026-09-29 日志）表现为
        // 「进一次设置页就刷 30 行堆栈」，把一个正常业务状态伪装成故障。
        val shizukuState = shizukuManager.state.value
        if (shizukuState != ShizukuState.READY) {
            FileLogger.d(TAG, "Shizuku 未就绪（$shizukuState），跳过指令: $line")
            return null
        }
        val timeoutSec = (timeoutMs / 1000L).coerceAtLeast(1L)
        val cmd = "printf '%s\\n' '$line' | timeout $timeoutSec toybox nc 127.0.0.1 $PORT"
        return try {
            val result = shizukuManager.runCommand(cmd, timeoutMs + 3_000L)
            if (result.exitCode == -1000 || result.exitCode == 124) {
                FileLogger.w(TAG, "指令超时: $line")
                null
            } else {
                result.output
            }
        } catch (e: IllegalStateException) {
            // 上面检过就绪，此处只剩「探测与执行之间就绪被撤销」（服务刚被系统回收）的竞态，
            // 属正常情形，debug 级且不带堆栈。
            FileLogger.d(TAG, "指令未执行（$line）: ${e.message}")
            null
        } catch (e: Exception) {
            FileLogger.w(TAG, "指令发送失败: $line", e)
            null
        }
    }
}
