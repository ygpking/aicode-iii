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
        return ExtensionRepository(installer, paicode)
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
        assertTrue(repo.skillDirs(null).isEmpty())
        val entry = repo.listExtensions(null).single()
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
    fun mcpServers_parsedPerExtension() {
        writeExtension(
            globalRoot, "mcp-pack",
            """{"id":"mcp-pack","contributes":{"mcp":"mcp.json"}}""",
            "mcp.json" to """{"srv":{"command":"python3","args":["-m","srv"]}}"""
        )
        val repo = repo()
        val servers = repo.mcpServers(null)
        assertEquals(1, servers.size)
        assertEquals("srv", servers[0].first)
        val cfg = servers[0].second as JsonObject
        assertEquals(JsonPrimitive("python3"), cfg["command"])
    }

    @Test
    fun noExtensionsDir_returnsEmpty() {
        val repo = repo()
        assertTrue(repo.listExtensions(null).isEmpty())
        assertTrue(repo.skillDirs(null).isEmpty())
        assertTrue(repo.mcpServers(null).isEmpty())
    }
}
