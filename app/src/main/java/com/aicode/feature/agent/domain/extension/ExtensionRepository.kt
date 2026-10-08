package com.aicode.feature.agent.domain.extension

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.canonicalHostPath
import com.aicode.core.watch.isUnderPath
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 扩展作用域：全局（跨项目）或项目级（随工作区，可 git 化）。 */
enum class ExtensionScope { GLOBAL, PROJECT }

/** 扩展贡献的一个 MCP server 条目：名称 + 原始配置 JSON + 来源扩展 id + 该扩展的作用域。 */
data class ExtensionMcpServer(
    val name: String,
    val config: JsonObject,
    val extensionId: String,
    val scope: ExtensionScope
)

/**
 * 一个已解析的扩展：清单 + 根目录 + 作用域 + 解析期错误（非致命，逐条记录供诊断）。
 *
 * @param errors manifest 缺字段/路径越界等问题的描述；非空时该扩展的对应贡献被跳过，
 *   但扩展本身仍出现在 [ExtensionRepository.listExtensions] 里，便于用户看到「装了个坏包」。
 */
data class ExtensionEntry(
    val manifest: ExtensionManifest,
    val root: File,
    val scope: ExtensionScope,
    val errors: List<String> = emptyList()
) {
    /** 贡献目录解析为扩展根内的实际路径；scanDir 已预校验，此处为消费端双保险。 */
    fun resolveDirs(relatives: List<String>): List<File> =
        relatives.mapNotNull { rel -> safeResolve(root, rel) }

    /** 贡献文件解析为实际路径；越界返回 null。 */
    fun resolveFile(rel: String): File? = safeResolve(root, rel)

    private fun safeResolve(root: File, rel: String): File? {
        if (rel.isBlank() || rel.startsWith("/")) return null
        val f = File(root, rel)
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        return if (canonical.path == rootCanonical.path || canonical.path.startsWith(rootCanonical.path + File.separator)) f else null
    }
}

/**
 * 扩展仓库：扫描两级扩展根（全局 `~/.aicode/extensions/`、项目 `<.aicode>/extensions/`），
 * 解析并校验 manifest，聚合各贡献目录供技能/提示词/记忆/MCP 消费端挂接。
 *
 * 设计取舍（对齐既有两级语义）：
 * - 扫描**不做**同名去重——同名 extId 项目级优先的裁决交给消费端的合并次序（与
 *   SkillRepository/MemoryRepository 的「项目级优先」一致），仓库层只提供带作用域的全集；
 * - 单个扩展损坏（manifest 解析失败/路径越界）不拖垮其它扩展，逐条记 errors；
 * - 无 extensions 目录时返回空集，各消费端行为与未引入本机制前逐字节一致。
 */
