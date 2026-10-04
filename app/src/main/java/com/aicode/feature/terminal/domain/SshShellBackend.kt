package com.aicode.feature.terminal.domain

import com.aicode.core.util.FileLogger
import com.termux.terminal.SessionBackend
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SshShellBackend"

/**
 * [SessionBackend] backed by an sshj interactive shell channel ([Session.Shell]).
 *
 * Used by [RemoteTerminalSessionManager] so the remote SSH shell drives the Termux
 * [com.termux.terminal.TerminalEmulator] exactly like a local pty would: shell output →
 * emulator, user input → shell stdin, resize → PTY window size change.
 *
 * The [Session.Shell] must already be started (with a PTY allocated) before being wrapped
 * here. [waitForExit] blocks on [Session.Shell.join] and returns 0: sshj 只在
 * `Session.Command` 上提供 `getExitStatus()`，`Session.Shell` 没有，交互式 shell 无退出码可读。
 * 真实退出码由 RemoteTerminalSessionManager 在命令尾部打印退出标记后从屏幕缓冲解析。
 */
class SshShellBackend(
    private val shell: Session.Shell
) : SessionBackend {

    // changeWindowDimensions 走网络 I/O，resize 会被 TerminalView 在主线程触发，需切到后台线程
    private val resizeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SshShellResize") }

    // 用 AtomicBoolean 而非普通 var：finishIfRunning() 与 cleanupResources() 都可能调 close()，
    // 普通赋值的 check-then-act 挡不住并发，会重复 spawn 线程二次 shell.close()。
    private val closed = AtomicBoolean(false)

    override fun getInputStream(): InputStream = shell.inputStream

    override fun getOutputStream(): OutputStream = shell.outputStream

    override fun resize(columns: Int, rows: Int) {
        if (closed.get() || resizeExecutor.isShutdown) return
        resizeExecutor.execute {
            runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
                .onFailure { FileLogger.w(TAG, "PTY resize 失败 ${columns}x${rows}", it) }
        }
    }

    override fun waitForExit(): Int {
        return runCatching {
            shell.join()
            0
        }.getOrElse {
            FileLogger.w(TAG, "shell.join 异常", it)
            0
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resizeExecutor.shutdownNow()
        // shell.close() 走网络 I/O，不能在主线程同步执行
        Thread({
            runCatching { shell.close() }
                .onFailure { FileLogger.w(TAG, "shell.close 异常", it) }
        }, "SshShellClose").start()
    }
}
