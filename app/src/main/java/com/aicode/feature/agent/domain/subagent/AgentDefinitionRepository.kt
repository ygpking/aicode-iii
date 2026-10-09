package com.aicode.feature.agent.domain.subagent

import com.aicode.core.text.NameKey
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与技能、MCP 两级配置一致）。
 * 启停状态由 [AgentDefinitionConfigRepository] 持有：被禁用的定义仍出现在设置页列表里，
 * 但不进主代理的可派发清单、也不能被 [find] 派发出去。
 *
 * 全局定义固定在 App 私有目录（始终本地），项目级定义随工作区（本地宿主目录或远程 SSH 工作区），
 * 读写都经 [FileAccessProvider] 以容器路径完成。
 *
 * 除上述两个目录来源外，还聚合**扩展包贡献的定义**（manifest 的 `agents`）——
 * 扩展目录是宿主 [java.io.File]，不经 [FileAccessProvider]（宿主绝对路径会被路径映射兜底进 rootfs，
 * 扫出空目录），与 [com.aicode.feature.agent.domain.skill.SkillRepository] 的扩展技能同一姿势。
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val localSource: LocalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository,
    private val localFileAccess: LocalFileAccess,
    private val fileAccess: FileAccessProvider,
    private val extensionRepository: com.aicode.feature.agent.domain.extension.ExtensionRepository
) {
    /**
     * 全部定义（含来源作用域与承载方式），未过滤禁用，按名称排序。
     * 同作用域内扩展贡献优先于目录定义（与技能/记忆「扩展 > 内置」一致）。
     * 返回值已应用模型覆盖表（见 [AgentDefinitionConfigRepository.overrides]）。
     */
    fun listAll(): List<AgentDefinitionEntry> =
        applyOverrides(
            mergeEntries(
                localSource.listDefinitions().map { it to AgentDefinitionOrigin.DIRECTORY } +
                    extensionDefinitions(globalAgentDirs()).map { it to AgentDefinitionOrigin.EXTENSION },
                projectSource.listDefinitions().map { it to AgentDefinitionOrigin.DIRECTORY } +
                    extensionDefinitions(projectAgentDirs()).map { it to AgentDefinitionOrigin.EXTENSION }
            )
        )

    /**
     * 应用模型覆盖表：扩展贡献的定义本体在扩展目录里不可改（改了升级即丢），
     * 用户指定的模型存 agents.json，在这里覆盖到内存中的定义摘上，使列表、详情与派发口径一致。
     */
    private fun applyOverrides(entries: List<AgentDefinitionEntry>): List<AgentDefinitionEntry> {
        val overrides = runCatching { configRepository.overrides() }.getOrElse {
            FileLogger.w(TAG, "读取子代理模型覆盖表失败", it)
            return entries
        }
        if (overrides.isEmpty()) return entries
        return entries.map { entry ->
            val override = overrides[NameKey.of(entry.definition.name)] ?: return@map entry
            entry.copy(
                definition = entry.definition.copy(
                    providerId = override.providerId ?: entry.definition.providerId,
                    model = override.model ?: entry.definition.model,
                    reasoningEffort = override.reasoningEffort ?: entry.definition.reasoningEffort
                )
            )
        }
    }

    /** 写入/清除某个子代理的模型覆盖项（扩展定义也能设；三个字段全空即清除）。 */
    fun setModelOverride(
        name: String,
        providerId: String?,
        model: String?,
        reasoningEffort: String?,
        scope: AgentDefinitionScope
    ) = configRepository.setOverride(
        name,
        AgentDefinitionConfigRepository.ModelOverride(
            providerId = providerId?.trim()?.takeIf { it.isNotEmpty() },
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            reasoningEffort = reasoningEffort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ),
        scope
    )

    private fun globalAgentDirs(): List<File> =
        runCatching { extensionRepository.globalAgentDirs() }.getOrElse {
            FileLogger.w(TAG, "读取全局扩展子代理目录失败", it)
            emptyList()
        }

    private fun projectAgentDirs(): List<File> =
        runCatching { extensionRepository.projectAgentDirs() }.getOrElse {
            FileLogger.w(TAG, "读取项目扩展子代理目录失败", it)
            emptyList()
        }

    /**
     * 扫描扩展贡献目录的**顶层** `*.md`（与 [AgentDefinitionDirectoryScanner] 同一口径）。
     * 单个坏定义只跳过自己；目录不存在/不可读返回空表，不向上抛——扫盘失败会同时打掉
     * 子代理列表与可派发清单。宿主 `java.io.File` 直读，不经 FileAccessProvider。
     */
    internal fun extensionDefinitions(dirs: List<File>): List<AgentDefinition> =
        dirs.flatMap { dir ->
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                    ?.sortedBy { it.name.lowercase() }
                    ?.mapNotNull { file ->
                        runCatching {
                            AgentDefinitionParser.parseText(
                                file.readText(),
                                fallbackName = file.name.substringBeforeLast('.'),
                                filePath = file.path
                            )
                        }.onFailure { FileLogger.w(TAG, "解析扩展子代理定义失败，已跳过: ${file.name}", it) }
                            .getOrNull()
                    }
                    .orEmpty()
            }.getOrElse {
                FileLogger.w(TAG, "扫描扩展子代理目录失败: $dir", it)
                emptyList()
            }
        }

    /** 已启用的定义（注入主代理的可派发清单用）。 */
    fun listEnabled(): List<AgentDefinitionEntry> {
        val disabled = configRepository.disabledNames()
        return listAll().filterNot { NameKey.of(it.definition.name) in disabled }
    }

    /** 按名称查找可派发的定义（忽略大小写）；不存在或已被禁用时返回 null。 */
    fun find(name: String): AgentDefinition? =
        listEnabled().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /**
     * 按名称查找定义，包含已禁用的。已经派出去的子会话要用它还原自己的提示词与工具集——
     * 禁用只该拦住新的派发，不该让在跑的子代理中途换一套配置。
     */
    fun findIncludingDisabled(name: String): AgentDefinition? =
        listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** 该子代理是否在任一作用域中被禁用。 */
    fun isDisabled(name: String): Boolean = NameKey.of(name) in configRepository.disabledNames()

    /** 当前生效的禁用子代理名集合（全局 + 项目并集，小写）。供批量列表一次读盘、代替逐行 isDisabled。 */
    fun disabledNames(): Set<String> = configRepository.disabledNames()

    /** 在指定作用域启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) =
        configRepository.setDisabled(name, disabled, scope)

    /**
     * 写入定义文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     */
    fun save(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ): AgentSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return AgentSaveError.INVALID_NAME
        if (form.prompt.isBlank()) return AgentSaveError.EMPTY_PROMPT

        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        val all = listAll()
        // 扩展包贡献的定义只读：设置页已隐藏编辑入口，这里对绕过 UI 的调用也一并拒绝，
        // 否则会按扩展目录的宿主路径拼出容器路径去写，静默落到 rootfs 影子文件。
        if (originalName != null) {
            val editing = all.firstOrNull {
                it.scope == scope && it.definition.name.equals(originalName, ignoreCase = true)
            }
            if (editing?.origin == AgentDefinitionOrigin.EXTENSION) return AgentSaveError.READ_ONLY_EXTENSION
        }
        if (!overwritingSelf) {
            val taken = all.any {
                it.scope == scope && it.definition.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            all.firstOrNull {
                it.scope == scope && it.definition.name.equals(old, ignoreCase = true)
            }?.definition?.filePath
        }

        val provider = providerFor(scope)
        val root = agentsRoot(scope)
        val text = AgentDefinitionParser.serialize(
            name = name,
            description = form.description,
            providerId = form.providerId,
            model = form.model,
            reasoningEffort = form.reasoningEffort,
            mode = form.mode,
            allowedTools = form.allowedTools,
            disallowedTools = form.disallowedTools,
            inject = form.inject,
            prompt = form.prompt,
            interactionModes = form.interactionModes
        )

        return try {
            // 名字未改时写回原文件，不能按 name 重拼文件名：内置 Explore 的文件叫 explore.md
            // 而 frontmatter 里写的是 Explore，重拼会在大小写敏感的文件系统上多出一份 Explore.md。
            val target = existingFile?.takeIf { overwritingSelf && provider.isFile(it) }
                ?: "${root.trimEnd('/')}/$name.md"
            provider.writeFile(target, text, overwrite = true)
            // 改名后清掉旧文件，否则会多出一个同内容的旧名子代理
            if (!overwritingSelf && existingFile != null && existingFile != target && provider.isFile(existingFile)) {
                runCatching { provider.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "重命名后删除旧定义失败: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存子代理定义失败: $name", e)
            AgentSaveError.IO_FAILED
        }
    }

    /** 删除指定作用域的定义文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.definition.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        // 扩展包贡献的定义只读，删它得卸载整个扩展（与 save 同一道拦截）。
        if (entry.origin == AgentDefinitionOrigin.EXTENSION) return false
        val filePath = entry.definition.filePath ?: return false
        val provider = providerFor(scope)
        if (!provider.isFile(filePath)) return false
        return runCatching { provider.delete(filePath); true }.getOrDefault(false)
    }

    /** 指定作用域的定义目录（容器路径）。 */
    fun agentsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) localSource.agentsRoot else projectSource.agentsRoot

    /** 全局定义固定在本地私有目录，项目级定义跟随工作区（可能是远程）。 */
    private fun providerFor(scope: AgentDefinitionScope): FileAccessProvider =
        if (scope == AgentDefinitionScope.GLOBAL) localFileAccess else fileAccess

    companion object {
        private const val TAG = "AgentDefinitionRepository"

        /** 名称同时用作文件名，禁掉路径分隔符与保留字符；长度上限防止极端文件名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。（目录来源专用；扩展来源走 [mergeEntries]。） */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> =
            mergeEntries(
                global.map { it to AgentDefinitionOrigin.DIRECTORY },
                project.map { it to AgentDefinitionOrigin.DIRECTORY }
            )

        /**
         * 合并两级来源（带承载方式）：同名项目级覆盖全局，同作用域内后者覆盖前者，按名称排序。
         * 调用方把扩展贡献排在目录定义之后，即可实现「同作用域内扩展 > 目录」。
         */
        internal fun mergeEntries(
            global: List<Pair<AgentDefinition, AgentDefinitionOrigin>>,
            project: List<Pair<AgentDefinition, AgentDefinitionOrigin>>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { (def, origin) ->
                byName[NameKey.of(def.name)] = AgentDefinitionEntry(def, AgentDefinitionScope.GLOBAL, origin)
            }
            project.forEach { (def, origin) ->
                byName[NameKey.of(def.name)] = AgentDefinitionEntry(def, AgentDefinitionScope.PROJECT, origin)
            }
            return byName.values.sortedBy { NameKey.of(it.definition.name) }
        }
    }
}
