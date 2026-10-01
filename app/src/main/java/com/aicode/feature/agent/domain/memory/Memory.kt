package com.aicode.feature.agent.domain.memory

import java.io.File

/**
 * 解析后的单个 Memory 模型。
 *
 * @param name 记忆名称（供大模型调用的唯一标识，通常对应文件名不含扩展名）
 * @param description 记忆描述（一句话摘要，注入到系统提示词中）
 * @param scope 记忆的作用域（GLOBAL 或 PROJECT）
 * @param file 记忆对应的本地文件
 * @param content 记忆正文（剥离 Frontmatter 后的详细内容）
 * @param pinned true 表示「必常驻」：召回时与相关性正交，直接置顶。在 frontmatter 中写 `pinned: true` 开启。
 * @param triggers 用户在提问时可能用到的词/同义词/英文写法（frontmatter `triggers`）。
 *   仅用于召回匹配（门控与加权），**不注入系统提示词清单**：它存在的意义正是让
 *   「用户没说正文里那些字」的查询也能命中（如「发正式版」→ android-build-env）。
 * @param malformed true 表示文件 frontmatter 畸形（未闭合，已尽力恢复）。此时 [content] 可能仍残留
 *   文档头部碎片，回写（edit）会把畸形固化，故 [MemorySource.editMemory] 会直接拒绝。
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val pinned: Boolean = false,
    val triggers: List<String> = emptyList(),
    val malformed: Boolean = false
)

enum class MemoryScope {
    GLOBAL, PROJECT
}
