package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.domain.model.EndpointSanitizer
import com.aicode.feature.settings.domain.model.UrlCheck
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, FAILED, DISABLED }
}

// reloadMutex 串行化重连，避免设置页连点导致并发注册/反注册竞态。
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "McpManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    /** 连接失败退避：不可达 server 在冷却期内不再重试，避免每轮/每次重载空耗超时。 */
    private val connectBackoff = ConnectBackoff()

    /**
     * MCP 专用 OkHttp 客户端：关闭透明重试（`tools/call` 非幂等，重试可能重复执行副作用），
     * 并给请求设有限读超时（共享客户端 readTimeout=0 为流式 LLM 设计，MCP 一问一答不能无限等）。
     *
     * 连接池刻意设为零空闲上限：MCP server 常在数秒内静默关闭空闲 keep-alive 连接，池中死连接
     * 被复用时表现为 `unexpected end of stream`（EOFException: \n not found: limit=0 content=…，
     * 即读响应状态行时缓冲区为空）；又因不能重试，只能把错误抛给用户。本地端点重建连接仅数毫秒，
     * 放弃复用换取此类失败归零。
     */
    private val mcpHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .retryOnConnectionFailure(false)
            .connectionPool(okhttp3.ConnectionPool(0, 5, java.util.concurrent.TimeUnit.SECONDS))
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .eventListenerFactory(object : okhttp3.EventListener.Factory {
                override fun create(call: okhttp3.Call): okhttp3.EventListener =
                    object : okhttp3.EventListener() {
                        private var newConnection = false

                        override fun connectStart(
                            call: okhttp3.Call,
                            inetSocketAddress: java.net.InetSocketAddress,
                            proxy: java.net.Proxy
                        ) {
                            newConnection = true
                        }

                        // 失败时留下连接来源，使「复用已关闭的 keep-alive」与「新连接也失败」可区分：
                        // 前者是连接池/服务端 idle 超时的特征，后者指向服务端或网络本身。
                        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                            // 用 INFO 而非 DEBUG：release（非 debuggable）构建的默认门槛是 INFO，
                            // DEBUG 会被整体丢弃，诊断日志便形同不存在。
                            FileLogger.i(
                                TAG,
                                "MCP 请求失败 endpoint=${call.request().url.host}:${call.request().url.port} " +
                                    "连接来源=${if (newConnection) "新建" else "复用（疑为已关闭的 keep-alive）"} " +
                                    "异常=${ioe.javaClass.simpleName}"
                            )
                        }
                    }
            })
            .build()
    }

    private val activeClients = mutableMapOf<String, McpClient>()
    private val registeredToolNames = mutableSetOf<String>()

    /** 上次成功重载时的「配置 + 工作区」指纹：相同且已连接时跳过重建，避免无谓 teardown。 */
    @Volatile
    private var lastFingerprint: String? = null

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    fun start() {
        // 跟随当前工作区切换自动重载（首帧立即发射当前值，等价启动即重连；
        // 项目切换时合并配置与 stdio 工具的项目路径都会变化，需要重建连接）。
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内自动重载，使新增/删除/启停即时生效。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到 MCP 配置文件外部变更，自动重载")
                reload()
            }
        }
        // 扩展包贡献的 server 被装/删/改：同样需要重建，否则新 server 不会连上。
        scope.launch {
            configRepository.extensionChanges.collect {
                FileLogger.i(TAG, "检测到扩展包变更，重载 MCP")
                reload()
            }
        }
        // 容器 profile 切换：stdio server 的进程跑在旧容器的 rootfs 上，必须重建才能用新容器；
        // HTTP server 不依赖容器，不受影响。drop(1) 跳过启动首帧（reload 已处理）。
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        // 默认容器变化：远程模式下 stdio server 运行在默认容器上，同样需要重建。
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers()

        // fingerprint 防重建：配置内容与工作区路径都没变、且所有启用的 server 都已连接时，
        // 无须 teardown + 重连（工作区 collectLatest / 外部变更可能重复触发同一个状态）。
        val fingerprint = fingerprintOf(servers)
        if (fingerprint == lastFingerprint && allEnabledConnected(servers)) {
            FileLogger.d(TAG, "配置与工作区未变且已连接，跳过 MCP 重建")
            return@withLock
        }

        FileLogger.i(TAG, "重新加载 MCP 配置，共 ${servers.size} 个 server")

        // 整表重载（工作区/配置变更）视为环境变化：清除退避，给「曾经失败但已修好」的 server 重试机会。
        connectBackoff.clear()
        teardown()
        lastFingerprint = fingerprint

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // 先把所有 server 置为「连接中/禁用」，UI 立即有反馈。
        _statuses.value = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // 并行连接所有启用的 server；各自独立失败。
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg ->
                async { connectOne(cfg) }
            }.awaitAll()
        }

        // 合并禁用项与连接结果，保持原始顺序。
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
    }

    /** 配置内容 + 当前工作区路径的稳定指纹：任一变化才需重建 stdio（带项目路径）连接。 */
    private fun fingerprintOf(servers: List<McpServerConfig>): String {
        val workspacePath = runCatching { workspaceRepository.currentPath() }.getOrNull().orEmpty()
        return buildString {
            append(workspacePath)
            append('\u0000')
            servers.forEach { cfg ->
                append(cfg.name).append('|').append(cfg.enabled).append('|')
                append(cfg.url).append('|').append(cfg.command).append('|')
                append(cfg.args.joinToString(",")).append('|')
                append(cfg.env.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
                append('|').append(cfg.disabledTools.sorted().joinToString(","))
                append('|').append(cfg.headers.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
                append('\n')
            }
        }
    }

    /** 所有启用的 server 是否均已建立连接。 */
    private fun allEnabledConnected(servers: List<McpServerConfig>): Boolean = synchronized(activeClients) {
        servers.filter { it.enabled }.all { activeClients.containsKey(it.name) }
    }

    private suspend fun connectOne(cfg: McpServerConfig): McpServerStatus {
        val t0 = System.currentTimeMillis()
        // 退避冷却：上次连接失败且未过冷却期，本轮直接跳过，避免对不可达 server 反复空耗超时。
        val remaining = connectBackoff.delayUntil(cfg.name, System.currentTimeMillis())
        if (remaining > 0) {
            val secs = (remaining + 999) / 1000
            FileLogger.i(TAG, "[${cfg.name}] 处于退避冷却中，${secs}s 后重试，本轮跳过")
            return McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = "连接失败，冷却中（${secs}s 后重试）")
        }
        var freshTransport: McpTransport? = null
        return try {
            FileLogger.i(TAG, "[${cfg.name}] 开始连接（${if (cfg.isStdio) "stdio" else "HTTP"}）")
            val transport = if (cfg.isStdio) {
                // stdio server 跑在「运行时容器」上：本地模式用当前容器，远程 SSH 模式用默认容器。
                // 容器未就绪不自动初始化，直接失败并引导去终端页完成初始化。
                val runtimeProfile = resolveMcpRuntimeProfile()
                containerEngine.notReadyHintFor(runtimeProfile)?.let {
                    throw IllegalStateException(it)
                }
                StdioTransport(
                    serverName = cfg.name,
                    engine = containerEngine,
                    program = cfg.command!!,
                    programArgs = cfg.args,
                    projectPath = workspaceRepository.currentPath(),
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile
                )
            } else {
                // 明文 HTTP 白名单：复用 EndpointSanitizer（去零宽/全角、拒非 http(s)、拒 userinfo、
                // 公网强制 https、私网放行明文）。
                when (val check = EndpointSanitizer.sanitize(cfg.url.orEmpty())) {
                    is UrlCheck.Ok -> StreamableHttpTransport(
                        endpoint = check.sanitized,
                        client = mcpHttpClient,
                        extraHeaders = cfg.headers
                    )
                    is UrlCheck.Rejected -> throw IllegalStateException(
                        "MCP server 「${cfg.name}」的 URL 不合法：${check.reason}"
                    )
                }
            }
            freshTransport = transport
            val client = McpClient(serverName = cfg.name, transport = transport)
            client.connect()

            val tools = client.tools.map { McpTool(client, it) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(activeClients) {
                activeClients[cfg.name] = client
                enabledTools.forEach { tool ->
                    toolRegistry.register(tool.name, tool)
                    registeredToolNames.add(tool.name)
                }
            }
            FileLogger.i(TAG, "[${cfg.name}] 连接成功，注册 ${enabledTools.size}/${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
            connectBackoff.onSuccess(cfg.name)
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: Exception) {
            // 连接失败时把刚建的 transport 关掉：stdio 已在容器内拉起子进程与读循环协程，
            // 不关会随每次退避重连不断泄漏孤儿进程（node 包下载失败场景很常见）。
            runCatching { freshTransport?.close() }
            val cooldown = connectBackoff.onFailure(cfg.name)
            FileLogger.e(TAG, "[${cfg.name}] 连接失败（${System.currentTimeMillis() - t0}ms），退避 ${cooldown / 1000}s", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: emptyList()
        }
    }

    /**
     * 仅重连单个 server（保存/启用开关/编辑刷新用），其他 server 的连接与已注册工具不受影响。
     * 配置中不存在该 name 时静默返回。
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val cfg = configRepository.getEffectiveServers().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@withLock
        // 用户主动保存/编辑该 server：清除其退避，立即重试（否则改了配置仍被冷却挡住）。
        connectBackoff.clear(name)
        teardownServer(name)
        if (_statuses.value.none { it.name.equals(name, ignoreCase = true) }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name.equals(name, ignoreCase = true)) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(cfg)
    }

    /**
     * 仅重连当前未连接的 server（已连接的跳过），新会话时兜底：manageMcp 新增/删除只改配置不重连，
     * 提示「下次会话生效」；本方法让新会话真正连上未连接的 server，不打断已连接的工具。
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled }
        for (cfg in servers) {
            val connected = synchronized(activeClients) { activeClients.containsKey(cfg.name) }
            if (connected) continue
            reconnectOne(cfg)
        }
    }

    /**
     * 异步兜底重连未连接的 server（不阻塞调用方）。新会话创建不应等待 MCP 就绪：
     * stdio server 首跑可能长时间卡在下载依赖/握手超时，同步等待会让「新建会话」看起来卡死。
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig) {
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(cfg) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** 解析 stdio server 的运行时容器：本地模式用当前 profile，远程 SSH 模式用默认容器。 */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** 当前激活 profile：按 id 从配置列表解析，找不到回退内置 Alpine。 */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 默认容器：设置的 id 且为本地 PRoot 模式；找不到/被删/是远程则回退内置 Alpine。 */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 容器相关配置变化后重建所有 stdio server（HTTP 不依赖容器，不动）。
     * 旧进程钉在旧容器的 rootfs 上，必须 teardown 后才能用新容器拉起；单个 server 失败不影响其它。
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled && it.isStdio }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "容器配置变化，重建 ${servers.size} 个 stdio MCP server")
        // 容器切换属环境变化：清除退避，给新容器下的 stdio server 立即重连机会。
        servers.forEach { connectBackoff.clear(it.name) }
        for (cfg in servers) {
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /** 删除 server 时仅断开其连接并反注册其工具，不影响其他 server。 */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        teardownServer(name)
        _statuses.value = _statuses.value.filterNot { it.name.equals(name, ignoreCase = true) }
    }

    private fun teardownServer(name: String) {
        synchronized(activeClients) {
            // activeClients 以配置里的 name 为 key；调用方传进来的 name 大小写可能不同，
            // 精确匹配会断不掉旧连接、反注册不掉旧工具（与合并/ManageMcpTool 同一约定：一律 ignoreCase）。
            val key = activeClients.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return
            val client = activeClients.remove(key) ?: return
            runCatching { client.close() }
            FileLogger.i(TAG, "[$key] 断开连接，反注册 ${client.tools.size} 个工具")
            client.tools.forEach { tool ->
                toolRegistry.unregister(tool.name)
                registeredToolNames.remove(tool.name)
            }
        }
    }

    private fun teardown() {
        synchronized(activeClients) {
            registeredToolNames.forEach { toolRegistry.unregister(it) }
            registeredToolNames.clear()
            activeClients.values.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
    }
}
