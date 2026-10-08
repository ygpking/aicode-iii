package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.extension.ExtensionRepository
import com.aicode.feature.agent.domain.provider.ResolvedChatProvider
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [MemoryCurationService.apply] 的**作用域回归**（设备实测发现）。
 *
 * 缺陷原貌：`apply` 早期实现按 `projectRoot.isNullOrBlank()` **推导** scope，导致
 * `memory(action=apply, scope=global)` 在连着工作区时被静默忽略——文件落到了项目级目录，
 * 用户既无法写全局、也看不到任何提示。与 `save` 尊重 scope 的行为不对称（契约单边，根因 R3 同类）。
 *
 * 这里用**真实文件落位**作判据（而非只验参数传递）：作用域错了，最终就是写错文件。
 */
class MemoryCurationApplyScopeTest {

    private val globalRoot = Files.createTempDirectory("curation-global").toFile()
    private val projectRoot = Files.createTempDirectory("curation-project").toFile()

    private val containerInstaller = mockk<ContainerInstaller> {
        every { aicodeDir } returns globalRoot
    }
    private val executionModeHolder = mockk<ExecutionModeHolder> {
        every { currentMode() } returns ExecutionMode.LOCAL_PROOT
    }
    private val projectAicodeRoot = mockk<ProjectAicodeRoot>(relaxed = true)
    private val repository = MemoryRepository(
        globalMemorySource = GlobalMemorySource(containerInstaller),
        executionModeHolder = executionModeHolder,
        containerInstaller = containerInstaller,
        projectAicodeRoot = projectAicodeRoot,
        // 扩展仓库：aicodeDir 指向临时目录且无 extensions 子目录 → listExtensions 为空集，
        // 本测试不受扩展机制影响（空扩展 = 恒等变换，正是新合并逻辑要锁定的行为）。
        extensionRepository = ExtensionRepository(containerInstaller, projectAicodeRoot, mockk(relaxed = true)),
    )

    private val service = MemoryCurationService(
        memoryRepository = repository,
        messagePersistenceUseCase = mockk<MessagePersistenceUseCase>(relaxed = true),
        resolvedChatProvider = mockk<ResolvedChatProvider>(relaxed = true),
        containerInstaller = containerInstaller,
    )

    /** 写入一份回执（模拟 propose 的产物），[isMerge] 为 true 时指向同名目标。 */
    private fun writeReceipt(id: String, name: String, isMerge: Boolean = false) {
        val dir = File(File(globalRoot, "memory"), ".curation").apply { mkdirs() }
        val target = if (isMerge) "\"$name\"" else "null"
        File(dir, "$id.json").writeText(
            """
            {"id":"$id","createdAt":1,"source":"CONVERSATION",
             "items":[{"name":"$name","description":"d","content":"c","triggers":["t"],
                       "evidence":"e","isMerge":$isMerge,"targetName":$target}],
             "rejected":[]}
            """.trimIndent()
        )
    }

    private fun globalFile(name: String) = File(File(globalRoot, "memory"), "$name.md")
    private fun projectFile(name: String) = File(File(projectRoot, ".aicode/memory"), "$name.md")

    /** 核心回归：有 projectRoot 时，scope=GLOBAL 仍必须写全局。 */
    @Test
    fun globalScope_writesGlobal_evenWhenProjectRootPresent() = runBlocking {
        writeReceipt("g1", "scope-global-check")

        val result = service.apply("g1", MemoryScope.GLOBAL, projectRoot.absolutePath, null)

        assertTrue("apply 应成功：${result.error}", result.error == null)
        assertTrue("必须写到全局目录", globalFile("scope-global-check").isFile)
        assertFalse("不得写到项目级目录（这正是实测发现的缺陷）", projectFile("scope-global-check").exists())
    }

    /** 反向：scope=PROJECT 必须写项目级。 */
    @Test
    fun projectScope_writesProject() = runBlocking {
        writeReceipt("p1", "scope-project-check")

        val result = service.apply("p1", MemoryScope.PROJECT, projectRoot.absolutePath, null)

        assertTrue("apply 应成功：${result.error}", result.error == null)
        assertTrue("必须写到项目级目录", projectFile("scope-project-check").isFile)
        assertFalse("不得写到全局目录", globalFile("scope-project-check").exists())
    }

