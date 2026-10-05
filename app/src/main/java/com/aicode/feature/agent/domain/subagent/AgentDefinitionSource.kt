package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionDirectoryScanner"

    /**
     * 扫描 [root] 下顶层 `*.md`，每个文件一个定义。目录不存在时返回空列表；
     * 列举目录本身失败（远程工作区未连接、IO 错误）时同样降级为空列表，
     * 不向上抛——扫描失败会同时打掉子代理列表与可派发清单，不该让调用方崩溃。
     */
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            // 单个坏定义只跳过自己，绝不连带整表：否则一个畸形 .md 会让全部子代理从列表与可派发清单中消失。
            .mapNotNull { entry ->
                runCatching { AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
                    .onFailure { FileLogger.w(TAG, "解析子代理定义失败，已跳过: ${entry.name}", it) }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描子代理定义目录失败: $root", e)
        emptyList()
    }
}
