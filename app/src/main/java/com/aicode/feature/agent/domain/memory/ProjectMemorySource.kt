package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File

/**
 * 项目级记忆数据源。由 Repository 动态创建（依赖当前会话的 projectRoot）。
 *
 * 本地模式：存 `<projectRoot>/.aicode/memory/`（跟随工作区目录）；
 * 远程模式：projectRoot 是远程服务器路径，java.io.File 无法在本地创建该目录（保存必失败），
 * 故改存全局配置目录下 `memory/projects/<项目名>-<标识哈希>/`（本地可写、跨连接稳定）。
 * 标识哈希基于「IP:端口:路径」计算，不同服务器上的同名路径（如都叫 /home/u/workspace/default）
 * 也会落到不同目录，不会混淆。
 */
class ProjectMemorySource(
    private val projectRoot: String,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) : MemorySource {

    private val memoryRoot: File by lazy {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(containerInstaller.aicodeDir, "memory/projects"), projectAicodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".aicode/memory")
        }
    }

    override fun listMemories(): List<Memory> {
        if (projectRoot.isBlank() || !memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file ->
            // 单个坏记忆只跳过自己，绝不连带整表（与 Skill/Agent 扫描器同一根因）。
            runCatching { MemoryParser.parse(file, MemoryScope.PROJECT) }
                .onFailure { FileLogger.w("ProjectMemorySource", "解析项目记忆失败，已跳过: ${file.name}", it) }
                .getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        if (projectRoot.isBlank()) return null
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(name: String, description: String, content: String, triggers: List<String>?): Boolean {
        if (projectRoot.isBlank()) return false
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            // 保留既有 pinned 与 triggers（全量覆盖不应丢失元数据）；调用方显式传入 triggers 时以传入值为准。
            val existing = listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }
            val pinned = existing?.pinned ?: false
            val effectiveTriggers = triggers ?: existing?.triggers ?: emptyList()
            file.writeText(
                MemoryParser.format(
                    MemorySource.sanitizeName(name), description, content, pinned, effectiveTriggers
                )
            )
            true
        } catch (e: Exception) {
            FileLogger.e("ProjectMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        if (projectRoot.isBlank()) return false
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }
}
