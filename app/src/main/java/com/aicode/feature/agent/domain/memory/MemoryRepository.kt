package com.aicode.feature.agent.domain.memory

import com.aicode.core.text.NameKey
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val extensionRepository: com.aicode.feature.agent.domain.extension.ExtensionRepository
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        // 1b. 全局扩展贡献的记忆（声明式资源包，只读源）
        allMemories.addAll(
            ExtensionMemorySource(
                extensionRepository.globalMemoryDirs(), MemoryScope.GLOBAL
            ).listMemories()
        )
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
            allMemories.addAll(
                ExtensionMemorySource(
                    extensionRepository.projectMemoryDirs(), MemoryScope.PROJECT
                ).listMemories()
            )
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）
        return allMemories
            .groupBy { NameKey.of(it.name) }
            .map { it.value.last() }
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
            // 项目扩展贡献的记忆（只读源）
            ExtensionMemorySource(extensionRepository.projectMemoryDirs(), MemoryScope.PROJECT)
                .loadContent(name)?.let { return it }
        }
        // 回退到全局读取
        globalMemorySource.loadContent(name)?.let { return it }
        // 全局扩展贡献的记忆
        return ExtensionMemorySource(extensionRepository.globalMemoryDirs(), MemoryScope.GLOBAL)
            .loadContent(name)
    }

    /**
     * 返回**指定作用域**内 [name] 对应的记忆文件（可能尚不存在；PROJECT 无工作区时返回 null）。
     *
     * 与 [listMemories] 的区别：后者跨作用域合并且项目级优先，因此在「另一作用域存在同名记忆」
     * 时会定位到**另一个文件**。需要按作用域定位原始文件时必须用本方法（实测：apply 的覆盖前
     * 备份曾用 [listMemories] 定位，scope=global 时备份到了项目级同名文件，被覆盖的全局原文丢了）。
     */
    fun memoryFile(name: String, scope: MemoryScope, projectRoot: String?): File? =
        when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.memoryFile(name)
            MemoryScope.PROJECT -> projectRoot?.takeIf { it.isNotBlank() }?.let { projectSource(it).memoryFile(name) }
        }

    fun saveMemory(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        triggers: List<String>? = null,
        kind: String? = null,
    ): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content, triggers, kind)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content, triggers, kind)
            }
        }
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    /**
     * 召回命中回写：按「项目级优先」的定位口径（与 [listMemories] 的合并口径一致）落到正确的源。
     * 尽力而为：未找到（未选中工作区且全局也无）时静默忽略。
     */
    fun touchMemory(name: String, projectRoot: String?): Boolean {
        val key = NameKey.of(name)
        if (!projectRoot.isNullOrBlank()) {
            val project = projectSource(projectRoot)
            if (project.listMemories().any { NameKey.of(it.name) == key }) {
                project.touchMemory(name)
                return true
            }
            // 项目扩展贡献的记忆（只读源但 touch 回写扩展文件是安全的）
            val projectExt = ExtensionMemorySource(extensionRepository.projectMemoryDirs(), MemoryScope.PROJECT)
            if (projectExt.listMemories().any { NameKey.of(it.name) == key }) {
                projectExt.touchMemory(name)
                return true
            }
        }
        if (globalMemorySource.listMemories().any { NameKey.of(it.name) == key }) {
            globalMemorySource.touchMemory(name)
            return true
        }
        val globalExt = ExtensionMemorySource(extensionRepository.globalMemoryDirs(), MemoryScope.GLOBAL)
        if (globalExt.listMemories().any { NameKey.of(it.name) == key }) {
            globalExt.touchMemory(name)
            return true
        }
        return false
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }

    /**
     * 评估记忆陈旧度（只读，不删任何东西）。
     *
     * @param staleDays 视为陈旧的天数阈值；`<= 0` 关闭评估。
     */
    fun assessStaleness(
        projectRoot: String?,
        staleDays: Long = MemoryRetention.DEFAULT_STALE_DAYS,
        nowMs: Long = System.currentTimeMillis()
    ): MemoryRetention.Report =
        MemoryRetention.assess(listMemories(projectRoot), nowMs, staleDays)

    /**
     * 只读评估存量记忆的整理建议（近重复 / 正文过大 / 缺 triggers）。**不修改任何文件**，
     * 与 [assessStaleness] 同一策略：诊断由模型/用户看过之后再决定要不要动手。
     */
    internal fun curate(projectRoot: String?): List<MemoryCuration.Finding> =
        MemoryCuration.evaluate(listMemories(projectRoot))

    /**
     * 显式清理陈旧记忆（**破坏性操作**，仅在调用方明确给出 [staleDays] 时执行）。
     *
     * 这是「只读扫描 + 显式清理」策略的落地：不做后台自动删除，因为 AiCode 的记忆全是具名
     * 常青记忆，自动按时间删会误删「长期有效但久未更新」的约定，而记忆不可再生。
     *
     * 安全约束：
     * - `pinned: true` 的记忆永不删（对应上游 `exempt_files` 豁免名单）。
     * - `mtime` 读不到（返回 0）的记忆不删——信息缺失时不赌。
     * - [dryRun] 为 true（默认）时只返回将被删除的名单，不实际删除。
     *
     * @return 被删除（或将被删除）的记忆名，以及跳过数与失败数。
     */
    fun pruneStaleMemories(
        projectRoot: String?,
        staleDays: Long,
        dryRun: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): PruneResult {
        // staleDays <= 0 视为未启用：绝不因参数缺省或写错而全删。
        if (staleDays <= 0) {
            return PruneResult(emptyList(), skipped = 0, failed = 0, enabled = false)
        }
        val memories = listMemories(projectRoot)
        val report = MemoryRetention.assess(memories, nowMs, staleDays)
        val staleNames = report.ages.filter { it.stale }.map { it.name }
        if (dryRun) {
            return PruneResult(staleNames, skipped = report.pinnedExemptCount, failed = 0, enabled = true)
        }

        var failed = 0
        val deleted = mutableListOf<String>()
        staleNames.forEach { name ->
            val target = memories.firstOrNull { NameKey.of(it.name) == NameKey.of(name) } ?: return@forEach
            val ok = deleteMemory(target.name, target.scope, projectRoot)
            if (ok) deleted += target.name else failed++
        }
        return PruneResult(deleted, skipped = report.pinnedExemptCount, failed = failed, enabled = true)
    }

    /**
     * [pruneStaleMemories] 的结果。
     *
     * @param names 已删除（dryRun 时为「将被删除」）的记忆名。
     * @param skipped 因 pinned 而豁免的记忆数。
     * @param failed 删除失败的条数（如文件已被并发删除外的 IO 错误）。
     * @param enabled false 表示 `staleDays <= 0`、本次未启用淘汰。
     */
    data class PruneResult(
        val names: List<String>,
        val skipped: Int,
        val failed: Int,
        val enabled: Boolean,
    )
}
