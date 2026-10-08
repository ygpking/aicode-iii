package com.aicode.feature.agent.domain.extension

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 扩展仓库：manifest 解析、路径越界拒绝、两级合并次序。
 * ContainerInstaller/ProjectAicodeRoot 用 mockk 指向临时目录，扩展目录用真实文件。
 */
class ExtensionRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 惰性 getter：属性初始化早于 Rule 生效，newFolder 在此时调用会抛 IllegalStateException；
    // tmp.root 由 TemporaryFolder 在测试前自动创建，测试方法执行时已就绪。
    private val globalRoot get() = File(tmp.root, "aicode/extensions")
    private val projectRoot get() = File(tmp.root, "ws/.aicode/extensions")

    private fun repo(): ExtensionRepository {
        val installer = mockk<com.aicode.feature.agent.domain.container.ContainerInstaller>(relaxed = true)
        val paicode = mockk<com.aicode.feature.workspace.domain.ProjectAicodeRoot>(relaxed = true)
        every { installer.aicodeDir } returns File(tmp.root, "aicode")
        every { paicode.forPath(any()) } returns File(tmp.root, "ws/.aicode")
        // 无参版走 current()，与 forPath 同一落点；不桩它 relaxed mock 会给出无内容的 File 替身。
        every { paicode.current() } returns File(tmp.root, "ws/.aicode")
        return ExtensionRepository(installer, paicode, mockk(relaxed = true))
    }

    private fun writeExtension(root: File, extId: String, manifest: String, vararg files: Pair<String, String>) {
        val dir = File(root, extId).apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest)
        files.forEach { (rel, content) ->
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
    }

    @Test
    fun parse_manifestAndContributeDirs() {
        writeExtension(
            globalRoot, "my-pack",
            """{"id":"my-pack","name":"My Pack","version":1,"description":"d",
               "contributes":{"skills":["skills"],"memory":["memory"]}}""",
            "skills/demo/SKILL.md" to "---\nname: demo\n---\n演示",
            "memory/demo.md" to "---\nname: demo\n---\n记忆正文"
        )
        val repo = repo()
        val entries = repo.listExtensions(null)
        assertEquals(1, entries.size)
        assertEquals("my-pack", entries[0].manifest.id)
        assertTrue(entries[0].errors.isEmpty())
        assertEquals(1, repo.skillDirs(null).size)
        assertEquals(1, repo.memoryDirs(null).size)
    }

    @Test
    fun pathTraversal_isRejected() {
        writeExtension(
            globalRoot, "evil",
            """{"id":"evil","contributes":{"skills":["../../outside"]}}""",
            "placeholder" to "x"
        )
        val repo = repo()
        val dirs = repo.skillDirs(null)
        val entry = repo.listExtensions(null).single()
        org.junit.Assert.assertEquals(
            "越界贡献应被拒（tmp.root=${tmp.root}），errors=${entry.errors}",
            emptyList<File>(),
            dirs
        )
        assertTrue(entry.errors.isNotEmpty())
    }

    @Test
    fun brokenManifest_doesNotKillOtherExtensions() {
        writeExtension(globalRoot, "broken", "not-json{{{")
        writeExtension(
            globalRoot, "good",
            """{"id":"good","contributes":{"skills":["skills"]}}""",
            "skills/s/SKILL.md" to "x"
        )
        val repo = repo()
        val entries = repo.listExtensions(null)
        assertEquals(2, entries.size)
        assertTrue(entries.single { it.manifest.id == "broken" }.errors.isNotEmpty())
        assertEquals(1, repo.skillDirs(null).size)
    }

    @Test
    fun projectExtension_listsAfterGlobal() {
        writeExtension(
            globalRoot, "g", """{"id":"g","contributes":{"memory":["memory"]}}""",
            "memory/g.md" to "g"
        )
        writeExtension(
            projectRoot, "p", """{"id":"p","contributes":{"memory":["memory"]}}""",
            "memory/p.md" to "p"
        )
        val repo = repo()
        val dirs = repo.memoryDirs("/ws/a")
        // 全局在前、项目在后：消费端按序合并即「项目覆盖全局」
        assertEquals(2, dirs.size)
        assertTrue(dirs[0].path.contains("aicode/extensions"))
        assertTrue(dirs[1].path.contains(".aicode/extensions"))
    }

    @Test
    fun agentsContribution_parsedAndAggregated() {
        writeExtension(
            globalRoot, "agent-pack",
            """{"id":"agent-pack","contributes":{"agents":["agents"]}}""",
            "agents/reviewer.md" to "---\nname: reviewer\ndescription: 复核\n---\n复核正文"
        )
        val repo = repo()
        val dirs = repo.agentDirs(null)
        assertEquals(1, dirs.size)
        assertTrue(dirs[0].path.endsWith("agent-pack/agents"))
        assertEquals(1, repo.globalAgentDirs().size)
    }

    @Test
    fun agentsPathTraversal_isRejected() {
        writeExtension(
            globalRoot, "evil-agent",
            """{"id":"evil-agent","contributes":{"agents":["../../../etc"]}}"""
        )
        val repo = repo()
        assertEquals(emptyList<File>(), repo.globalAgentDirs())
        assertTrue(repo.listExtensions(null).single().errors.isNotEmpty())
    }

    @Test
    fun projectAgentDirs_areScopedSeparately() {
        writeExtension(
            globalRoot, "g", """{"id":"g","contributes":{"agents":["agents"]}}""",
            "agents/g.md" to "---\nname: g\n---\n正"
        )
        writeExtension(
            projectRoot, "p", """{"id":"p","contributes":{"agents":["agents"]}}""",
            "agents/p.md" to "---\nname: p\n---\n正"
        )
        val repo = repo()
        // 两级分开取：消费端按「项目 > 全局」合并，仓库层不预幂等
        assertEquals(1, repo.globalAgentDirs().size)
        assertEquals(1, repo.projectAgentDirs().size)
        assertTrue(repo.globalAgentDirs()[0].path.contains("aicode/extensions"))
        assertTrue(repo.projectAgentDirs()[0].path.contains(".aicode/extensions"))
        // 兼容旧包：manifest 不带 agents 字段时不贡献该类，且不影响其它贡献解析
        assertEquals(2, repo.agentDirs("/ws/a").size)
    }

    /**
     * 回归护栏：项目级扩展根必须是 `<.aicode>/extensions`，不能直接拿 `<.aicode>` 当扩展根。
     * 曾经的 `currentProjectRoot()` 少了 extensions 后缀，导致项目级扩展的技能/提示词/记忆全扫不到。
     */
    @Test
    fun projectDirAccessors_useExtensionsSuffix() {
        writeExtension(
            projectRoot, "p",
            """{"id":"p","contributes":{"skills":["skills"],"memory":["memory"]}}""",
            "skills/s/SKILL.md" to "---\nname: s\n---\n正"
        )
        val repo = repo()
        val skillDirs = repo.projectSkillDirs()
        assertEquals(1, skillDirs.size)
        assertTrue(skillDirs[0].path.endsWith(".aicode/extensions/p/skills"))
        assertEquals(1, repo.projectMemoryDirs().size)
        assertTrue(repo.projectMemoryDirs()[0].path.endsWith(".aicode/extensions/p/memory"))
    }

    @Test
    fun mcpServers_parsedPerExtension() {
        writeExtension(
            globalRoot, "mcp-pack",
            """{"id":"mcp-pack","contributes":{"mcp":"mcp.json"}}""",
            "mcp.json" to """{"srv":{"command":"python3","args":["-m","srv"]}}"""
        )
        val repo = repo()
        val servers = repo.mcpServers(null)
        assertEquals(1, servers.size)
        assertEquals("srv", servers[0].name)
        assertEquals(JsonPrimitive("python3"), servers[0].config["command"])
        // 作用域跟着扩展走，供消费端标注与只读判定。
        assertEquals(ExtensionScope.GLOBAL, servers[0].scope)
    }

    @Test
    fun noExtensionsDir_returnsEmpty() {
        val repo = repo()
        assertTrue(repo.listExtensions(null).isEmpty())
        assertTrue(repo.skillDirs(null).isEmpty())
        assertTrue(repo.agentDirs(null).isEmpty())
        assertTrue(repo.mcpServers(null).isEmpty())
    }
}
