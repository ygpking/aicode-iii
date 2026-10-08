package com.aicode.feature.agent.domain.mcp

import com.aicode.core.text.NameKey
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.extension.ExtensionRepository
import com.aicode.feature.agent.domain.extension.ExtensionScope
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** MCP server 的配置作用域：全局（跨项目共享）或项目级（仅当前工作区生效）。 */
enum class McpScope { GLOBAL, PROJECT }

/**
 * 条目的承载方式：磁盘上的 mcp.json，或扩展包贡献的 mcp.json。
 * 扩展来源在设置页只读（改它得改扩展目录），且随扩展包增删。
 */
enum class McpOrigin { DIRECTORY, EXTENSION }

/** 一个生效的 MCP 条目：配置 + 来源作用域与承载方式，供 UI 标注「全局/项目」与「来自扩展」。 */
data class McpServerEntry(
    val server: McpServerConfig,
    val scope: McpScope,
    val origin: McpOrigin = McpOrigin.DIRECTORY
)

/**
 * MCP 配置持久化，支持全局 + 项目级两级：
 * - 全局：`filesDir/aicode/mcp.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/mcp.json`（随工作区走，可 git 追踪）。
 *
 * 生效配置 = 全局 + 项目合并，**项目级优先**，同名时项目项覆盖全局项。
 * 并发模式：Mutex 保护文件 IO + MutableStateFlow 缓存，项目级按工作区路径各自缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class McpConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val extensionRepository: ExtensionRepository
) {
    private companion object {
        const val TAG = "McpConfigRepository"
        const val CONFIG_FILE = "mcp.json"
        const val DEFAULT_JSON = """{"mcpServers":{}}"""
        /** 项目级配置目录名，容器内即 `~/workspace/.aicode`。 */
        const val AICODE_DIR_NAME = ".aicode"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val PRETTY_JSON = Json { prettyPrint = true }
    }

    /** 全局配置文件：`filesDir/aicode/mcp.json`。 */
    private val globalFile: File
        get() = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/mcp.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), CONFIG_FILE)

    private val globalState = MutableStateFlow<String?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑配置文件后，刷新缓存并广播给 McpManager 重连 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 配置被外部修改（内容与缓存不一致）时广播一次。订阅驱动：只在有订阅者（McpManager）期间
     * 才由 [FileChangeHub] 监听两个配置文件所在目录，无人订阅时零开销。
     */
    val externalChanges: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
    ).mapNotNull { batch ->
        val globalPath = globalFile.absolutePath
        val projectPath = projectFileForPath(workspaceRepository.currentPath()).absolutePath
        val touched = batch.changes.any { it.hostPath == globalPath || it.hostPath == projectPath }
        // 同目录下其它文件的变更（如 skills.json）不触发重连；内容没真变（如 touch）也不触发。
        if (!touched) null else if (reloadFromDisk()) Unit else null
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * 外部变更后按磁盘现状刷新缓存，内容确实变化时返回 true。项目级只处理当前工作区；
     * 其缓存尚未加载时不广播——首次加载由 [ensureProjectLoaded] 完成，工作区切换不算外部变更。
     */
    private suspend fun reloadFromDisk(): Boolean {
        var changed = false
        val globalContent = load(globalFile)
        if (globalContent != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = globalContent
            changed = true
            FileLogger.i(TAG, "检测到全局 MCP 配置变化，已刷新")
        }
        val path = workspaceRepository.currentPath()
        val state = getProjectState(path)
        if (state.value != null) {
            val content = load(projectFileForPath(path))
            if (content != state.value) {
                state.value = content
                changed = true
                FileLogger.i(TAG, "检测到项目 MCP 配置变化，已刷新")
            }
        }
        return changed
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<String?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = load(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = load(projectFileForPath(workspacePath))
        }
    }

    private suspend fun load(file: File): String = withContext(Dispatchers.IO) {
        // 只读加载：文件不存在时返回默认配置，不主动创建目录/文件，
        // 避免在 projectsRoot 下误建 .aicode 目录被工作区扫描器当成工作区。
        if (file.isFile) {
            return@withContext runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败，回退默认配置: ${it.message}")
                DEFAULT_JSON
            }
        }
        DEFAULT_JSON
    }

    private fun writeFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** 全局 MCP 配置流。 */
    val globalServersFlow: Flow<List<McpServerConfig>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull().map { parse(it) })
    }

    /**
     * 当前项目生效的 MCP 条目流（全局 + 项目合并，项目优先覆盖同名），
     * 跟随当前工作区切换自动重载对应项目配置。
     */
    val effectiveEntriesFlow: Flow<List<McpServerEntry>> =
        workspaceRepository.current.flatMapLatest {
            val path = workspaceRepository.currentPath()
            ensureGlobalLoaded()
            ensureProjectLoaded(path)
            // 第三路是扩展包变更触发：扩展贡献的 server 不在两个 state 里，
            // 不并入就得等下次配置文件变动才会出现在列表上。onStart 补首帧，保持原有“立即发一次”语义。
            combine(
                globalState,
                getProjectState(path),
                extensionRepository.changes.onStart { emit(Unit) }
            ) { g, p, _ ->
                val (globalExt, projectExt) = extensionServers(path)
                merge(
                    parse(g ?: DEFAULT_JSON),
                    parse(p ?: DEFAULT_JSON),
                    globalExt,
                    projectExt
                )
            }
        }

    /**
     * 扩展包贡献的 MCP 配置发生变化（装/删/改扩展包）时广播一次，供 [McpManager] 重建连接。
     * 与 [externalChanges] 分开：后者语义是「磁盘 mcp.json 内容真变了」，扩展变更不属于它。
     */
    val extensionChanges: Flow<Unit> = extensionRepository.changes

    suspend fun getGlobalServers(): List<McpServerConfig> {
        ensureGlobalLoaded()
        return parse(globalState.value ?: DEFAULT_JSON)
    }

    suspend fun getProjectServers(): List<McpServerConfig> {
        val path = workspaceRepository.currentPath()
        ensureProjectLoaded(path)
        return parse(getProjectState(path).value ?: DEFAULT_JSON)
    }

    suspend fun setGlobalServers(servers: List<McpServerConfig>) {
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(globalFile, json) }
            globalState.value = json
        }
    }

    suspend fun setProjectServers(servers: List<McpServerConfig>) {
        val path = workspaceRepository.currentPath()
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(projectFileForPath(path), json) }
            getProjectState(path).value = json
        }
    }

    // ── 名称主键的归一 ──
    // server 名是逻辑主键（用于合并、去重、匹配、重连）。写入侧与读取侧必须用同一个变换，
    // 否则会出现「写进去了但按同样的名字删不掉/查不到」的静默失效（见 e5be8bc：禁用名单
    // 删除分支未归一导致技能永远无法重新启用）。归一定义收敛到 [NameKey]（与 skill/agent 同一份）。

    /** 名称主键的归一键。 */
    private fun keyOf(name: String): String = NameKey.of(name)

    private fun ExtensionScope.toMcpScope(): McpScope =
        if (this == ExtensionScope.GLOBAL) McpScope.GLOBAL else McpScope.PROJECT

    private suspend fun readScoped(scope: McpScope): List<McpServerConfig> =
        if (scope == McpScope.GLOBAL) getGlobalServers() else getProjectServers()

    private suspend fun writeScoped(scope: McpScope, servers: List<McpServerConfig>) {
        if (scope == McpScope.GLOBAL) setGlobalServers(servers) else setProjectServers(servers)
    }

    /**
     * 增/改一个 server（含重命名与作用域迁移），归一在此一次性完成。
     *
     * @param originalName 编辑前的名字；新增传 null。**仅大小写/空白变化也视为同一实体**，
     *   否则目标作用域会同时留下 `Foo` 与新写入的 `foo`，落盘成 UI 不可见的幽灵重复项。
     * @param originalScope 编辑前的作用域；与 [scope] 不同时先旧作用域移除，避免残留项在
     *   [merge]（项目优先）中继续覆盖新作用域的配置。
     */
    suspend fun upsertServer(
        config: McpServerConfig,
        scope: McpScope,
        originalName: String? = null,
        originalScope: McpScope? = null
    ): Boolean {
        // 扩展来源只读：既不能改它，也不能用同名条目去遮它——
        // 扩展在 merge 中胜出，写进去的目录条目会既不可见也不生效（静默失败）。
        if (originalName != null && originalScope != null && isExtensionServer(originalName, originalScope)) {
            return false
        }
        if (isExtensionServer(config.name, scope)) return false
        if (originalName != null && originalScope != null && originalScope != scope) {
            removeServer(originalName, originalScope)
        }
        // 需剔除【原名】与【新名】两个 key：
        // - 新名：编辑同一实体时替换其旧条目；
        // - 原名：**重命名**（如 old→new）时移除残留的旧条目，否则新旧两条并存。
        // 原名与新名相同（含仅大小写/空白变化）时二者归一键相等，不会重复剔除。
        val keysToDrop = setOfNotNull(originalName?.let(::keyOf), keyOf(config.name))
        val base = readScoped(scope).filterNot { keyOf(it.name) in keysToDrop }
        writeScoped(scope, base + config)
        return true
    }

    /** 该名字是否是扩展包贡献的 server（只读，改它得改扩展目录）。 */
    /**
     * 该名字在同一作用域是否已被扩展包贡献（此类 server 只读，且会遮蔽同名目录条目）。
     *
     * 写成 internal 供 [com.aicode.feature.agent.domain.tool.mcp.ManageMcpTool] 复用：
     * 工具新增同名 server 时会落进目录配置被扩展遮住，必须与 UI 同一判定。
     */
    internal fun isExtensionServer(name: String, scope: McpScope): Boolean {
        val path = runCatching { workspaceRepository.currentPath() }.getOrNull()
        return extensionRepository.mcpServers(path).any {
            keyOf(it.name) == keyOf(name) && it.scope.toMcpScope() == scope
        }
    }

    /** 按名称（忽略大小写与首尾空白）从指定作用域删除；返回是否真的删掉了。 */
    suspend fun removeServer(name: String, scope: McpScope): Boolean {
        // 扩展来源只读：它们在 mcp.json 里本就不存在，写路径只会落在目录配置上而改不动它。
        if (isExtensionServer(name, scope)) return false
        val base = readScoped(scope)
        val kept = base.filterNot { keyOf(it.name) == keyOf(name) }
        if (kept.size == base.size) return false
        writeScoped(scope, kept)
        return true
    }

    /** 按名称（忽略大小写与首尾空白）设置启用位；返回是否命中。 */
    suspend fun setServerEnabled(name: String, enabled: Boolean, scope: McpScope): Boolean {
        if (isExtensionServer(name, scope)) return false
        val target = keyOf(name)
        var hit = false
        val updated = readScoped(scope).map {
            if (keyOf(it.name) == target) {
                hit = true
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
        if (hit) writeScoped(scope, updated)
        return hit
    }

    /** 当前项目生效的合并配置（项目优先覆盖同名），供 [McpManager] 连接使用。 */
    suspend fun getEffectiveServers(): List<McpServerConfig> =
        getEffectiveEntries().map { it.server }

    /** 当前项目生效的合并条目（含来源作用域），供设置页列表标注使用。 */
    suspend fun getEffectiveEntries(): List<McpServerEntry> {
        val path = workspaceRepository.currentPath()
        ensureGlobalLoaded()
        ensureProjectLoaded(path)
        val (globalExt, projectExt) = extensionServers(path)
        return merge(
            parse(globalState.value ?: DEFAULT_JSON),
            parse(getProjectState(path).value ?: DEFAULT_JSON),
            globalExt,
            projectExt
        )
    }

    fun serialize(servers: List<McpServerConfig>): String {
        val serversObj = buildJsonObject {
            servers.forEach { server ->
                putJsonObject(server.name) {
                    if (server.isStdio) {
                        put("command", server.command)
                        if (server.args.isNotEmpty()) {
                            putJsonArray("args") { server.args.forEach { add(it) } }
                        }
                        if (server.env.isNotEmpty()) {
                            putJsonObject("env") {
                                server.env.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    } else {
                        put("url", server.url ?: "")
                        if (server.headers.isNotEmpty()) {
                            putJsonObject("headers") {
                                server.headers.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    }
                    put("enabled", server.enabled)
                    if (server.disabledTools.isNotEmpty()) {
                        putJsonArray("disabledTools") { server.disabledTools.forEach { add(it) } }
                    }
                }
            }
        }
        val root = buildJsonObject { put("mcpServers", serversObj) }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    fun parse(raw: String): List<McpServerConfig> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(TAG, "MCP 配置 JSON 解析失败: ${it.message}")
            return emptyList()
        }
        val servers = (root["mcpServers"] as? JsonObject) ?: return emptyList()

        return servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseOne(name, obj)
        }
    }

    /** 解析单个 server 配置（`{name: {...}}` 里的一项）；既无 url 也无 command 时返回 null。 */
    internal fun parseOne(name: String, obj: JsonObject): McpServerConfig? {
        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

        val command = (obj["command"] as? JsonPrimitive)?.contentOrNull
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull

        val disabledTools = (obj["disabledTools"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.toSet() ?: emptySet()

        return when {
            !command.isNullOrBlank() -> {
                val args = (obj["args"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull
                } ?: emptyList()
                val env = (obj["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap() ?: emptyMap()
                McpServerConfig(
                    name = name,
                    command = command,
                    args = args,
                    env = env,
                    enabled = enabled,
                    disabledTools = disabledTools
                )
            }
            !url.isNullOrBlank() -> {
                val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap() ?: emptyMap()
                McpServerConfig(
                    name = name,
                    url = url,
                    headers = headers,
                    enabled = enabled,
                    disabledTools = disabledTools
                )
            }
            else -> {
                // 既无 url 也无 command，无法识别，跳过。
                FileLogger.i(TAG, "跳过无法识别的 MCP server（缺 url/command）: $name")
                null
            }
        }
    }

    /** 扩展包贡献的 MCP server，按作用域分两组；单个配置非法只跳过自己。 */
    private fun extensionServers(
        projectRoot: String?
    ): Pair<List<McpServerConfig>, List<McpServerConfig>> {
        val global = ArrayList<McpServerConfig>()
        val project = ArrayList<McpServerConfig>()
        for (item in extensionRepository.mcpServers(projectRoot)) {
            runCatching { parseOne(item.name, item.config) }
                .onFailure { FileLogger.w(TAG, "扩展 ${item.extensionId} 的 MCP server 解析失败: ${it.message}") }
                .getOrNull()
                ?.let { if (item.scope == ExtensionScope.GLOBAL) global += it else project += it }
        }
        return global to project
    }

    /** 合并全局与项目配置：全局按序在前，项目项覆盖同名，顺序 = 全局序 + 项目新增项。 */
    private fun merge(
        global: List<McpServerConfig>,
        project: List<McpServerConfig>,
        globalExt: List<McpServerConfig> = emptyList(),
        projectExt: List<McpServerConfig> = emptyList()
    ): List<McpServerEntry> {
        val byName = LinkedHashMap<String, McpServerEntry>()
        // 小写作 key：与 skill/agent 两级合并一致。否则 `Foo`/`foo` 同时生效，且全局项不被项目项覆盖。
        // 次序即优先级：先作用域（项目 > 全局），同作用域内扩展 > 目录。
        global.forEach { byName[keyOf(it.name)] = McpServerEntry(it, McpScope.GLOBAL, McpOrigin.DIRECTORY) }
        globalExt.forEach { byName[keyOf(it.name)] = McpServerEntry(it, McpScope.GLOBAL, McpOrigin.EXTENSION) }
        project.forEach { byName[keyOf(it.name)] = McpServerEntry(it, McpScope.PROJECT, McpOrigin.DIRECTORY) }
        projectExt.forEach { byName[keyOf(it.name)] = McpServerEntry(it, McpScope.PROJECT, McpOrigin.EXTENSION) }
        return byName.values.toList()
    }
}