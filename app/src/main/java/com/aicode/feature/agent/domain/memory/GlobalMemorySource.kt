package com.aicode.feature.agent.domain.memory

import com.aicode.core.text.NameKey
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalMemorySource @Inject constructor(
    private val containerInstaller: ContainerInstaller
) : MemorySource {

    private val memoryRoot: File by lazy {
        File(containerInstaller.aicodeDir, "memory").also { it.mkdirs() }
    }

    override fun listMemories(): List<Memory> {
        if (!memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file ->
            // 单个坏记忆只跳过自己，绝不连带整表（与 Skill/Agent 扫描器同一根因）。
            runCatching { MemoryParser.parse(file, MemoryScope.GLOBAL) }
                .onFailure { FileLogger.w("GlobalMemorySource", "解析全局记忆失败，已跳过: ${file.name}", it) }
                .getOrNull()
        }.sortedBy { NameKey.of(it.name) }
    }

    override fun loadContent(name: String): String? {
        return listMemories()
            .firstOrNull { NameKey.of(it.name) == NameKey.of(name) }
            ?.content
    }

    override fun saveMemory(
        name: String,
        description: String,
        content: String,
        triggers: List<String>?,
        kind: String?,
    ): Boolean {
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            // 保留既有 pinned 与 triggers（全量覆盖不应丢失元数据）；调用方显式传入 triggers 时以传入值为准。
            val existing = listMemories().firstOrNull { NameKey.of(it.name) == NameKey.of(name) }
            val pinned = existing?.pinned ?: false
            val effectiveTriggers = triggers ?: existing?.triggers ?: emptyList()
            val effectiveKind = MemoryKind.fromToken(kind) ?: existing?.kind ?: MemoryKind.POLICY
            file.writeText(
                MemoryParser.format(
                    MemorySource.sanitizeName(name), description, content, pinned, effectiveTriggers,
                    updatedAtMs = MemorySource.resolveUpdatedAt(
                        existing, description, content, System.currentTimeMillis()
                    ),
                    // 全量覆盖保留既有使用信号与结晶状态（同 pinned/triggers 的保留逻辑）。
                    lastUsedMs = existing?.lastUsedMs ?: 0L,
                    recallCount = existing?.recallCount ?: 0,
                    kind = effectiveKind,
                    crystallizedTo = existing?.crystallizedTo,
                )
            )
            true
        } catch (e: Exception) {
            FileLogger.e("GlobalMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }

    override fun memoryFile(name: String): File = MemorySource.resolveMemoryFile(memoryRoot, name)
}
