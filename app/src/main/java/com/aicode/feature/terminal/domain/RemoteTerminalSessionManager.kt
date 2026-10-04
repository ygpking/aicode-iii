package com.aicode.feature.terminal.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * 命令尾部退出标记的前缀（与本地 [TerminalSessionManager] 同口径）。
 *
 * 远程必须靠标记而非 `exitStatus`：sshj 只给 [net.schmizz.sshj.connection.channel.direct.Session.Command]
 * 提供 `getExitStatus()`，而终端标签用的是 `Session.Shell`，该接口无退出码可读。
 */
private const val EXIT_MARKER_PREFIX = "[command exited: "
private const val EXIT_MARKER_GRACE_MS = 1_500L
private const val EXIT_MARKER_POLL_MS = 1_000L

/** 已完成的后台标签保留上限（与本地 [TerminalSessionManager] 同值）：超出则自动关最旧的。 */
private const val MAX_FINISHED_BACKGROUND_TABS = 5

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    /** 退出标记兜底监控的后台作用域；生命周期跟随进程（Singleton）。 */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 仅当当前模式是 REMOTE_SSH 且已连接时才可使用。 */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(command, isBackground = true, notify = notify, title = title, sourceSessionId = sourceSessionId)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?
    ): String {
        val id = nextId()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = withContext(Dispatchers.IO) {
            try {
                connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
            } catch (e: Exception) {
                FileLogger.e(TAG, "创建远程 SSH shell 会话失败（$id, isBackground=$isBackground）", e)
                throw e
            }
        }
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // shell 登录后默认在 home，先 cd 到当前工作区，与命令执行链路（RemoteSshEngine.buildCdCommand）保持一致：
        // 优先 ~/workspace 符号链接，失败回退到真实工作区路径。
        val wsPath = workspaceRepository.currentPath()
        if (wsPath.isNotBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd '${wsPath.trimEnd('/')}' 2>/dev/null\n")
        }
        if (command != null) {
            // notify=true 时命令尾部打印退出标记并以真实退出码退出。
            // 不能调 `exec /bin/sh`（保活）也不能靠 exitStatus：前者让 shell 不退出、拿不到码，
            // 后者接口不存在。退出标记 + [monitorBackgroundExit] 轮询是远程唯一可行的路径。
            val init = if (notify) {
                "$command; ec=\$?; echo \"$EXIT_MARKER_PREFIX\$ec]\"; exit \$ec"
            } else {
                command + "; exec /bin/sh"
            }
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        if (command != null && notify) monitorBackgroundExit(id)
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()
        FileLogger.i(TAG, "关闭远程终端标签 $id")
        return true
    }

    /**
     * 回收超额的历史标签：已完成的后台标签超过上限时，从最旧的开始自动关闭。
     * 与本地 [TerminalSessionManager.trimFinishedTabs] 同口径，避免远程模式下标签无限制堆积。
     */
    private fun trimFinishedTabs() {
        val finishedBackground = _tabs.value.filter { it.isBackground && it.runState is RunState.Finished }
        val excess = finishedBackground.size - MAX_FINISHED_BACKGROUND_TABS
        if (excess <= 0) return
        finishedBackground.take(excess).forEach { closeTab(it.id) }
        FileLogger.i(TAG, "已自动回收 $excess 个历史已完成后台标签（保留上限 $MAX_FINISHED_BACKGROUND_TABS）")
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            tab.session.write(bytes, 0, bytes.size)
        }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /** 从屏幕缓冲尾部解析 `[command exited: N]`；无标记返回 null（交互标签与旧行为）。 */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    /**
     * 完成后兑底：远端 PTY 不总是干净退出（如掉线、shell 未发 EOF），此时不会触发
     * [TerminalSessionClient.onSessionFinished]，标签会永远停在 Running。观察到退出标记后
     * 再等 [EXIT_MARKER_GRACE_MS]，若回调仍未触发则强制收尾——退出码取真实值而非 0。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        current.runState = RunState.Finished(exitCode)
                        bumpRevision()
                        FileLogger.i(TAG, "兑底：远程标签 $tabId 检测到退出标记，强制收尾 exit=$exitCode")
                        if (current.notifyOnExit && !current.finishedNotified) {
                            current.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                                )
                            )
                        }
                        trimFinishedTabs()
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                // 取屏幕缓冲里的退出标记：远程 `Session.Shell` 无退出码接口，
                // backend.waitForExit() 恒返 0（见 [SshShellBackend]），标记是唯一的真实来源。
                // 交互标签（notify=false）无标记 → 退化为 0，与改前行为一致。
                val exitCode = extractExitCode(getTabOutput(target.id) ?: "") ?: 0
                target.runState = RunState.Finished(exitCode)
                bumpRevision()
                FileLogger.i(TAG, "远程终端标签 ${target.id} 会话结束 exit=$exitCode（后台=${target.isBackground}）")
                if (target.notifyOnExit && !target.finishedNotified) {
                    target.finishedNotified = true
                    _tabFinishedEvents.tryEmit(
                        TabFinishedEvent(
                            target.id, target.title, target.command, exitCode, target.sourceSessionId,
                            tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                        )
                    )
                }
                trimFinishedTabs()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
