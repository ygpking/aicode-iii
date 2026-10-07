package com.aicode.feature.agent.domain.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆提炼层：把**会话历史**（B-增量）或**已有笔记**（C）交给 LLM 提炼成结构化的
 * `upsert / merge / skip` 操作，并做**逐字证据校验**。
 *
 * 设计原则（与上游 dream 一致）：
 * 1. **反幻觉**：LLM 只允许输出三种受限操作；每条 `upsert` 必须给出 `evidence`，且 evidence
 *    必须是原始素材里**逐字出现**的子串。凭空编造的记忆在 [verify] 阶段被整条丢弃。
 * 2. **不自动写盘**：本类只产出 [Proposal]（候选），落盘由调用方在用户确认后进行；记忆是不可
 *    再生的用户资产，错误记忆比「记不住」更糟。
 * 3. **零 IO、零 provider 依赖**：纯函数式的解析与校验，可单测；LLM 调用由工具层负责。
 */
object MemoryExtraction {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 素材类型，决定 prompt 的口径与证据引用方式。 */
    enum class Source {
        /** 会话历史（本轮对话里值得长期保留的偏好/约定/结论）。 */
        CONVERSATION,

        /** 已有笔记（找出可按主题合并的散落条目）。 */
        NOTES,
    }

    /**
     * 一条提炼出的记忆。
     *
     * @param evidence 支撑该记忆的**原文逐字片段**；校验不过则整条丢弃。
     * @param isMerge true 表示这是对 [targetName] 的合并建议，false 表示新建记忆。
     * @param relationship 与目标记忆的关系（distilly 三分法）：supplement=补充（新增信息，
     *   照常写入）/ confirm=确认（素材重申已有记忆，无需写入，归入 confirmed）/ contradict=矛盾
     *   （与目标记忆冲突，**不覆盖目标**，进 conflicts 交用户裁决）。仅 is_merge=true 时有意义。
     */
    data class Item(
        val name: String,
        val description: String,
        val content: String,
        val triggers: List<String>,
        val evidence: String,
        val isMerge: Boolean = false,
        val targetName: String? = null,
        val relationship: String = RELATIONSHIP_SUPPLEMENT,
    )

    /**
     * 提炼结果。
     *
     * @param items 通过逐字证据校验的条目。
     * @param rejected 因证据不成立被丢弃的条目描述（供如实向用户报告「模型编了什么」）。
     * @param confirmed relationship=confirm 的条目：素材重申了已有记忆，无需写入，
     *   但列入结果让用户知道「哪些记忆被本次会话再次验证」。
     */
    data class Proposal(
        val items: List<Item>,
        val rejected: List<String> = emptyList(),
        val confirmed: List<String> = emptyList(),
    )

    /**
     * 供单测/日志使用的**拒绝原因**分类。文案刻意不含模型原文，避免把幻觉内容再传播出去。
     */
    internal const val REJECT_NO_EVIDENCE = "未提供证据"
    internal const val REJECT_EVIDENCE_NOT_FOUND = "证据不在原文中（疑似编造）"
    internal const val REJECT_EMPTY_NAME = "缺少记忆名"