    /** 未选工作区时不得静默降级为全局——应明确报错（错误的作用域比失败更糟）。 */
    @Test
    fun projectScope_withoutProjectRoot_failsInsteadOfSilentDowngrade() = runBlocking {
        writeReceipt("p2", "no-workspace-check")

        val result = service.apply("p2", MemoryScope.PROJECT, null, null)

        assertNotNull("无工作区时应报错而非静默改写全局", result.error)
        assertFalse(globalFile("no-workspace-check").exists())
    }

    /** 覆盖已有记忆前必须备份原文（merge 会改掉目标记忆的正文）。 */
    @Test
    fun merge_backsUpOverwrittenContent() = runBlocking {
        // 先写入"第一版"作为将被覆盖的对象
        writeReceipt("m1", "merge-target")
        service.apply("m1", MemoryScope.GLOBAL, null, null)
        val before = globalFile("merge-target").readText()

        // 再写入一份合并到它的回执（内容不同）
        val dir = File(File(globalRoot, "memory"), ".curation")
        File(dir, "m2.json").writeText(
            """
            {"id":"m2","createdAt":2,"source":"CONVERSATION",
             "items":[{"name":"merge-new-name","description":"d2","content":"c2","triggers":[],
                       "evidence":"e","isMerge":true,"targetName":"merge-target"}],
             "rejected":[]}
            """.trimIndent()
        )
        val result = service.apply("m2", MemoryScope.GLOBAL, null, null)

        assertTrue("apply 应成功：${result.error}", result.error == null)
        assertTrue("合并应写入 targetName（而非 name）", globalFile("merge-target").readText() != before)
        assertFalse("不得新建 merge-new-name（is_merge 指向已有记忆）", globalFile("merge-new-name").exists())
        val backedUp = File(File(dir, "backup-m2"), "merge-target.md")
        assertTrue("必须备份被覆盖的原文", backedUp.isFile)
        assertEquals("备份应逐字节保留覆盖前的内容", before, backedUp.readText())
    }

    /** 回执不存在 / 为空时给出明确错误，不抛异常。 */
    @Test
    fun missingReceipt_reportsError() = runBlocking {
        val result = service.apply("no-such-receipt", MemoryScope.GLOBAL, null, null)
        assertNotNull(result.error)
        assertTrue(result.written.isEmpty())
    }

    /**
     * 跨作用域同名时的备份定位（设备实测发现的缺陷）。
     *
     * 缺陷原貌：备份用 `listMemories(projectRoot)` 定位文件，而该方法跨作用域合并且项目级优先——
     * `apply(scope=global)` 且存在同名项目级记忆时，它备份的是**项目级**文件，
     * 而被覆盖的**全局**原文没有备份（实测：写入落全局正确，backup 里却是项目级内容）。
     * 备份恰恰只在「覆盖已有记忆」时才有意义，此处错位等于备份在最需要时失效。
     */
    @Test
    fun globalApply_backsUpGlobalOriginal_whenProjectHasSameName() = runBlocking {
        val name = "collide-name"

        // 两处作用域各放一份**同名**记忆，内容可区分。
        writeReceipt("c1", name)
        service.apply("c1", MemoryScope.GLOBAL, projectRoot.absolutePath, null)
        globalFile(name).writeText("GLOBAL-ORIGINAL\n")
        projectFile(name).apply { parentFile?.mkdirs() }.writeText("PROJECT-ORIGINAL\n")

        // 再用一份新回执覆盖全局同名记忆。
        writeReceipt("c2", name)
        val result = service.apply("c2", MemoryScope.GLOBAL, projectRoot.absolutePath, null)

        assertTrue("apply 应成功：${result.error}", result.error == null)
        val backup = File(File(File(globalRoot, "memory"), ".curation/backup-c2"), "$name.md")
        assertTrue("必须备份被覆盖的文件", backup.isFile)
        assertEquals(
            "备份必须是**被覆盖的全局原文**，而不是另一作用域的同名文件",
            "GLOBAL-ORIGINAL\n",
            backup.readText(),
        )
        assertEquals("项目级同名文件不得被改动", "PROJECT-ORIGINAL\n", projectFile(name).readText())
    }
}
