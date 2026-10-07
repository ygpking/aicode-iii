package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.domain.subagent.SubAgentInteractionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 每个 Source 维护内容缓存，避免重复读取与格式化。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`（见 [BASE_FRAGMENTS]），可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（模式提醒、子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，主代理提示词只由自定义数字片段组成，
 * 不再注入任何内置来源；此时用 `{{AICODE_*}}` 变量按需取回动态内容。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val executionModeHolder: com.aicode.feature.settings.data.repository.ExecutionModeHolder
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String {
            return cached ?: run {
                val merged = PromptFragmentResolver.mergeStatic(
                    BASE_FRAGMENTS.keys.toList(),
                    PromptFragmentResolver.numberedFragments(customDir)
                )
                val pieces = merged.mapNotNull { (number, override) ->
                    // 内置数字走 resolvePrompt（内部按数字身份查覆盖）；新增片段直接读自定义文件。
                    val raw = BASE_FRAGMENTS[number]?.let { resolvePrompt(it) }
                        ?: readFileOrNull(override)
                    raw?.replace(LEADING_COMMENT, "")?.trim()?.takeIf { it.isNotEmpty() }
                }
                pieces.joinToString("\n\n").also { cached = it }
            }
        }
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) { return null }
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${triggerHint(it.description)}" }
            val content = "可用技能 (skills)（格式为 名称: 何时使用；相关时用 loadSkill 传入名称取完整正文，详见上文「技能」说明）：\n当清单里有与当前任务对口的技能时，在合适的时机主动 `loadSkill` 加载并按其正文行事，让技能辅助你更规范、更高效地完成工作，而不是仅凭默认流程硬做。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        /**
         * 清单里只留「何时用」那截，正文留给 loadSkill —— 描述前半段是技能内部做法，
         * 清单阶段用不上，却随每次请求全量重发。
         */
        private fun triggerHint(description: String): String {
            val d = description.ifBlank { return "（无描述）" }
            val marks = listOf("当用户说", "触发条件：", "触发：", "触发词：", "当需要", "适用于", "用于")
            for (m in marks) {
                val i = d.indexOf(m)
                if (i > 0) {
                    val head = d.substring(0, i)
                    return head.take(60) + "…" + d.substring(i)
                }
            }
            return d.take(160)
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 可用子代理清单（仅注入主代理）：让 AI 知道有哪些自定义 agent 可派发。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = entries.joinToString("\n") { entry ->
                val d = entry.definition
                val modeNote = when (d.interactionModes) {
                    setOf(SubAgentInteractionMode.ONE_SHOT) -> " [仅 one-shot]"
                    setOf(SubAgentInteractionMode.CONTINUABLE) -> " [仅 continuable]"
                    else -> ""
                }
                "- ${d.name}: ${d.description.ifBlank { "（无描述）" }}$modeNote"
            }
            val content = "可用子代理 (subagents)（格式为 名称: 何时派发；用 `task(action=\"create\", agent=\"名称\", ...)` 派发）：\n" +
                "这些子代理有各自专属的提示词、模型与工具集，任务与某个 agent 对口时优先按名派发，而不是用默认通用子代理。\n" +
                "未标注交互模式 = one-shot 与 continuable 都支持；仅声明 one-shot 的子代理完成后不能对其 send。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            val body = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }
            cached = "项目规则 (来自 ~/workspace/${file.second}，务必遵守):\n${body.trim()}"
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        // 按项目根缓存识别结果，避免每轮扫目录；项目切换时自然失效。
        @Volatile private var cachedRoot: String = "\u0000"
        @Volatile private var cachedTypes: List<ProjectType> = emptyList()

        override fun build(ctx: AgentContext): String {
            val hasWorkspace = ctx.projectRoot.isNotBlank()
            val base = "当前上下文:\n- 项目根目录: ${if (hasWorkspace) "~/workspace" else "（未选择工作区）"}"
            val envLine = "- 执行环境: ${executionEnvironment()}"
            if (!hasWorkspace) return "$base\n$envLine"
            val types = projectTypesOf(ctx.projectRoot)
            if (types.isEmpty()) return "$base\n$envLine"
            val guidance = types.joinToString("\n") { "- ${ProjectTypeDetector.guidance(it)}" }
            return "$base\n$envLine\n- 项目类型探测:\n$guidance"
        }

        /** 当前执行环境（本地容器 / 远程 SSH）+ 权限边界说明。逐轮重建，模式切换即时生效。 */
        private fun executionEnvironment(): String =
            when (executionModeHolder.currentMode()) {
                com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH ->
                    "远程 SSH 工作区（文件与命令在远程服务器上执行；工具路径按远程主机解析）"
                com.aicode.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT ->
                    "本地容器（文件与命令在容器内执行；可用 Shizuku 作用于宿主 Android，但每次需授权）"
            }

        private fun projectTypesOf(projectRoot: String): List<ProjectType> {
            if (projectRoot != cachedRoot) {
                cachedTypes = runCatching {
                    val entries = File(projectRoot).list()?.toList() ?: emptyList()
                    ProjectTypeDetector.detect(entries)
                }.getOrDefault(emptyList())
                cachedRoot = projectRoot
            }
            return cachedTypes
        }
    }

    private inner class CurrentTimeSource : PromptSource {
        override fun build(ctx: AgentContext): String = "[System] 当前本地时间: ${currentDate()}"
    }

    private inner class MemoryListSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) { return null }
            if (memories.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }

            val content = buildString {
                if (globalMemories.isNotEmpty()) {
                    append("全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))：\n")
                    globalMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
                if (projectMemories.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))：\n")
                    projectMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
            }.trimEnd()

            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()
    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val currentTimeSource = CurrentTimeSource()

    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memoriesContent = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)

        // 2. Workspace 上下文固定输出（内容已精简，无需快照占位）
        val effectiveWorkspaceContent = workspaceSource.build(agentContext)
        val timeContent = currentTimeSource.build(agentContext)

        // 3. 变量就地展开：片段里写了 {{AICODE_*}} 就替换为真实内容，并跳过下方对应的自动追加，避免重复。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memoriesContent,
            subAgentsContent,
            projectRules,
            effectiveWorkspaceContent,
            currentDate()
        )

        // 4. 组装最终提示词：把稳定不变的重头基线放最前面（享受 KV Cache），变化部分放末尾
        return buildString {
            append(staticContent)

            if (!usesStandaloneVar(rawStatic, SKILLS_VAR)) skillsContent?.let { append("\n\n"); append(it) }
            if (!usesStandaloneVar(rawStatic, SUBAGENTS_VAR)) subAgentsContent?.let { append("\n\n"); append(it) }
            if (!usesStandaloneVar(rawStatic, MEMORY_VAR)) memoriesContent?.let { append("\n\n"); append(it) }
            if (!usesStandaloneVar(rawStatic, PROJECT_RULES_VAR)) projectRules?.let { append("\n\n"); append(it) }

            if (!usesStandaloneVar(rawStatic, WORKSPACE_VAR)) {
                append("\n\n")
                append(effectiveWorkspaceContent)
            }
            if (!usesStandaloneVar(rawStatic, DATE_VAR)) {
                append("\n\n")
                append(timeContent)
            }
        }
    }

    /**
     * [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 生效时：只输出 `prompts.custom/` 顶层的数字片段，
     * 不注入任何内置来源；动态内容仅通过 `{{AICODE_*}}` 变量按需取回。
     */
    private fun buildCustomOnly(ctx: AgentContext): String {
        val fragments = PromptFragmentResolver.numberedFragments(customDir)
        if (fragments.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，系统提示词为空"
            )
            return ""
        }
        val content = fragments
            .mapNotNull { readFileOrNull(it.second)?.replace(LEADING_COMMENT, "")?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString("\n\n")
        return renderVariables(
            content,
            activeSkillsSource.build(ctx),
            memoryListSource.build(ctx),
            subAgentListSource.build(ctx),
            projectRuleSource.build(ctx),
            workspaceSource.build(ctx),
            currentDate()
        )
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理清单（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            // 与下方 definition.prompt 同样过 renderVariables：内置片段（如 70-skills-and-mcp.md）
            // 含 {{AICODE_*}} 占位符，不过这一步会让子代理**看到未展开的字面占位符**，
            // 与函数注释「{{AICODE_*}} 变量同样会展开」相矛盾。
            // subAgents 传 null：子代理不能嵌套派发（工具集已剔除 task），与下方口径一致。
            append(
                renderVariables(
                    staticRuleSource.build(agentContext),
                    activeSkillsSource.build(agentContext),
                    memoryListSource.build(agentContext),
                    null,
                    projectRuleSource.build(agentContext),
                    workspaceSource.build(agentContext),
                    currentDate()
                )
            )
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        append("当前角色 (subagent: ${definition.name})：你是一个由主代理派发的子代理，拥有独立上下文，看不到主对话历史。")
        append("专注完成本会话交给你的任务，并在最后一条回复里给出完整结论——主代理只能读到你的最后一条回复，中间过程与工具结果它看不到。\n\n")
        append(
            renderVariables(
                definition.prompt,
                activeSkillsSource.build(agentContext),
                memoryListSource.build(agentContext),
                // 子代理不能嵌套派发（工具集已剔除 task），这里刻意不传子代理清单：
                // 传了的话，definition.prompt 里的 {{AICODE_SUBAGENTS}} 会被替换成主代理的
                // 可派发清单，与上方注释「不注入可用子代理清单」自相矛盾，还可能诱导子代理
                // 去调一个它根本没有的工具。传 null，占位符展开为空串。
                null,
                projectRuleSource.build(agentContext),
                workspaceSource.build(agentContext),
                currentDate()
            )
        )

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            memoryListSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            projectRuleSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }

        append("\n\n")
        append(workspaceSource.build(agentContext))
        append("\n\n")
        append(currentTimeSource.build(agentContext))
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memories: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        date: String
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_VAR, memories.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRules.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(DATE_VAR, date)
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    internal companion object {
        /** 提示词里可渲染的 `AICODE_*` 变量名（不含花括号）。供 [PromptGuard] 校验与单测引用。 */
        val RENDERABLE_VARIABLES = setOf(
            "AICODE_SKILLS", "AICODE_MEMORY", "AICODE_SUBAGENTS",
            "AICODE_PROJECT_RULES", "AICODE_WORKSPACE", "AICODE_DATE",
        )

        /**
         * 判断片段是否**显式地**把某个变量当作独立内容块使用——即该占位符单独占据一行。
         *
         * 不能用「子串是否出现」判定：内置片段里会有**介绍占位符用法**的说明文字
         * （如「可用 `{{AICODE_SKILLS}}` 等变量取回…」），子串判定会把它误认为
         * 「用户已使用该变量」→ 本该追加到末尾的清单不再追加，反而被 `renderVariables`
         * 塞进那句说明中间（真机实测：一行说明被撑成上万字符）。
         *
         * 以「独占一行」为判据，与实际用法（片段里写一行 `{{AICODE_X}}` 让内容落在此处）一致；
         * 说明文字里的行内引用不再干扰判定。
         *
         * 无实例状态依赖，故置于 companion 便于单测直接验证。
         */
        fun usesStandaloneVar(text: String, varToken: String): Boolean =
            text.lineSequence().any { it.trim() == varToken }

        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        val BASE_FRAGMENTS = linkedMapOf(
            0 to "00-identity.md",
            5 to "05-常驻锚点.md",
            10 to "10-communication.md",
            15 to "15-project-rules.md",
            20 to "20-coding-discipline.md",
            30 to "30-comments.md",
            40 to "40-approach.md",
            45 to "45-环境基线.md",
            50 to "50-safety.md",
            60 to "60-tools-and-paths.md",
            62 to "62-工具执行与等待纪律.md",
            70 to "70-skills-and-mcp.md",
            80 to "80-self-diagnosis.md"
        )

        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_VAR = "{{AICODE_MEMORY}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
    }
}