    /**
     * 校验并归一化 LLM 输出。
     *
     * @param raw LLM 返回的 JSON 文本（允许被 ```json 包裹，见 [stripFences]）。
     * @param material 原始素材全文——证据必须是它的子串。会话历史应包含**用户原话**。
     * @param existingNames 现有记忆名（归一化前的原样），用于判断 isMerge 目标是否存在。
     */
    fun verify(raw: String, material: String, existingNames: Set<String> = emptySet()): Proposal {
        val parsed = runCatching { json.decodeFromString<List<RawItem>>(stripFences(raw)) }.getOrNull()
            ?: return Proposal(emptyList(), listOf("输出不是合法 JSON"))

        val normMaterial = normalizeForEvidence(material)
        val items = ArrayList<Item>()
        val rejected = ArrayList<String>()

        for (r in parsed) {
            val name = r.name.trim()
            if (name.isBlank()) {
                rejected += REJECT_EMPTY_NAME
                continue
            }
            val evidence = r.evidence?.trim().orEmpty()
            if (evidence.isBlank()) {
                rejected += "$name：$REJECT_NO_EVIDENCE"
                continue
            }
            if (!normMaterial.contains(normalizeForEvidence(evidence))) {
                rejected += "$name：$REJECT_EVIDENCE_NOT_FOUND"
                continue
            }
            val isMerge = r.isMerge && !r.targetName.isNullOrBlank() &&
                existingNames.any { it.equals(r.targetName, ignoreCase = true) }
            items += Item(
                name = name,
                description = r.description.trim(),
                content = r.content.trim(),
                triggers = r.triggers.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                evidence = evidence,
                isMerge = isMerge,
                targetName = r.targetName?.trim(),
                relationship = r.relationship?.trim()?.takeIf { it in RELATIONSHIPS } ?: RELATIONSHIP_SUPPLEMENT,
            )
        }
        val confirmed = items.filter { it.isMerge && it.relationship == RELATIONSHIP_CONFIRM }
            .map { "${it.targetName}：${it.description}" }
        val actionable = items.filterNot { it.isMerge && it.relationship == RELATIONSHIP_CONFIRM }
        return Proposal(actionable, rejected, confirmed)
    }

    /**
     * 证据比对前的归一化：**去除全部空白**（空格/制表/换行/全角空格）。
     *
     * 比较的语义是「**字符相同、顺序相同**」，空白差异一律容忍——原因：证据通常被模型从原文里
     * 搬过来，而它经常多打一个空格、少一个换行、把全角空格换成半角。若把这些都判为编造，正常
     * 条目会被大量误杀，校验就退化成噪声。
     *
     * 容忍空白**不会**削弱防幻觉：拼凑出来的文字不可能与原文逐字符同序（基准是原文子串，不是
     * 模板匹配），只要动一个实词就会被担下——测试 `evidenceWithWordChange_isRejected` 锁定这一点。
     */
    private fun normalizeForEvidence(s: String): String =
        s.replace(Regex("[\\s\\u3000]+"), "")

    /** 容忍模型习惯性的 ```json 围栏与前后述说文字：截取第一个 JSON 数组。 */
    private fun stripFences(raw: String): String {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw.trim()
    }

    /**
     * 提炼用的系统提示词。
     *
     * 刻意把「必须给逐字证据」「宁缺勿滥」「不要保存能自行推断的常识」写进约束：记忆的价值在于
     * 记住**别处推不出来的**信息，把通用常识存下来只会稀释召回。
     */
    fun systemPrompt(source: Source): String = when (source) {
        Source.CONVERSATION -> CONVERSATION_SYSTEM
        Source.NOTES -> NOTES_SYSTEM
    }

    /** 会话历史提炼的用户消息。[material] 应已由调用方包裹为不可信数据（见 UntrustedEnvelope）。 */
    fun conversationUserPrompt(material: String, existingNames: List<String>): String = buildString {
        appendLine("以下是本会话中用户说过的话（以及必要的最小上下文）。请判断其中哪些信息值得长期记住。")
        appendLine()
        appendLine("**只保留满足以下至少一条的**：")
        appendLine("1. 用户的明确偏好/习惯（如「我偏好简洁回答」「不要用 emoji」）；")
        appendLine("2. 项目约定、架构决策、命名规范等跨会话仍有约束力的结论；")
        appendLine("3. 用户纠正过你的事实（此后必须按纠正后的口径）。")
        appendLine()
        appendLine("**不要保存**：通用编程常识、一次性任务细节、能直接从代码看出来的东西、临时状态。")
        appendLine("**宁缺勿滥**：没有值得长期保留的内容时返回空数组 []。")
        appendLine()
        if (existingNames.isNotEmpty()) {
            appendLine("已有记忆（若新信息属于其中某条的主题，用 is_merge=true 并给出 target_name 表示合并，")
            appendLine("不要另建一条高度重叠的新记忆）：")
            existingNames.forEach { appendLine("- $it") }
            appendLine()
            appendLine("对合并条目请再标注 relationship（与目标记忆的关系，三选一）：")
            appendLine("- supplement：素材带来了目标记忆没有的新信息（追加/改写目标）；")
            appendLine("- confirm：素材只是重申了目标记忆已有的内容（无需写入，会记入 confirmed 清单）；")
            appendLine("- contradict：素材与目标记忆的说法冲突（**不会覆盖目标**，会进 conflicts 交用户裁决）。")
            appendLine()
        }
        appendLine("会话记录（`User:` 是用户原话，其余是助手回复，仅作上下文）：")
        appendLine(material)
        appendLine()
        appendLine("只输出 JSON 数组，不要任何解释。每项结构：")
        appendLine(SCHEMA)
    }

