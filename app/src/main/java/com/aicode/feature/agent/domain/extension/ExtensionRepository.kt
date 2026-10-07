package com.aicode.feature.agent.domain.extension

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 扩展作用域：全局（跨项目）或项目级（随工作区，可 git 化）。 */
enum class ExtensionScope { GLOBAL, PROJECT }

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
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 扩展根目录（全局/项目）；由消费端经仓库统一读取，不自行拼路径。 */
    fun globalRoot(): File = File(containerInstaller.aicodeDir, "extensions")

    fun projectRoot(projectRoot: String): File = File(projectAicodeRoot.forPath(projectRoot), "extensions")

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

    /** 当前工作区作用域的两组目录：全局扩展在前、项目扩展在后（消费端按序合并即「近者胜」）。 */
    fun globalSkillDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.skills) }

    fun projectSkillDirs(): List<File> =
        scanDir(currentProjectRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.skills) }

    fun globalPromptDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.prompts) }

    fun projectPromptDirs(): List<File> =
        scanDir(currentProjectRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.prompts) }

    fun globalMemoryDirs(): List<File> =
        scanDir(globalRoot(), ExtensionScope.GLOBAL).flatMap { it.resolveDirs(it.manifest.contributes.memory) }

    fun projectMemoryDirs(): List<File> =
        scanDir(currentProjectRoot(), ExtensionScope.PROJECT).flatMap { it.resolveDirs(it.manifest.contributes.memory) }

    private fun currentProjectRoot(): File = projectAicodeRoot.current()

    /** 扩展贡献的 MCP 配置：`(serverName, 配置 JSON, 来源扩展 id)`；格式不合法的 server 逐条跳过。 */
    fun mcpServers(projectRoot: String?): List<Triple<String, JsonObject, String>> {
        val out = ArrayList<Triple<String, JsonObject, String>>()
        for (entry in listExtensions(projectRoot)) {
            val rel = entry.manifest.contributes.mcp ?: continue
            val f = entry.resolveFile(rel) ?: continue
            if (!f.isFile) continue
            runCatching {
                val obj = json.decodeFromString<JsonObject>(f.readText())
                for ((name, cfg) in obj) {
                    if (cfg is JsonObject) out += Triple(name, cfg, entry.manifest.id)
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
        val ID_REGEX = Regex("""^[a-z0-9-]+$""")
    }
}
