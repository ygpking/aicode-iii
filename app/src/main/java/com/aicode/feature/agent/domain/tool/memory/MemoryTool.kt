package com.aicode.feature.agent.domain.tool.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.memory.MemoryCuration
import com.aicode.feature.agent.domain.memory.MemoryCurationService
import com.aicode.feature.agent.domain.memory.MemoryEdit
import com.aicode.feature.agent.domain.memory.MemoryEditResult
import com.aicode.feature.agent.domain.memory.MemoryExtraction
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryRetention
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryCurationService: MemoryCurationService
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list", "curate", "propose" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "管理 AI 的长期记忆。当用户告知新的偏好、项目约定、架构设计，或者你发现了有价值的规律时，使用此工具将其永久记录。" +
            "prune 默认只预览不删除，需显式 dry_run=false 才真删；pinned 记忆永不被动。"

    /** edits 数组单个元素的结构，供 function-calling 的 items schema，语义与 editFile 一致。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与记忆当前正文精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：read=读取记忆正文；save=保存记忆（创建或全量覆盖）；edit=对已有记忆正文做局部编辑；delete=删除记忆；list=列出所有记忆摘要；curate=只读评估存量记忆的整理建议（近重复/正文过大/缺 triggers）；propose=让模型从素材提炼记忆候选（**不写盘**，返回回执 id 供确认）；apply=按回执 id 写入候选（**会覆盖同名记忆**，写前自动备份）；prune=清理超过指定天数未更新的陈旧记忆（破坏性，默认先预览）",
            enum = listOf("read", "save", "edit", "delete", "list", "curate", "propose", "apply", "prune"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "记忆的短名称（作为文件名，如 conventions）。list 操作可省略。",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "一句话摘要（save 必填，将出现在系统提示词的记忆清单中）。",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "记忆的详细正文（Markdown 格式，save 必填）。",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "edit 操作要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "作用域：project=当前项目专属；global=跨项目通用。默认为 project。",
            enum = listOf("project", "global"),
            required = false
        ),
        "kind" to ToolParameter(
            name = "kind",
            type = ParameterType.STRING,
            description = "结晶层级（仅 save 操作）：trace=原始证据；policy=归纳后的做法（默认）；" +
                "skill=该经验已写成 SKILL.md 技能（此时本条记忆是原始证据的存档）。" +
                "重复出现的操作性经验建议升格为技能并在此标注。缺省保留既有值。",
            enum = listOf("trace", "policy", "skill"),
            required = false
        ),
        "triggers" to ToolParameter(
            name = "triggers",
            type = ParameterType.ARRAY,
            description = "save 可选：用户在提问时可能用到的词/同义词/英文写法（如 [\"发版\",\"正式版\",\"release\"]，建议 3-8 项，每项不超 16 字）。" +
                "用途：当用户的话与你写的正文没有共同字词时（如说「发个正式版」而你写的是「构建环境」），" +
                "检索会命中不了；这些词就是那条「用户没说的字」的桥。" +
                "不传则保留该记忆已有的 triggers，因此更新记忆时无需重复填写；" +
                "显式传空数组 [] 表示清空该记忆的全部触发词。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "stale_days" to ToolParameter(
            name = "stale_days",
            type = ParameterType.INTEGER,
            description = "prune 必填：超过多少天未更新即视为陈旧。必须为正数；为 0 或负数不会删任何东西。" +
                "不确定时先用较大值（如 180）预览。pinned 记忆永不被清理。",
            required = false
        ),
        "dry_run" to ToolParameter(
            name = "dry_run",
            type = ParameterType.BOOLEAN,
            description = "prune 可选，默认 true：只列出将被清理的记忆，不真删。确认名单无误后传 false 执行删除。",
            required = false
        ),
        "source" to ToolParameter(
            name = "source",
            type = ParameterType.STRING,
            description = "propose 的素材来源：conversation=从当前会话历史提炼用户偏好/约定（默认）；notes=从现有记忆里找可合并/补全的条目。",
            enum = listOf("conversation", "notes"),
            required = false
        ),
        "receipt_id" to ToolParameter(
            name = "receipt_id",
            type = ParameterType.STRING,
            description = "apply 必填：propose 返回的回执 id。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val scope = if (scopeStr == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot)
                "curate" -> handleCurate(context.projectRoot)
                "propose" -> handlePropose(args, context)
                "apply" -> handleApply(args, context)
                "read" -> handleRead(memoryName, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot)
                "prune" -> handlePrune(args, context.projectRoot)
                else -> ToolResult.Error("不支持的操作: $action", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory 工具执行失败: ${e.message}", e)
            ToolResult.Error("记忆操作失败: ${e.message}", "MEMORY_FAILED")
        }
    }

    private fun handleList(projectRoot: String?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))

        // 只读评估陈旧度，仅作标注供参考——不在这里删任何东西，清理必须由 prune 显式触发。
        val report = memoryRepository.assessStaleness(projectRoot)
        val list = memories.joinToString("\n") { memory ->
            val staleTag = if (report.isStale(memory.name)) " [陈旧]" else ""
            "- ${memory.name} (${memory.scope.name.lowercase()}): ${memory.description}$staleTag"
        }
        val staleNote = if (report.staleCount > 0) {
            "\n\n提示：有 ${report.staleCount} 条记忆超过 ${MemoryRetention.DEFAULT_STALE_DAYS} 天未更新（标 [陈旧]），" +
                "如确认无用可调 memory(action=prune, stale_days=${MemoryRetention.DEFAULT_STALE_DAYS}) 预览并清理。"
        } else {
            ""
        }
        return ToolResult.Success(JsonPrimitive("当前记忆列表：\n$list$staleNote"))
    }

    /**
     * 从素材提炼候选记忆。**只生成候选、绝不写盘**：记忆写错比漏记更糟，故必须先经确认。
     */
    private suspend fun handlePropose(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val source = when (args["source"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
            "notes" -> MemoryExtraction.Source.NOTES
            else -> MemoryExtraction.Source.CONVERSATION
        }
        val outcome = memoryCurationService.propose(source, context.projectRoot, context.sessionId)
        outcome.error?.let { return ToolResult.Error(it, "CURATION_FAILED") }

        val p = outcome.proposal
        if (p.items.isEmpty()) {
            val why = if (p.rejected.isEmpty()) "未发现值得长期保存的内容"
            else "候选均未通过逐字证据校验：${p.rejected.joinToString("；")}"
            return ToolResult.Success(JsonPrimitive("提炼完成，但没有可写入的条目（$why）。"))
        }
        val sb = StringBuilder("已提炼 ${p.items.size} 条候选（**尚未写入**，回执 id=${outcome.receiptId}）：")
        p.items.forEach { item ->
            val act = if (item.isMerge) "合并到「${item.targetName}」" else "新建"
            sb.append("\n\n[").append(act).append("] ").append(item.name)
            sb.append("\n  摘要：").append(item.description)
            sb.append("\n  触发词：").append(item.triggers.joinToString(", "))
            sb.append("\n  证据：").append(item.evidence.take(160))
        }
        if (p.confirmed.isNotEmpty()) {
            sb.append("\n\n另有 ").append(p.confirmed.size).append(" 条被素材再次确认（无需写入）：")
            p.confirmed.forEach { sb.append("\n- ").append(it) }
        }
        if (p.rejected.isNotEmpty()) {
            sb.append("\n\n另有 ").append(p.rejected.size).append(" 条未通过校验已丢弃：")
            p.rejected.forEach { sb.append("\n- ").append(it) }
        }
        sb.append("\n确认后调 memory(action=apply, receipt_id=${outcome.receiptId}) 写入（写前会自动备份被覆盖的记忆）。")
        return ToolResult.Success(JsonPrimitive(sb.toString()))
    }

    /** 按回执写入候选。**破坏性**：会覆盖同名记忆，故写前自动备份。 */
    private suspend fun handleApply(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val receiptId = args["receipt_id"]?.jsonPrimitive?.contentOrNull?.trim()
        if (receiptId.isNullOrEmpty()) return ToolResult.Error("apply 操作需要 receipt_id 参数（来自 propose）", "MISSING_RECEIPT_ID")
        // 作用域从工具参数读出（与 save 同一口径），不得由 projectRoot 推导。
        val scope = if (args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() == "global") {
            MemoryScope.GLOBAL
        } else {
            MemoryScope.PROJECT
        }
        val result = memoryCurationService.apply(receiptId, scope, context.projectRoot, context.sessionId)
        result.error?.let { return ToolResult.Error(it, "APPLY_FAILED") }
        val backup = result.backupDir?.let { "，被覆盖的原文件已备份到 $it" } ?: ""
        val sb = StringBuilder("已写入 ${result.written.size} 条记忆：\n")
        sb.append(result.written.joinToString("\n") { "- $it" })
        sb.append(backup)
        if (result.skipped.isNotEmpty()) {
            sb.append("\n\n另有 ").append(result.skipped.size).append(" 条确认重申、无需写入：")
            result.skipped.forEach { sb.append("\n- ").append(it) }
        }
        if (result.conflicts.isNotEmpty()) {
            // 矛盾条目未写入目标，必须显式呈现：模型不得自行 memory(save) 覆盖已有记忆。
            sb.append("\n\n有 ").append(result.conflicts.size).append(" 条与已有记忆冲突、**未写入**，需用户裁决：")
            result.conflicts.forEach { sb.append("\n- ").append(it) }
            sb.append("\n（在用户确认前，请勿用 memory(action=save) 覆盖目标记忆。）")
        }
        return ToolResult.Success(JsonPrimitive(sb.toString()))
    }

    /**
     * 只读整理诊断。**不改任何文件**，只把「值得关注」的记忆列出来供模型/用户决定下一步。
     */
    private fun handleCurate(projectRoot: String?): ToolResult {
        val findings = memoryRepository.curate(projectRoot)
        if (findings.isEmpty()) {
            return ToolResult.Success(JsonPrimitive("未发现需要整理的记忆（无近重复、无超出索引上限的长正文、无缺 triggers 的条目）。"))
        }
        return ToolResult.Success(JsonPrimitive(buildCurationReport(findings)))
    }

    /** 把整理建议渲染成模型可读的报告（按类别分组，附具体依据）。 */
    private fun buildCurationReport(findings: List<MemoryCuration.Finding>): String {
        val sb = StringBuilder("记忆整理建议（只读诊断，未做任何修改）：")
        MemoryCuration.Kind.entries.forEach { kind ->
            val group = findings.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            sb.append("\n\n【${kindTitle(kind)}】共 ${group.size} 项")
            group.forEach { f ->
                val head = if (f.memories.size > 1) f.memories.joinToString(" × ") else f.memories.first()
                sb.append("\n- $head（${f.detail}）")
            }
        }
        sb.append("\n\n如需处理，请用 read 查看正文后用 save 重写（合并请保留一份并把另一份 delete）；确认无用的可 prune。")
        return sb.toString()
    }

    private fun kindTitle(kind: MemoryCuration.Kind): String = when (kind) {
        MemoryCuration.Kind.NEAR_DUPLICATE -> "可能的近重复"
        MemoryCuration.Kind.OVERSIZED_BODY -> "正文过长（尾部无法被召回）"
        MemoryCuration.Kind.MISSING_TRIGGERS -> "缺少 triggers"
    }

    /**
     * 清理陈旧记忆。**破坏性操作**，默认 dry-run 先预览。
     * 不做后台自动删除：记忆不可再生，自动按时间删会误删「长期有效但久未更新」的约定。
     */
    private fun handlePrune(args: Map<String, JsonElement>, projectRoot: String?): ToolResult {
        val staleDays = args["stale_days"]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()
            ?: return ToolResult.Error(
                "prune 操作需要 stale_days 参数（正整数）：超过多少天未更新即视为陈旧。建议先用 180 预览。",
                "MISSING_STALE_DAYS"
            )
        if (staleDays <= 0) {
            return ToolResult.Error("stale_days 必须为正整数（传 0 或负数不会删任何东西）", "INVALID_STALE_DAYS")
        }
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: true

        val result = memoryRepository.pruneStaleMemories(projectRoot, staleDays, dryRun)
        val scopeNote = if (result.skipped > 0) "（${result.skipped} 条 pinned 记忆已豁免）" else ""

        if (result.names.isEmpty()) {
            return ToolResult.Success(
                JsonPrimitive("没有超过 $staleDays 天未更新的记忆，无需清理$scopeNote。")
            )
        }

        return if (dryRun) {
            FileLogger.i(TAG, "memory prune 预览: staleDays=$staleDays candidates=${result.names.size}")
            ToolResult.Success(
                JsonPrimitive(
                    "【预览】超过 $staleDays 天未更新的记忆共 ${result.names.size} 条$scopeNote：\n" +
                        result.names.joinToString("\n") { "- $it" } +
                        "\n\n确认无误后调 memory(action=prune, stale_days=$staleDays, dry_run=false) 执行删除。"
                )
            )
        } else {
            FileLogger.i(TAG, "memory prune 执行: staleDays=$staleDays deleted=${result.names.size} failed=${result.failed}")
            val failNote = if (result.failed > 0) "，${result.failed} 条删除失败（见日志）" else ""
            ToolResult.Success(
                JsonPrimitive(
                    "已删除 ${result.names.size} 条超过 $staleDays 天未更新的记忆$scopeNote$failNote：\n" +
                        result.names.joinToString("\n") { "- $it" }
                )
            )
        }
    }

    private fun handleRead(name: String?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("read 操作需要 name 参数", "MISSING_NAME")
        val content = memoryRepository.loadContent(name, projectRoot)
            ?: return ToolResult.Error("未找到记忆「$name」", "MEMORY_NOT_FOUND")
        return ToolResult.Success(JsonPrimitive(content))
    }

    private fun handleSave(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("save 操作需要 name 参数", "MISSING_NAME")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 description 参数", "MISSING_DESCRIPTION")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 content 参数", "MISSING_CONTENT")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法保存项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        // 未传 triggers 时传 null，语义是「保留既有值」，与旧行为一致。
        val triggers = parseTriggers(args)
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val success = memoryRepository.saveMemory(name, description, content, scope, projectRoot, triggers, kind)
        return if (success) {
            FileLogger.i(TAG, "memory save: name=$name scope=${scope.name.lowercase()} triggers=${describeTriggers(triggers)} kind=$kind")
            val triggerNote = if (triggers.isNullOrEmpty()) "" else "（含 ${triggers.size} 个触发词）"
            val kindNote = if (kind.isNullOrBlank()) "" else "，结晶层级=$kind"
            ToolResult.Success(JsonPrimitive("已成功保存记忆「$name」到 ${scope.name.lowercase()} 作用域$triggerNote$kindNote。它将在下一次会话启动时自动注入摘要。当前会话若需立即使用，请通过 read 操作读取。"))
        } else {
            ToolResult.Error("保存记忆失败，请查看日志。", "WRITE_FAILED")
        }
    }

    /**
     * 解析 `triggers` 数组。缺省（未传/非数组）返回 null，语义为「保留既有值」——
     * 这样模型更新一条已有记忆时无需把触发词再抄一遍，也不会因漏写而把元数据抹掉。
     * 显式传了数组（含空数组）则返回列表：空数组语义为「清空既有触发词」，与
     * [MemorySource] 的数据层契约（非 null 即设为该值）保持一致。
     */
    private fun parseTriggers(args: Map<String, JsonElement>): List<String>? {
        val arr = args["triggers"] as? JsonArray ?: return null
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 日志用的 triggers 摘要：null=保留既有；空列表=已清空；非空=数量与前 5 个。 */
    private fun describeTriggers(triggers: List<String>?): String = when {
        triggers == null -> "保留既有"
        triggers.isEmpty() -> "已清空"
        else -> "${triggers.size} 个（${triggers.take(5).joinToString(",")}）"
    }

    private fun handleEdit(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("edit 操作需要 name 参数", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("edit 操作需要 edits 参数：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法编辑项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot)) {
            is MemoryEditResult.Success -> {
                FileLogger.i(TAG, "memory edit: name=$name scope=${scope.name.lowercase()} edits=${edits.size} 条")
                ToolResult.Success(JsonPrimitive("已成功编辑记忆「$name」的正文（${scope.name.lowercase()} 作用域）。"))
            }
            is MemoryEditResult.NotFound ->
                ToolResult.Error("未找到记忆「${result.name}」，请先通过 save 创建，或确认 name 与作用域是否正确。", "MEMORY_NOT_FOUND")
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("delete 操作需要 name 参数", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            FileLogger.i(TAG, "memory delete: name=$name scope=${scope.name.lowercase()}")
            ToolResult.Success(JsonPrimitive("已成功删除 ${scope.name.lowercase()} 作用域的记忆「$name」。"))
        } else {
            FileLogger.w(TAG, "memory delete 失败（未找到）: name=$name scope=${scope.name.lowercase()}")
            ToolResult.Error("删除失败，记忆「$name」可能不存在于该作用域。", "DELETE_FAILED")
        }
    }
}
