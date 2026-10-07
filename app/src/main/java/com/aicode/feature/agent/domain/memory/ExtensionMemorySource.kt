package com.aicode.feature.agent.domain.memory

import com.aicode.core.text.NameKey
import java.io.File

/**
 * 扩展包贡献的记忆目录的只读源（贡献 manifest 的 memory 字段）。
 *
 * 只读语义：扩展记忆随扩展包走，save/edit/delete 一律拒绝（改扩展内容请直接改扩展目录
 * 里的 md 文件，或经主会话的文件工具）；touchMemory（召回命中回写）保留默认实现——
 * 使用信号回写到扩展目录内的文件是安全的，扩展目录本身可写。
 *
 * @param dirs 扩展贡献的记忆目录（已由仓库层按「全局在前、项目在后」排序）。
 * @param scope 解析 Memory 时标注的作用域（目录属于哪个扩展作用域）。
 */
class ExtensionMemorySource(
    private val dirs: List<File>,
    private val scope: MemoryScope
) : MemorySource {

    private val memories: List<Memory> by lazy {
        dirs.flatMap { dir ->
            val files = dir.listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray()
            files.mapNotNull { MemoryParser.parse(it, scope) }
        }
    }

    override fun listMemories(): List<Memory> = memories

    override fun loadContent(name: String): String? =
        memories.firstOrNull { NameKey.of(it.name) == NameKey.of(name) }?.content

    override fun memoryFile(name: String): File {
        val f = memories.firstOrNull { NameKey.of(it.name) == NameKey.of(name) }?.file
            ?: File(dirs.firstOrNull() ?: File("."), "${MemorySource.sanitizeName(name)}.md")
        return f
    }

    override fun saveMemory(
        name: String,
        description: String,
        content: String,
        triggers: List<String>?,
        kind: String?
    ): Boolean = false

    override fun deleteMemory(name: String): Boolean = false
}
