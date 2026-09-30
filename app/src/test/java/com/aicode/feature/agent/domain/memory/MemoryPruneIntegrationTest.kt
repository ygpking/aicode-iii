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
        assertTrue(source.saveMemory(name, description, "body of $name", triggers = null))
        if (pinned) {
            // pinned 通过 frontmatter 表达，直接改文件内容
            val f = File(File(tmpDir, "memory"), "${MemorySource.sanitizeName(name)}.md")
            f.writeText(MemoryParser.format(name, description, "body of $name", pinned = true))
        }
        val f = File(File(tmpDir, "memory"), "${MemorySource.sanitizeName(name)}.md")
        f.setLastModified(System.currentTimeMillis() - ageDays * day + 3000)
    }

    /** 真实 mtime 能被读回来，陈旧判定与文件系统一致。 */
    @Test
    fun realFileMtime_drivesStaleness() {
        writeMemory("old-note", "很旧", pinned = false, ageDays = 300)
        writeMemory("fresh-note", "很新", pinned = false, ageDays = 1)

        val report = MemoryRetention.assess(source.listMemories(), System.currentTimeMillis(), staleDays = 180)
        assertEquals(listOf("old-note"), report.ages.filter { it.stale }.map { it.name })
        assertTrue(report.ages.first { it.name == "fresh-note" }.ageDays in 0..2)
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
