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
 * @param updatedAtMs **内容派生的**更新时间（frontmatter `updated`，epoch 毫秒）；0 表示未知。
 *   **不要用文件 mtime 代替它**：mtime 只说明「文件被写过」，批量格式重写（补 triggers、
 *   统一 frontmatter 风格）会把它全部刷成同一时刻，而内容年龄根本没变——09-30 那次
 *   15 个文件的时间戳被刷到同一秒，此后 mtime 不再是「内容新旧」的证据。
 * @param lastUsedMs 召回命中后回写的最近使用时间（frontmatter `last_used`）；0 表示从未被召回。
 *   与 [updatedAtMs] 分开：被召回不改变内容新旧，只证明「这条记忆还有用」。
 * @param recallCount 召回命中累计次数（frontmatter `recall_count`）；0 表示从未被召回。
 *   与时间衰减正交：衰减证明「新」，使用计数证明「常被用到」，排序可叠加。
 * @param keyPoints 正文 `## 要点` 段的条目（L1 层，从正文派生不入 frontmatter）：
 *   召回命中先注要点而非正文首段，模型要细节再 read 全文。存量无该段为空表。
 * @param kind 结晶层级（frontmatter `kind`）：trace（原始证据）/ policy（归纳做法，缺省）/ skill（已升格）。
 * @param crystallizedTo 已升格为的技能名（frontmatter `crystallized_to`）；null 表示尚未升格。
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val pinned: Boolean = false,
    val triggers: List<String> = emptyList(),
    val malformed: Boolean = false,
    val updatedAtMs: Long = 0L,
    val lastUsedMs: Long = 0L,
    val recallCount: Int = 0,
    val keyPoints: List<String> = emptyList(),
    val kind: String = MemoryKind.POLICY,
    val crystallizedTo: String? = null,
) {
    /**
     * 有效更新时间：优先用 frontmatter 的 `updated`（内容派生），缺失时**回退**文件 mtime。
     *
     * 一切「这份内容有多新」的判据都走这里（陈旧评估、召回时衰）。回退只为兼容存量文件
     * （它们还没有 `updated`）；一旦某文件被有意义地改过一次，此后就不再依赖 mtime。
     */
    val effectiveUpdatedAtMs: Long
        get() = updatedAtMs.takeIf { it > 0L } ?: (file?.lastModified() ?: 0L)
}

enum class MemoryScope {
    GLOBAL, PROJECT
}

/** 结晶层级常量（[Memory.kind]）。 */
object MemoryKind {
    const val TRACE = "trace"
    const val POLICY = "policy"
    const val SKILL = "skill"

    private val ALL = setOf(TRACE, POLICY, SKILL)

    /** 宽松归一化：未知/非法值返回 null（调用方回退既有值或 POLICY）。 */
    fun fromToken(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it in ALL }
}
