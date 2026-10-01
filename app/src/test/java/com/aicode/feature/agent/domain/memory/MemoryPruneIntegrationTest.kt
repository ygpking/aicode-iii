package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * 端到端集成测试：真实 [GlobalMemorySource] 写盘 → [MemoryRetention] 评估 → 真删。
 *
 * 单测把 mtime 当参数注入，无法证明「真实文件系统的 mtime 能按预期被读到」；
 * 这里用真实临时目录 + 真实 `setLastModifiedTime` 跑完整链路。
 * 重点仍是那条安全底线：**不该删的绝不能删**。
 */
class MemoryPruneIntegrationTest {

    private val tmpDir = Files.createTempDirectory("memory-prune").toFile()
    private val source = GlobalMemorySource(mockk<ContainerInstaller> { every { aicodeDir } returns tmpDir })

    private val day = 24L * 60 * 60 * 1000

    private fun writeMemory(name: String, description: String, pinned: Boolean, ageDays: Long) {
        val dir = File(tmpDir, "memory").also { it.mkdirs() }
        val f = File(dir, "${MemorySource.sanitizeName(name)}.md")
        val aged = System.currentTimeMillis() - ageDays * day
        // 直接写文件并显式带上 updated：语义上「这份内容最后一次被有意义地改动」是 aged 之前。
        // mtime 一并对齐，让两个信号一致（否则测不出真正关心的那条判据）。
        f.writeText(
            MemoryParser.format(name, description, "body of $name", pinned = pinned, updatedAtMs = aged)
        )
        f.setLastModified(aged)
    }

    /** 内容派生的 updated 能被读回来，陈旧判定与之一致（不再依赖 mtime）。 */
    @Test
    fun contentDerivedUpdatedAt_drivesStaleness() {
        writeMemory("old-note", "很旧", pinned = false, ageDays = 300)
        writeMemory("fresh-note", "很新", pinned = false, ageDays = 1)

        val report = MemoryRetention.assess(source.listMemories(), System.currentTimeMillis(), staleDays = 180)
        assertEquals(listOf("old-note"), report.ages.filter { it.stale }.map { it.name })
        assertTrue(report.ages.first { it.name == "fresh-note" }.ageDays in 0..2)
    }

    /**
     * **本次修复的核心回归**：内容一字未改的「格式重写」（只把 mtime 刷新）
     * 不得把 updated 往前推——否则陈旧判据会重演 mtime 的错误。
     */
    @Test
    fun saveMemory_unchangedContent_doesNotRefreshUpdatedAt() {
        val dir = File(tmpDir, "memory").also { it.mkdirs() }
        val f = File(dir, "keep-age.md")
        val old = System.currentTimeMillis() - 300 * day
        f.writeText(MemoryParser.format("keep-age", "描述", "正文", updatedAtMs = old))
        // 模拟工具批量处理：文件被重写，mtime 刷新，但内容毫无变化
        f.setLastModified(System.currentTimeMillis())

        assertTrue(source.saveMemory("keep-age", "描述", "正文", triggers = null))

        val reloaded = source.listMemories().first { it.name == "keep-age" }
        assertEquals("内容未变，updated 不得被刷新", old, reloaded.updatedAtMs)
        val report = MemoryRetention.assess(listOf(reloaded), System.currentTimeMillis(), staleDays = 180)
        assertTrue("内容确实很旧，应仍判陈旧（mtime 的新鲜不得掩盖它）", report.isStale("keep-age"))
    }

    /** 内容真变时 updated 必须前进，否则该记忆永远清不掉。 */
    @Test
    fun saveMemory_changedContent_refreshesUpdatedAt() {
        val dir = File(tmpDir, "memory").also { it.mkdirs() }
        val f = File(dir, "revived.md")
        val old = System.currentTimeMillis() - 300 * day
        f.writeText(MemoryParser.format("revived", "描述", "旧正文", updatedAtMs = old))

        assertTrue(source.saveMemory("revived", "描述", "新正文", triggers = null))

        val reloaded = source.listMemories().first { it.name == "revived" }
        assertTrue("正文变了，updated 应该前进", reloaded.updatedAtMs > old)
        val report = MemoryRetention.assess(listOf(reloaded), System.currentTimeMillis(), staleDays = 180)
        assertFalse("刚被更新的内容不应判陈旧", report.isStale("revived"))
    }

    /**
     * 安全底线：pinned 记忆即便极旧也不判陈旧——它是「长期有效的约定」，
     * 自动/误操作删掉都是用户资产损失。
     */
    @Test
    fun pinnedOldMemory_isNeverStale_inRealFile() {
        writeMemory("conventions", "项目约定（必须常驻）", pinned = true, ageDays = 1000)

        val memories = source.listMemories()
        val conventions = memories.first { it.name == "conventions" }
        assertTrue("frontmatter pinned 应被解析出来", conventions.pinned)

        val report = MemoryRetention.assess(memories, System.currentTimeMillis(), staleDays = 1)
        assertFalse("pinned 记忆不得判陈旧", report.isStale("conventions"))
        assertEquals(0, report.staleCount)
    }

    /** 真删链路：删掉陈旧、保留新鲜与 pinned，且磁盘文件确实消失/保留。 */
    @Test
    fun deleteStale_removesOnlyStale_keepsFreshAndPinned() {
        writeMemory("stale-one", "待清理", pinned = false, ageDays = 400)
        writeMemory("fresh-one", "保留", pinned = false, ageDays = 2)
        writeMemory("pinned-old", "必须保留", pinned = true, ageDays = 400)

        val memories = source.listMemories()
        val report = MemoryRetention.assess(memories, System.currentTimeMillis(), staleDays = 180)
        val toDelete = report.ages.filter { it.stale }.map { it.name }
        assertEquals("只应删这一条", listOf("stale-one"), toDelete)
        toDelete.forEach { name -> assertTrue(source.deleteMemory(name)) }

        val left = source.listMemories().map { it.name }.sorted()
        assertEquals(listOf("fresh-one", "pinned-old"), left)
        assertNull("陈旧记忆内容应取不到", source.loadContent("stale-one"))
        assertEquals("body of fresh-one", source.loadContent("fresh-one"))
    }

    /**
     * frontmatter 的 pinned/triggers 在 saveMemory 全量覆盖时不得丢失
     * （丢失会导致该记忆从此无法被门控命中，症状隐蔽）。
     */
    @Test
    fun saveMemory_preservesPinnedAndTriggers() {
        writeMemory("keep-meta", "描述", pinned = true, ageDays = 1)
        val file = File(File(tmpDir, "memory"), "keep-meta.md")
        file.writeText(MemoryParser.format("keep-meta", "描述", "body", pinned = true, triggers = listOf("发版", "release")))

        // 全量覆盖（triggers 传 null = 保留既有）
        assertTrue(source.saveMemory("keep-meta", "新描述", "新正文", triggers = null))

        val reloaded = source.listMemories().first { it.name == "keep-meta" }
        assertTrue("pinned 不得丢失", reloaded.pinned)
        assertEquals("triggers 不得丢失", listOf("发版", "release"), reloaded.triggers)
    }
}