@Singleton
class ExtensionRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 外部变更监听：手工放置 / 容器内解压扩展包后，数秒内通知 UI 刷新 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 两级扩展根被增删改时广播一次。
     *
     * **实际是全程常驻监听**：虽然 [shareIn] 用的是 `WhileSubscribed`，但 [McpManager] 在
     * Singleton 作用域里长驻 collect（构造时启动、永不取消），订阅者不消失，因此
     * [FileChangeHub] 对本仓的 5 路 watch（含整棵 projects 树的递归监听）在 app 全生命
     * 周期都挂着，并非「只在设置页打开时才有开销」。改动订阅方前先确认其 scope 生命周期。
     *
     * 订阅方据此刷新的是**所有**消费扩展的列表（子代理/技能/记忆/MCP/扩展本身），
     * 因为一个扩展包可同时贡献这几类资源。
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(EXTENSIONS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        // 远程模式下项目级扩展根落在 filesDir/aicode/projects/<key>/extensions，
        // 而 watchWorkspace 在远程模式下退化为空流，故这条路是远程唯一的项目级事件源。
        fileChangeHub.watchAicode(PROJECTS_DIR, recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR/$EXTENSIONS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR")
    ).mapNotNull { batch ->
        // 前缀每批算一次（canonicalFile 有 IO 开销），不逐条重算。
        val global = canonicalHostPath(File(containerInstaller.aicodeDir, EXTENSIONS_DIR).absolutePath)
        val project = canonicalHostPath(File(projectAicodeRoot.current(), EXTENSIONS_DIR).absolutePath)
        if (!batch.changes.any { isExtensionChange(it.hostPath, global, project) }) return@mapNotNull null
        FileLogger.i(TAG, "检测到扩展目录变化，已通知刷新")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** 扩展相关变更：全局/项目扩展根自身及其下的任何文件。 */
    private fun isExtensionChange(path: String, globalPrefix: String, projectPrefix: String): Boolean {
        val p = canonicalHostPath(path)
        return isUnderPath(p, globalPrefix) || isUnderPath(p, projectPrefix)
    }

    /** 扩展根目录（全局/项目）；由消费端经仓库统一读取，不自行拼路径。 */
    fun globalRoot(): File = File(containerInstaller.aicodeDir, EXTENSIONS_DIR)

    fun projectRoot(projectRoot: String): File = File(projectAicodeRoot.forPath(projectRoot), EXTENSIONS_DIR)

    /**
     * 扫描两级扩展根。全局在前、项目在后（消费端合并时后者覆盖前者）。
     * [projectRoot] 为空/空白时只扫全局。
     */
    fun listExtensions(projectRoot: String?): List<ExtensionEntry> {
        val out = ArrayList<ExtensionEntry>()
        out += scanDir(globalRoot(), ExtensionScope.GLOBAL)
        if (!projectRoot.isNullOrBlank()) {
            out += scanDir(projectRoot(projectRoot), ExtensionScope.PROJECT)
        }
        return out
    }

    /** 三类贡献目录的聚合查询：全局扩展在前、项目扩展在后（消费端按序合并即「近者胜」）。 */
    fun skillDirs(projectRoot: String?): List<File> =
        listExtensions(projectRoot).flatMap { it.resolveDirs(it.manifest.contributes.skills) }

    fun promptDirs(projectRoot: String?): List<File> =
        listExtensions(projectRoot).flatMap { it.resolveDirs(it.manifest.contributes.prompts) }

    fun memoryDirs(projectRoot: String?): List<File> =
        listExtensions(projectRoot).flatMap { it.resolveDirs(it.manifest.contributes.memory) }

    fun agentDirs(projectRoot: String?): List<File> =
        listExtensions(projectRoot).flatMap { it.resolveDirs(it.manifest.contributes.agents) }

    /** 当前工作区作用域的两组目录：全局扩展在前、项目扩展在后（消费端按序合并即「近者胜」）。 */
    fun globalSkillDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.skills) }

    fun projectSkillDirs(): List<File> =
        scanDir(currentExtensionsRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.skills) }

    fun globalPromptDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.prompts) }

    fun projectPromptDirs(): List<File> =
        scanDir(currentExtensionsRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.prompts) }

    fun globalMemoryDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.memory) }

    fun projectMemoryDirs(): List<File> =
        scanDir(currentExtensionsRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.memory) }

    fun globalAgentDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.agents) }

    fun projectAgentDirs(): List<File> =
        scanDir(currentExtensionsRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.agents) }

    /** 当前工作区的扩展根（`<.aicode>/extensions`）；与 [projectRoot] 同一落点，供无参调用方用。 */
    private fun currentExtensionsRoot(): File = File(projectAicodeRoot.current(), "extensions")

    /**
     * 扩展贡献的 MCP 配置：`(serverName, 配置 JSON, 来源扩展 id, 作用域)`；格式不合法的 server 逐条跳过。
     */
    fun mcpServers(projectRoot: String?): List<ExtensionMcpServer> {
        val out = ArrayList<ExtensionMcpServer>()
        for (entry in listExtensions(projectRoot)) {
            val rel = entry.manifest.contributes.mcp ?: continue
            val f = entry.resolveFile(rel) ?: continue
            if (!f.isFile) continue
            runCatching {
                val obj = json.decodeFromString<JsonObject>(f.readText())
                for ((name, cfg) in obj) {
                    if (cfg is JsonObject) {
                        out += ExtensionMcpServer(name, cfg, entry.manifest.id, entry.scope)
                    }
                }
            }.onFailure { FileLogger.w(TAG, "扩展 ${entry.manifest.id} 的 mcp.json 解析失败: ${it.message}") }
        }
        return out
    }

    private fun scanDir(root: File, scope: ExtensionScope): List<ExtensionEntry> {
        if (!root.isDirectory) return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { extDir ->
            val manifestFile = File(extDir, "manifest.json")
            if (!manifestFile.isFile) return@mapNotNull null
            val errors = ArrayList<String>()
            val manifest = runCatching { json.decodeFromString<ExtensionManifest>(manifestFile.readText()) }
                .getOrElse {
                    errors += "manifest.json 解析失败: ${it.message}"
                    ExtensionManifest(id = extDir.name)
                }
            if (manifest.id.isBlank() || !manifest.id.matches(ID_REGEX)) {
                errors += "id 非法（应为 [a-z0-9-]）：${manifest.id}，已按目录名修正"
            }
            val effectiveId = manifest.id.takeIf { it.matches(ID_REGEX) } ?: extDir.name
            // 贡献路径越界检测前置：发现即记入 errors 并从清单剔除，
            // 而不是等消费端 safeResolve 静默过滤——「越界被发现却不进错误清单」是无解释的空白。
            var contributionErrors: List<String> = errors
            fun checkPaths(rels: List<String>): List<String> = rels.mapNotNull { rel ->
                // 注意：本类的 safeResolve 返回 Boolean（非 File?），不能写「!= null」——
                // 对非空 Boolean 与 null 比较恒 true，会静默跳过全部越界记录。
                val ok = safeResolve(extDir, rel)
                if (!ok) contributionErrors = contributionErrors + "贡献路径越界被拒绝: $rel"
                if (ok) rel else null
            }
            val checked = manifest.contributes.copy(
                skills = checkPaths(manifest.contributes.skills),
                prompts = checkPaths(manifest.contributes.prompts),
                memory = checkPaths(manifest.contributes.memory),
                agents = checkPaths(manifest.contributes.agents),
                mcp = manifest.contributes.mcp?.takeIf { rel ->
                    val ok = safeResolve(extDir, rel) != null
                    if (!ok) contributionErrors = contributionErrors + "贡献路径越界被拒绝: $rel"
                    ok
                }
            )
            ExtensionEntry(
                manifest.copy(id = effectiveId, contributes = checked),
                extDir,
                scope,
                contributionErrors
            ).also {
                if (contributionErrors.isNotEmpty()) FileLogger.w(TAG, "扩展 ${extDir.name} 存在问题: $contributionErrors")
            }
        }
    }

    /** 相对路径解析后是否仍落在 [root] 内（canonical 比对，防 `..` 与符号链接逃逸）。 */
    private fun safeResolve(root: File, rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/")) return false
        val f = File(root, rel)
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return false
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return canonical.path == rootCanonical.path || canonical.path.startsWith(rootCanonical.path + File.separator)
    }

    private companion object {
        const val TAG = "ExtensionRepo"
        const val EXTENSIONS_DIR = "extensions"
        const val AICODE_DIR = ".aicode"
        const val PROJECTS_DIR = "projects"
        val ID_REGEX = Regex("""^[a-z0-9-]+$""")
    }
}
