package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> {
        if (!provider.isDirectory(root)) return emptyList()
        val base = root.trimEnd('/')
        return provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            // 单个坏定义只跳过自己，绝不连带整表：否则一个畸形 .md 会让全部子代理从列表与可派发清单中消失。
            .mapNotNull { entry ->
                runCatching { AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
                    .onFailure { FileLogger.w(TAG, "解析子代理定义失败，已跳过: ${entry.name}", it) }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
    }

    private const val TAG = "AgentDefinitionDirectoryScanner"
}
