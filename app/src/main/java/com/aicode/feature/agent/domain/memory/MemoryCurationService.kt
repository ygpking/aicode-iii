package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.ResolvedChatProvider
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.UntrustedEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.aicode.core.util.runCatchingCancellable

/**
 * 记忆提炼的编排层：**取素材 → 调 LLM → 逐字校验 → 存回执**，落盘由 [apply] 显式触发。
 *
 * 为什么分两步（propose / apply）：记忆是不可再生的用户资产，错误记忆比「记不住」更糟。
 * 上游 dream 默认 `preview_mode=true` 也是同一取舍——本条把它落成硬约束：**LLM 永远不会
 * 直接写记忆**，只能产出候选；写盘必须由第二次显式调用触发，且先备份。
 *
 * 反幻觉三道：
 * 1. 素材以 `UntrustedEnvelope` 包裹——会话历史是**不可信数据**，其中的指令性文字不得越权；
 * 2. LLM 只能输出受限的 upsert/merge 结构（见 [MemoryExtraction.verify]）；
 * 3. 每条必须附**逐字证据**，对不上原文的整条丢弃并如实计入 [MemoryExtraction.Proposal.rejected]。
 */
@Singleton
class MemoryCurationService @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val resolvedChatProvider: ResolvedChatProvider,
    private val containerInstaller: ContainerInstaller,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 一次 propose 的结果。 */
    data class Outcome(
        val proposal: MemoryExtraction.Proposal,
        val receiptId: String? = null,
        val error: String? = null,
    )

    /** 一次 apply 的结果。 */
    data class ApplyResult(
        val written: List<String>,
        val backupDir: String?,
        val error: String? = null,
    )

    /**
     * 生成候选（**不写记忆**）。结果同时落成回执文件，供 [apply] 按 id 执行。
     *
     * @param sessionId 会话 id；[MemoryExtraction.Source.CONVERSATION] 时必填（素材来源）。
     */
    suspend fun propose(
        source: MemoryExtraction.Source,
        projectRoot: String?,
        sessionId: String?,
    ): Outcome {
        val memories = runCatching { memoryRepository.listMemories(projectRoot) }.getOrDefault(emptyList())
        val material = when (source) {
            MemoryExtraction.Source.CONVERSATION -> {
                if (sessionId.isNullOrBlank()) return Outcome(MemoryExtraction.Proposal(emptyList()), error = "会话历史提炼需要当前会话上下文")
                renderConversation(sessionId)
            }
            MemoryExtraction.Source.NOTES -> renderNotes(memories)
        }
        if (material.isBlank()) {
            return Outcome(MemoryExtraction.Proposal(emptyList()), error = "没有可提炼的素材")
        }

        val provider = resolvedChatProvider.resolve(sessionId)
            ?: return Outcome(MemoryExtraction.Proposal(emptyList()), error = "尚未配置可用的 AI 供应商，请到设置中添加并选择模型")

        val names = memories.map { it.name }
        val userPrompt = when (source) {
            MemoryExtraction.Source.CONVERSATION -> MemoryExtraction.conversationUserPrompt(material, names)
            MemoryExtraction.Source.NOTES -> MemoryExtraction.notesUserPrompt(material, names)
        }

        val response = runCatchingCancellable {
            // 一次性请求：不写服务端缓存（结果不会被复用，写入纯属白花钱）。
            provider.complete(
                systemPrompt = MemoryExtraction.systemPrompt(source),
                messages = listOf(AgentMessage.UserMessage(content = userPrompt)),
                disablePromptCaching = true,
            )
        }.getOrElse { e ->
            FileLogger.w(TAG, "记忆提炼调用失败", e)
            return Outcome(MemoryExtraction.Proposal(emptyList()), error = "提炼调用失败：${e.message}")
        }

        // 证据必须来自**素材原文**（不是提示词全文，避免模型把提示词里的示例当证据）。
        val proposal = MemoryExtraction.verify(response.content, material, names.toSet())
        val receiptId = saveReceipt(source, proposal)
        return Outcome(proposal, receiptId)
    }

    /**
     * 执行一份回执里的写入（**破坏性**：会覆盖同名记忆）。写前把将被覆盖的文件备份到
     * `.curation/backup-<receiptId>/`，回执本身留在 `.curation/` 供事后核对。
     *
     * [scope] 由调用方（工具参数）**显式传入**，不得由 `projectRoot` 推导——设备实测发现：
     * 早期实现按 `projectRoot.isNullOrBlank()` 推导，连着工作区时 `scope=global` 被静默忽略，
     * 文件写到了项目级，用户既无法写全局也无任何提示（与 `save` 尊重 scope 的行为不对称）。
     */
    suspend fun apply(
        receiptId: String,
        scope: MemoryScope,
        projectRoot: String?,
        sessionId: String?,
    ): ApplyResult {
        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ApplyResult(emptyList(), null, "当前未选择工作区，无法写入项目级记忆。请改用 scope=global")
        }
        val receiptFile = File(receiptDir(), "$receiptId.json")
        if (!receiptFile.isFile) return ApplyResult(emptyList(), null, "找不到回执 $receiptId（可能已被清理或从未来过 propose）")
        val receipt = runCatching { json.decodeFromString<CurationReceipt>(receiptFile.readText()) }.getOrElse {
            return ApplyResult(emptyList(), null, "回执内容无法解析：${it.message}")
        }
        if (receipt.items.isEmpty()) return ApplyResult(emptyList(), null, "该回执没有任何条目")

        val backupDir = File(receiptDir(), "backup-$receiptId").also { it.mkdirs() }
        val written = ArrayList<String>()

        for (item in receipt.items) {
            val name = if (item.isMerge && !item.targetName.isNullOrBlank()) item.targetName else item.name
            // 覆盖前备份：合并会改掉目标记忆的正文，出问题时能逐字节还原。
            // 必须在**目标作用域内**定位文件：仓库层 listMemories 跨作用域合并且项目级优先，
            // 用它会在「另一作用域存在同名」时备份到**另一个文件**，而被覆盖的那份原文丢失
            // （实测确认：apply(scope=global) + 同名项目级存在时，备份存的是项目级内容）。
            memoryRepository.memoryFile(name, scope, projectRoot)
                ?.takeIf { it.isFile }?.let { src ->
                    runCatching { src.copyTo(File(backupDir, "${name}.md"), overwrite = true) }
                }
            val ok = memoryRepository.saveMemory(
                name = name,
                description = item.description,
                content = item.content,
                scope = scope,
                projectRoot = projectRoot,
                triggers = item.triggers,
            )
            if (ok) written += name else FileLogger.w(TAG, "记忆写入失败: $name")
        }
        FileLogger.i(TAG, "记忆整理已应用: receipt=$receiptId scope=${scope.name.lowercase()} written=${written.size}")
        return ApplyResult(written, backupDir.absolutePath)
    }

    /** 回执存放目录（持久化在 aicode 配置目录下，与记忆同域，便于一起备份/清理）。 */
    private fun receiptDir(): File =
        File(containerInstaller.aicodeDir, "memory/.curation").also { it.mkdirs() }

    private fun saveReceipt(source: MemoryExtraction.Source, proposal: MemoryExtraction.Proposal): String? {
        val id = UUID.randomUUID().toString().take(8)
        val receipt = CurationReceipt(
            id = id,
            createdAt = System.currentTimeMillis(),
            source = source.name,
            items = proposal.items.map {
                ReceiptItem(it.name, it.description, it.content, it.triggers, it.evidence, it.isMerge, it.targetName)
            },
            rejected = proposal.rejected,
        )
        return runCatching {
            File(receiptDir(), "$id.json").writeText(json.encodeToString(receipt))
            id
        }.getOrElse {
            FileLogger.w(TAG, "回执写入失败", it)
            null
        }
    }

    /**
     * 把会话历史渲染成提炼素材。
     *
     * 只保留**用户与助手的可见文本**：工具调用与工具结果体量大、且多是机器输出，把它们塞进去
     * 既挤占预算也稀释信号（真正值得长期记住的偏好/约定几乎都出现在用户话里）。
     *
     * 整段作为不可信数据包裹：会话内容可能包含从网页/文件里读到的文本，其中任何「指令」都
     * 不得被当作系统指令执行。
     */
    private suspend fun renderConversation(sessionId: String): String {
        val history = runCatchingCancellable {
            messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
        }.getOrElse {
            FileLogger.w(TAG, "读取会话历史失败", it)
            return ""
        }
        val sb = StringBuilder()
        for (m in history) {
            when (m) {
                is AgentMessage.UserMessage -> {
                    if (m.content.isNotBlank()) sb.append("User: ").append(m.content.trim()).append("\n\n")
                }
                is AgentMessage.AssistantMessage -> {
                    if (m.content.isNotBlank()) sb.append("Assistant: ").append(m.content.trim()).append("\n\n")
                }
                else -> Unit
            }
        }
        val text = sb.toString().takeLast(CONVERSATION_MAX_CHARS)
        if (text.isBlank()) return ""
        return UntrustedEnvelope.wrap("conversation-history", text) + "\n"
    }

    /** 把全部记忆渲染成提炼素材（供 NOTES 模式找重复/补全）。 */
    private fun renderNotes(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val sb = StringBuilder()
        for (m in memories) {
            sb.append("## ").append(m.name).append('\n')
            if (m.description.isNotBlank()) sb.append(m.description).append('\n')
            if (m.triggers.isNotEmpty()) sb.append("triggers: ").append(m.triggers.joinToString(", ")).append('\n')
            sb.append(m.content.take(NOTES_BODY_CHARS)).append("\n\n")
        }
        return UntrustedEnvelope.wrap("memory-notes", sb.toString().take(NOTES_MAX_CHARS)) + "\n"
    }

    private companion object {
        const val TAG = "MemoryCuration"
        /** 会话素材上限：只取最近一段，避免长会话把预算吃光而信号反而更稀。 */
        const val CONVERSATION_MAX_CHARS = 20_000
        const val NOTES_BODY_CHARS = 1_500
        const val NOTES_MAX_CHARS = 40_000
    }
}

/** 一次 propose 的落盘回执，供 [MemoryCurationService.apply] 按 id 执行与事后核对。 */
@Serializable
internal data class CurationReceipt(
    val id: String,
    val createdAt: Long,
    val source: String,
    val items: List<ReceiptItem>,
    val rejected: List<String> = emptyList(),
)

/** 回执里的一条待写入记忆（含证据，便于事后追问「这条凭什么这么写」）。 */
@Serializable
internal data class ReceiptItem(
    val name: String,
    val description: String,
    val content: String,
    val triggers: List<String> = emptyList(),
    val evidence: String = "",
    val isMerge: Boolean = false,
    val targetName: String? = null,
)
