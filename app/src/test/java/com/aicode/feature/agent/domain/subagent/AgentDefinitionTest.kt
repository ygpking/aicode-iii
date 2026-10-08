package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.model.AgentMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentDefinitionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 扩展来源的扫盘不碰其余依赖，用 relaxed mock 构造即可。 */
    private fun repo(): AgentDefinitionRepository = AgentDefinitionRepository(
        localSource = mockk(relaxed = true),
        projectSource = mockk(relaxed = true),
        configRepository = mockk(relaxed = true),
        localFileAccess = mockk(relaxed = true),
        fileAccess = mockk(relaxed = true),
        extensionRepository = mockk(relaxed = true)
    )

    private val allTools = listOf(
        "readFile", "writeFile", "editFile", "Bash", "terminal", "search",
        "task", "mcp__ctx7__query", "mcp__ctx7__resolve", "mcp__mt__open"
    )

    private fun def(
        allowed: List<String> = emptyList(),
        disallowed: List<String> = emptyList()
    ) = AgentDefinition(
        name = "a",
        description = "",
        allowedTools = allowed,
        disallowedTools = disallowed,
        prompt = "p"
    )

    /** 省略两个名单即继承全量工具，但 task 永远剔除（子代理不能嵌套派发）。 */
    @Test
    fun filter_emptyListsInheritAllExceptTask() {
        assertEquals(allTools - "task", def().filterToolNames(allTools))
    }

    @Test
    fun filter_allowlistKeepsOnlyListed() {
        assertEquals(
            listOf("readFile", "search"),
            def(allowed = listOf("readFile", "search")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_denylistRemovesListed() {
        val result = def(disallowed = listOf("Bash", "terminal")).filterToolNames(allTools)

        assertEquals(allTools - "task" - "Bash" - "terminal", result)
    }

    /** 黑名单先生效，两名单都命中的工具最终被移除。 */
    @Test
    fun filter_denyWinsOverAllow() {
        val result = def(
            allowed = listOf("readFile", "Bash"),
            disallowed = listOf("Bash")
        ).filterToolNames(allTools)

        assertEquals(listOf("readFile"), result)
    }

    @Test
    fun filter_wildcardMatchesMcpServerPrefix() {
        val result = def(disallowed = listOf("mcp__ctx7__*")).filterToolNames(allTools)

        assertEquals(
            listOf("readFile", "writeFile", "editFile", "Bash", "terminal", "search", "mcp__mt__open"),
            result
        )
    }

    @Test
    fun filter_allowlistCannotReintroduceTask() {
        assertEquals(
            listOf("readFile"),
            def(allowed = listOf("readFile", "task")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_toolNameMatchIsCaseInsensitive() {
        assertEquals(listOf("Bash"), def(allowed = listOf("bash")).filterToolNames(allTools))
    }

    /**
     * 扩展目录是宿主 File、顶层 `*.md` 逐个解析；单个坏定义（正文空/读取失败）只跳过自己。
     */
    @Test
    fun extensionDefinitions_readsTopLevelMarkdownOnly() {
        val dir = tmp.newFolder("agents")
        File(dir, "good.md").writeText("---\nname: good\ndescription: 好\n---\n正文")
        File(dir, "noBody.md").writeText("---\nname: noBody\n---\n")
        File(dir, "notes.txt").writeText("---\nname: ignored\n---\n不该被读")
        File(dir, "nested").apply { mkdirs() }
        File(dir, "nested/deep.md").writeText("---\nname: deep\n---\n嵌套不读")

        val defs = repo().extensionDefinitions(listOf(dir))

        assertEquals(listOf("good"), defs.map { it.name })
        assertEquals("好", defs.single().description)
        // filePath 为宿主绝对路径，详情页展示用
        assertTrue(defs.single().filePath!!.endsWith("agents/good.md"))
    }

    /** 目录不存在时返回空表，不抛异常——扫盘失败会同时打掉列表与可派发清单。 */
    @Test
    fun extensionDefinitions_missingDirYieldsEmpty() {
        assertTrue(repo().extensionDefinitions(listOf(File(tmp.root, "nope"))).isEmpty())
    }

    /** 无 frontmatter 的纯正文也能解析：name 兜底用文件名。 */
    @Test
    fun extensionDefinitions_fallsBackToFileName() {
        val dir = tmp.newFolder("agents")
        File(dir, "plain.md").writeText("只有正文，没有 frontmatter")

        val defs = repo().extensionDefinitions(listOf(dir))

        assertEquals("plain", defs.single().name)
        assertEquals("", defs.single().description)
    }

    /** parseText 对空正文返回 null（agent 必须有提示词）。 */
    @Test
    fun parseText_rejectsEmptyBody() {
        assertNull(AgentDefinitionParser.parseText("---\nname: x\n---\n", fallbackName = "x"))
        assertNull(AgentDefinitionParser.parseText("", fallbackName = "x"))
    }

    /** parseText 保留 frontmatter 里的模型与工具配置。 */
    @Test
    fun parseText_keepsFrontmatterFields() {
        val def = AgentDefinitionParser.parseText(
            "---\nname: r\nmodel: gpt-5\nreasoningEffort: HIGH\ntools: readFile, search\nmode: plan\n---\n正文",
            fallbackName = "fallback"
        )
        assertEquals("r", def!!.name)
        assertEquals("gpt-5", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(AgentMode.PLAN, def.mode)
    }

    /** 同名定义项目级覆盖全局，结果按名称排序。 */
    @Test
    fun mergeAll_projectOverridesGlobal() {
        val global = listOf(
            AgentDefinition(name = "researcher", description = "global", prompt = "g"),
            AgentDefinition(name = "coder", description = "global", prompt = "g")
        )
        val project = listOf(
            AgentDefinition(name = "researcher", description = "project", prompt = "p")
        )

        val merged = AgentDefinitionRepository.mergeAll(global, project)

        assertEquals(listOf("coder", "researcher"), merged.map { it.definition.name })
        val researcher = merged.first { it.definition.name == "researcher" }
        assertEquals(AgentDefinitionScope.PROJECT, researcher.scope)
        assertEquals("project", researcher.definition.description)
        assertEquals(
            AgentDefinitionScope.GLOBAL,
            merged.first { it.definition.name == "coder" }.scope
        )
    }

    /** mergeAll 产物默认标为目录来源，旧调用不受新增 origin 字段影响。 */
    @Test
    fun mergeAll_defaultsToDirectoryOrigin() {
        val merged = AgentDefinitionRepository.mergeAll(
            listOf(AgentDefinition(name = "g", description = "", prompt = "x")),
            listOf(AgentDefinition(name = "p", description = "", prompt = "x"))
        )
        assertTrue(merged.all { it.origin == AgentDefinitionOrigin.DIRECTORY })
    }

    /** 同作用域内后入者覆盖：扩展贡献排在目录定义之后，即可实现「扩展 > 目录」。 */
    @Test
    fun mergeEntries_laterEntryWinsWithinSameScope() {
        val dir = AgentDefinition(name = "reviewer", description = "from-dir", prompt = "d")
        val ext = AgentDefinition(name = "reviewer", description = "from-ext", prompt = "e")

        val merged = AgentDefinitionRepository.mergeEntries(
            global = listOf(dir to AgentDefinitionOrigin.DIRECTORY) +
                listOf(ext to AgentDefinitionOrigin.EXTENSION),
            project = emptyList()
        )

        val only = merged.single()
        assertEquals("from-ext", only.definition.description)
        assertEquals(AgentDefinitionOrigin.EXTENSION, only.origin)
        assertEquals(AgentDefinitionScope.GLOBAL, only.scope)
    }

    /** 项目级目录定义仍应压过全局扩展贡献：作用域优先于承载方式。 */
    @Test
    fun mergeEntries_projectDirectoryOverridesGlobalExtension() {
        val merged = AgentDefinitionRepository.mergeEntries(
            global = listOf(
                AgentDefinition(name = "reviewer", description = "global-ext", prompt = "e") to
                    AgentDefinitionOrigin.EXTENSION
            ),
            project = listOf(
                AgentDefinition(name = "reviewer", description = "project-dir", prompt = "d") to
                    AgentDefinitionOrigin.DIRECTORY
            )
        )

        val only = merged.single()
        assertEquals(AgentDefinitionScope.PROJECT, only.scope)
        assertEquals(AgentDefinitionOrigin.DIRECTORY, only.origin)
        assertEquals("project-dir", only.definition.description)
    }

    /** 扩展来源与目录来源同名不同代理时各自独立展示，不相互吞并。 */
    @Test
    fun mergeEntries_keepsDistinctNames() {
        val merged = AgentDefinitionRepository.mergeEntries(
            global = listOf(
                AgentDefinition(name = "alpha", description = "", prompt = "a") to AgentDefinitionOrigin.DIRECTORY,
                AgentDefinition(name = "beta", description = "", prompt = "b") to AgentDefinitionOrigin.EXTENSION
            ),
            project = emptyList()
        )
        assertEquals(listOf("alpha", "beta"), merged.map { it.definition.name })
        assertEquals(
            AgentDefinitionOrigin.EXTENSION,
            merged.first { it.definition.name == "beta" }.origin
        )
    }

    // ── 扩展来源只读：设置页已隐藏入口，仓储层对绕过 UI 的调用同样拒绝 ──

    /** 构造一个全局扩展目录里有 `reviewer.md` 的仓库，其余依赖用 relaxed mock。 */
    private fun repoWithExtensionAgent(): AgentDefinitionRepository {
        val dir = tmp.newFolder("ext-agents")
        File(dir, "reviewer.md").writeText("---\nname: reviewer\ndescription: 来自扩展\n---\n复核")
        return AgentDefinitionRepository(
            localSource = mockk(relaxed = true),
            projectSource = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            localFileAccess = mockk(relaxed = true),
            fileAccess = mockk(relaxed = true),
            extensionRepository = mockk(relaxed = true) {
                every { globalAgentDirs() } returns listOf(dir)
            }
        )
    }

    /** 编辑扩展贡献的定义：返回只读错误码，不得去写盘。 */
    @Test
    fun save_extensionOriginRejected() {
        val repo = repoWithExtensionAgent()
        val form = AgentDefinitionForm(
            name = "reviewer",
            description = "改了",
            prompt = "新正文"
        )

        assertEquals(AgentSaveError.READ_ONLY_EXTENSION, repo.save(form, AgentDefinitionScope.GLOBAL, "reviewer"))
    }

    /** 删除扩展贡献的定义：返回 false（扩展不能被单个删掉，只能卸载整个扩展）。 */
    @Test
    fun delete_extensionOriginRejected() {
        val repo = repoWithExtensionAgent()

        assertFalse(repo.delete("reviewer", AgentDefinitionScope.GLOBAL))
    }

    /**
     * 新建时名字撞上扩展定义：报 NAME_CONFLICT，不得落盘覆盖。
     * 若漏了这道，新建会拿 name 拼出容器路径写入，落成看不见的影子文件。
     */
    @Test
    fun save_newNameCollidingWithExtensionRejected() {
        val repo = repoWithExtensionAgent()

        val err = repo.save(
            AgentDefinitionForm(name = "reviewer", description = "", prompt = "正文"),
            AgentDefinitionScope.GLOBAL,
            originalName = null
        )

        assertEquals(AgentSaveError.NAME_CONFLICT, err)
    }

    /**
     * 目录定义改名：走的是「写新文件 + 删旧文件」分支。
     * 改名目标是普通目录来源时两条路径照常走通，不被只读拦截误伤。
     */
    @Test
    fun save_directoryOriginRenameStillAllowed() {
        val local = mockk<LocalDirectoryAgentSource>(relaxed = true)
        every { local.agentsRoot } returns "/aicode/agents"
        every { local.listDefinitions() } returns listOf(
            AgentDefinition(
                name = "coder",
                description = "",
                prompt = "旧正文",
                filePath = "/aicode/agents/coder.md"
            )
        )
        val access = mockk<com.aicode.feature.workspace.domain.LocalFileAccess>(relaxed = true)
        every { access.isFile(any()) } returns true
        val repo = AgentDefinitionRepository(
            localSource = local,
            projectSource = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            localFileAccess = access,
            fileAccess = mockk(relaxed = true),
            extensionRepository = mockk(relaxed = true)
        )

        val err = repo.save(
            AgentDefinitionForm(name = "coder2", description = "", prompt = "新正文"),
            AgentDefinitionScope.GLOBAL,
            originalName = "coder"
        )

        assertNull("目录定义的改名被拒了：$err", err)
        io.mockk.verify { access.writeFile(any(), any(), overwrite = true, encoding = Charsets.UTF_8) }
        io.mockk.verify { access.delete(any()) }
    }

    /**
     * 回归护栏：目录来源的定义不能被新增的只读拦截误伤——编辑与新建都必须照常走通。
     * （这是本轮改动风险最高的点：判定写错会把正常编辑/新建一并打掉。）
     */
    @Test
    fun save_directoryOriginStillAllowed() {
        val local = mockk<LocalDirectoryAgentSource>(relaxed = true)
        every { local.listDefinitions() } returns listOf(
            AgentDefinition(name = "coder", description = "", prompt = "旧正文")
        )
        val repo = AgentDefinitionRepository(
            localSource = local,
            projectSource = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            localFileAccess = mockk(relaxed = true),
            fileAccess = mockk(relaxed = true),
            extensionRepository = mockk(relaxed = true)
        )

        val edit = repo.save(
            AgentDefinitionForm(name = "coder", description = "改了", prompt = "新正文"),
            AgentDefinitionScope.GLOBAL,
            "coder"
        )
        assertNull("目录来源的编辑被拒了：$edit", edit)

        val create = repo.save(
            AgentDefinitionForm(name = "新建的", description = "", prompt = "正文"),
            AgentDefinitionScope.GLOBAL,
            null
        )
        assertNull("目录来源的新建被拒了：$create", create)
    }
}