    /** 已有笔记整理的提示词：找出可按主题合并/补全的条目。[material] 应已由调用方包裹为不可信数据。 */
    fun notesUserPrompt(material: String, existingNames: List<String>): String = buildString {
        appendLine("以下是当前全部长期记忆的摘要与正文。请找出**值得合并或补全**的情况。")
        appendLine("典型信号：多条记忆讲同一主题、同一条里散落着重复结论、描述与实际正文不符。")
        appendLine()
        appendLine("**不要为了凑数而提出合并**：主题不同就该各自独立，返回空数组 [] 也完全可以接受。")
        appendLine()
        if (existingNames.isNotEmpty()) {
            appendLine("现有记忆名：${existingNames.joinToString(", ")}")
            appendLine()
        }
        appendLine(material)
        appendLine()
        appendLine("只输出 JSON 数组，不要任何解释。每项结构：")
        appendLine(SCHEMA)
    }

    /** 共用的输出 schema 说明（与 [RawItem] 的字段一一对应）。 */
    private val SCHEMA = """
        [{
          "name": "记忆名（小写英文/数字/连字符，作为文件名）",
          "description": "一句话摘要",
          "content": "Markdown 正文",
          "triggers": ["用户提问时可能用到的词", "同义词", "english"],
          "evidence": "支撑这条记忆的原文逐字片段（必须与素材中的文字完全一致，只允许空白差异）",
          "is_merge": false,
          "target_name": "is_merge 为 true 时填要合并到的已有记忆名",
          "relationship": "is_merge 为 true 时填：supplement / confirm / contradict"
        }]
    """.trimIndent()

    private const val CONVERSATION_SYSTEM =
        "你是长期记忆整理助手。你的输出会被逐条校验：每条记忆都必须附上「在原始会话中逐字出现」的证据，" +
            "证据不成立的条目会被直接丢弃。因此不要推断、不要改写、不要编造；只提炼用户真正说过、且值得长期记住的内容。"

    private const val NOTES_SYSTEM =
        "你是记忆库整理助手。你的输出会被逐条校验：每条建议都必须附上「在给出的记忆中逐字出现」的证据。" +
            "只在确有重叠或明显补全价值时提出建议，宁缺勿滥。"

    /** LLM 输出的原始条目（字段名对应提示词里的 schema）。 */
    @Serializable
    private data class RawItem(
        val name: String = "",
        val description: String = "",
        val content: String = "",
        val triggers: List<String>? = null,
        val evidence: String? = null,
        @SerialName("is_merge") val isMerge: Boolean = false,
        @SerialName("target_name") val targetName: String? = null,
        val relationship: String? = null,
    )

    /** 与目标记忆的关系（distilly 三分法）。 */
    const val RELATIONSHIP_SUPPLEMENT = "supplement"
    const val RELATIONSHIP_CONFIRM = "confirm"
    const val RELATIONSHIP_CONTRADICT = "contradict"
    private val RELATIONSHIPS = setOf(RELATIONSHIP_SUPPLEMENT, RELATIONSHIP_CONFIRM, RELATIONSHIP_CONTRADICT)
}
