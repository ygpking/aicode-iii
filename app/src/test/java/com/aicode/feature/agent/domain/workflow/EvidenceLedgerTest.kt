package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨回合账本的纯逻辑部分验证（先红后绿判定 + 命令分类）。
 *
 * 落盘 IO 部分依赖 Android Context，不在单测覆盖（同项目既有惯例）；此处只测判据。
 */
class EvidenceLedgerTest {

    private fun cmd(category: String, ok: Boolean, ts: Long = 0) =
        LedgerCommand(cmd = "sh gradlew $category", category = category, ok = ok, ts = ts)

    // ── 先红后绿 ────────────────────────────────────────────────────

    @Test
    fun `同类命令先失败后成功算红绿`() {
        val ledger = EvidenceLedger(
            verifyCommands = listOf(cmd("gradle-test", ok = false), cmd("gradle-test", ok = true))
        )
        assertTrue(EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands))
    }

    @Test
    fun `从未失败过不算红绿`() {
        val ledger = EvidenceLedger(
            verifyCommands = listOf(cmd("gradle-test", ok = true), cmd("gradle-test", ok = true))
        )
        assertFalse(EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands))
    }

    @Test
    fun `只失败过没有成功不算红绿`() {
        val ledger = EvidenceLedger(verifyCommands = listOf(cmd("gradle-test", ok = false)))
        assertFalse(EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands))
    }

    @Test
    fun `不同类别之间不算红绿`() {
        // lint 失败 + test 成功：不是同一验证任务，不构成「修复有针对性」的证据
        val ledger = EvidenceLedger(
            verifyCommands = listOf(cmd("lint", ok = false), cmd("gradle-test", ok = true))
        )
        assertFalse(EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands))
    }

    @Test
    fun `类别相隔的旧红不算数`() {
        // 红 → 中间夹着若干成功的同类别 → 绿：绿的前一条同类别是成功，故不算
        val ledger = EvidenceLedger(
            verifyCommands = listOf(
                cmd("gradle-test", ok = false),
                cmd("gradle-test", ok = true),
                cmd("gradle-test", ok = true),
            )
        )
        assertTrue(EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands)) // 第一对即构成红绿
    }

    @Test
    fun `空账本不算红绿`() {
        assertFalse(EvidenceLedger.hasRedThenGreenIn(emptyList()))
    }

    // ── 账本合并与清零（接线层核心语义）──────────────────────────

    @Test
    fun `裁决通过时失败计数归零`() {
        // 不归零会让诚实修复后的长期会话永久背着「已累计 N 次」标签，提示文案失真。
        val old = EvidenceLedger(consecutiveFails = 3, lastFail = LedgerFail("已修复 X", "FAIL", 1))
        val updated = EvidenceLedger.afterRecord(old, failed = false, claim = "", commands = emptyList(), now = 2, maxCommands = 20)
        assertEquals(0, updated.consecutiveFails)
    }

    @Test
    fun `裁决不通过时失败计数递增`() {
        val old = EvidenceLedger(consecutiveFails = 1)
        val updated = EvidenceLedger.afterRecord(old, failed = true, claim = "已修复 X", commands = emptyList(), now = 2, maxCommands = 20)
        assertEquals(2, updated.consecutiveFails)
        assertEquals("已修复 X", updated.lastFail?.claim)
    }

    @Test
    fun `跨 run 的红绿能判出`() {
        // 典型场景：run A 测试失败被拉回，补跑成功且收尾通过（写账本）→ 下个 run 应判出针对性。
        // 旧实现在 PASS 路径不写账本，这条永远不成立。
        val runA = listOf(cmd("gradle-test", ok = false, ts = 100))
        val ledgerA = EvidenceLedger.afterRecord(EvidenceLedger(), true, "已修复", runA, 100, 20)
        val runB = listOf(cmd("gradle-test", ok = true, ts = 200))
        val ledgerB = EvidenceLedger.afterRecord(ledgerA, false, "", runB, 200, 20)
        assertTrue(EvidenceLedger.hasRedThenGreenIn(ledgerB.verifyCommands))
    }

    @Test
    fun `重复命令去重不挤占容量`() {
        // 同一 run 内拉回多次会重复携带全量记录，不去重会把环形容量挤满。
        val cmds = listOf(cmd("gradle-test", ok = false, ts = 100), cmd("gradle-test", ok = false, ts = 100))
        val updated = EvidenceLedger.afterRecord(EvidenceLedger(), true, "x", cmds, 100, 20)
        assertEquals(1, updated.verifyCommands.size)
    }

    @Test
    fun `容量上限生效`() {
        val many = (1..30).map { cmd("gradle-test", ok = true, ts = it.toLong()) }
        val updated = EvidenceLedger.afterRecord(EvidenceLedger(), false, "", many, 1, 20)
        assertEquals(20, updated.verifyCommands.size)
    }

    // ── 命令分类（账本靠类别比对而非命令串）──────────────────────────

    @Test
    fun `gradle 测试与构建归为不同类别`() {
        assertEquals("gradle-test", CommandOutcome.categoryOf("sh gradlew :app:testUniversalDebugUnitTest"))
        assertEquals("gradle-assemble", CommandOutcome.categoryOf("sh gradlew :app:assembleUniversalDebug"))
    }

    @Test
    fun `重跑改了参数仍是同一类别`() {
        // 这是账本用类别而非命令串的原因：模型重跑几乎必改参数
        val a = CommandOutcome.categoryOf("sh gradlew :app:testUniversalDebugUnitTest --tests FooTest")
        val b = CommandOutcome.categoryOf("sh gradlew :app:testUniversalDebugUnitTest --tests BarTest --rerun-tasks")
        assertEquals(a, b)
    }

    @Test
    fun `非验证命令没有类别`() {
        assertEquals(null, CommandOutcome.categoryOf("git log --oneline"))
        assertEquals(null, CommandOutcome.categoryOf("ls -la"))
    }

    @Test
    fun `其他生态的验证命令能归类`() {
        assertEquals("pytest", CommandOutcome.categoryOf("python -m pytest tests/"))
        assertEquals("check-migrations", CommandOutcome.categoryOf("python3 scripts/check_migrations.py"))
        assertEquals("npm-test", CommandOutcome.categoryOf("npm test"))
    }
}
