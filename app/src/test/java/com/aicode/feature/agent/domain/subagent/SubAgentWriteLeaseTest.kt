package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentWriteLeaseTest {

    /** 用真实事件总线驱动「活跃集合」，租约的失效完全依赖它。 */
    private fun busWithActive(vararg ids: String) = SubAgentEventBus().also { bus ->
        ids.forEach { id ->
            bus.emit(SubAgentEvent(subSessionId = id, parentSessionId = "p", type = SubAgentEventType.SPAWNED))
        }
    }

    @Test
    fun noDeclarationMeansNoRestriction() {
        val lease = SubAgentWriteLease(busWithActive("s1"))
        // 未声明租约的会话：pathsFor 返回 null，写工具据此不做任何限制
        assertNull(lease.pathsFor("s1"))
        assertTrue(lease.isWithinLease("s1", "anything.kt"))
        assertTrue(lease.findConflict(listOf("a.kt")).isEmpty())
    }

    @Test
    fun conflictingPathIsDetected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        assertTrue(lease.findConflict(listOf("src/a.kt")).contains("s1"))
    }

    @Test
    fun prefixOverlapIsDetected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src"))
        // 目录租约覆盖其下文件
        assertTrue(lease.findConflict(listOf("src/a.kt")).contains("s1"))
    }

    @Test
    fun disjointPathsDoNotConflict() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        assertTrue(lease.findConflict(listOf("src/b.kt")).isEmpty())
    }

    @Test
    fun dotDotIsResolvedBeforeComparison() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/lib"))
        // `src/lib/../lib` 归一后与 `src/lib` 相同，应判冲突
        assertTrue(lease.findConflict(listOf("src/lib/../lib")).contains("s1"))
    }

    @Test
    fun wholeWorkspaceConflictsWithEverything() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("*"))
        assertTrue(lease.findConflict(listOf("anything.kt")).contains("s1"))
    }

    @Test
    fun leaseExpiresWhenSubAgentIsNoLongerActive() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        // 子代理结束（离开活跃集合）后租约应自动失效，不阻塞后续任务
        bus.emit(SubAgentEvent(subSessionId = "s1", parentSessionId = "p", type = SubAgentEventType.COMPLETED))
        assertTrue(lease.findConflict(listOf("src/a.kt")).isEmpty())
    }

    @Test
    fun explicitReleaseDropsLease() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src/a.kt"))
        lease.release("s1")
        assertNull(lease.pathsFor("s1"))
    }

    @Test
    fun writeOutsideLeaseIsRejected() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("src"))
        assertTrue(lease.isWithinLease("s1", "src/a.kt"))
        assertFalse(lease.isWithinLease("s1", "other/a.kt"))
    }

    @Test
    fun blankLeaseIsIgnored() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("  ", ""))
        assertNull(lease.pathsFor("s1"))
        assertEquals(0, lease.findConflict(listOf("a.kt")).size)
    }

    @Test
    fun tildeDeclarationMatchesContainerAbsolutePath() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // 声明用 ~/ 形式，目标用容器绝对路径（子代理复用 readFile 返回路径的场景）
        lease.acquire("s1", listOf("~/workspace/app"))
        assertTrue(lease.isWithinLease("s1", "/root/workspace/app/AgentTool.kt"))
        assertTrue(lease.isWithinLease("s1", "/root/workspace/app"))
        assertFalse(lease.isWithinLease("s1", "/root/workspace/other/File.kt"))
    }

    @Test
    fun absoluteDeclarationMatchesTildePath() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // 反向：声明容器绝对路径，目标用 ~/ 形式
        lease.acquire("s1", listOf("/root/workspace/app"))
        assertTrue(lease.isWithinLease("s1", "~/workspace/app/File.kt"))
        assertFalse(lease.isWithinLease("s1", "~/workspace/other/File.kt"))
    }

    @Test
    fun tildeConflictIsDetectedAcrossForms() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("~/workspace/app"))
        // 另一子代理声明绝对路径，两形式应视为同一目录而判冲突
        assertTrue(lease.findConflict(listOf("/root/workspace/app/a.kt")).contains("s1"))
        assertTrue(lease.findConflict(listOf("~/workspace/app")).contains("s1"))
    }

    @Test
    fun relativeDeclarationMatchesContainerAbsolutePath() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // 声明写成相对工作区（常见），目标用工具落盘的绝对路径——2026-10-13 实测被误拒的情形
        lease.acquire("s1", listOf("app/src/test/Foo.kt"))
        assertTrue(lease.isWithinLease("s1", "/root/workspace/app/src/test/Foo.kt"))
        assertFalse(lease.isWithinLease("s1", "/root/workspace/app/src/other/Bar.kt"))
    }

    @Test
    fun relativeDeclarationMatchesTildeTarget() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("app/src/test"))
        assertTrue(lease.isWithinLease("s1", "~/workspace/app/src/test/Foo.kt"))
        assertFalse(lease.isWithinLease("s1", "~/workspace/app/src/main/Bar.kt"))
    }

    @Test
    fun relativeDeclarationConflictIsDetectedAcrossForms() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf("app/src/test/Foo.kt"))
        // 相对、~、绝对三种写法应归一到同一路径而判冲突
        assertTrue(lease.findConflict(listOf("/root/workspace/app/src/test/Foo.kt")).contains("s1"))
        assertTrue(lease.findConflict(listOf("~/workspace/app/src/test/Foo.kt")).contains("s1"))
        assertTrue(lease.findConflict(listOf("app/src/test/Foo.kt")).contains("s1"))
    }

    @Test
    fun parentRelativeDeclarationDoesNotEscapeWorkspace() {
        val bus = busWithActive("s1", "s2")
        // `..` 开头声明挂上工作区前缀后归一结果会跳出工作区（/root、/root/.aicode），
        // 等于把家目录整棵树（含凭据目录）纳入租约——此类声明不得获得工作区外的覆盖面
        val lease = SubAgentWriteLease(bus)
        lease.acquire("s1", listOf(".."))
        assertFalse(lease.isWithinLease("s1", "/root/.aicode/credentials/x"))
        assertFalse(lease.isWithinLease("s1", "/root/.aicode/memory/a.md"))
        assertFalse(lease.isWithinLease("s1", "/root/workspace/app/x.kt"))

        val lease2 = SubAgentWriteLease(bus)
        lease2.acquire("s2", listOf("../.aicode"))
        assertFalse(lease2.isWithinLease("s2", "/root/.aicode/credentials/x"))
    }

    @Test
    fun parentRelativeTargetDoesNotEscapeWorkspace() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // 相对声明 + 工作区外的绝对目标不得被判覆盖（钉住上一笔的「相对路径锚定工作区」，
        // 非本次新增的边界检查：本用例在 main 上就红）
        lease.acquire("s1", listOf("app/src"))
        assertTrue(lease.isWithinLease("s1", "/root/workspace/app/src/Foo.kt"))
        assertFalse(lease.isWithinLease("s1", "/root/.aicode/credentials/x"))
    }

    @Test
    fun wholeWorkspaceDeclarationLeavesPathsUnrestricted() {
        val bus = busWithActive("s1", "s2", "s3")
        // `*` / `.` 的语义是「该子代理写路径不受限制」：工作区内外均可写
        // 本用例钉住用户 2026-10-13 拍板的宽口径；若日后改回收窄，本用例应变红并同步改断言
        val star = SubAgentWriteLease(bus)
        star.acquire("s1", listOf("*"))
        assertTrue(star.isWithinLease("s1", "/root/workspace/app/x.kt"))
        assertTrue(star.isWithinLease("s1", "/etc/passwd"))
        assertTrue(star.isWithinLease("s1", "/root/.aicode/credentials/x"))

        val dot = SubAgentWriteLease(bus)
        dot.acquire("s2", listOf("."))
        assertTrue(dot.isWithinLease("s2", "/root/workspace/app/x.kt"))
        assertTrue(dot.isWithinLease("s2", "/root/.aicode/credentials/x"))

        val rel = SubAgentWriteLease(bus)
        rel.acquire("s3", listOf("*"))
        assertTrue(rel.isWithinLease("s3", "app/src/Foo.kt"))
    }

    @Test
    fun impossibleRelativePathsAreRejectedAtCreateTime() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // `..` 类声明会被登记成一条覆盖不了任何东西的哑租约，把该子代理的合法写入也全锁死；
        // 创建期就应拦下并指向 `*`
        assertEquals(listOf(".."), lease.impossibleRelativePaths(listOf("..")))
        assertEquals(listOf("../.aicode"), lease.impossibleRelativePaths(listOf("../.aicode")))
        assertEquals(listOf("../../etc", "app/../.."), lease.impossibleRelativePaths(listOf("../../etc", "app/../..")))
        // 混合时只揪出越界项
        assertEquals(listOf(".."), lease.impossibleRelativePaths(listOf("app/src", "..", "~/workspace/x", "*")))
    }

    @Test
    fun normalPathsAreNotFlaggedAsImpossible() {
        val bus = busWithActive("s1")
        val lease = SubAgentWriteLease(bus)
        // 防误报：所有正常写法都不得被判为越界。
        // 后三条是「绝对/`~` 形态且含 `..`」的探针。逐条变异复算（删掉某个前置 guard 看哪条探针被误报）：
        //   `/../x`、`/etc/../..` -> 钉绝对 guard；`~/../../x` -> 钉 `~` guard。
        // 本链的整工作区 guard（只影响相对声明的过滤）当前无探针：`*`/`.` 挂前缀后仍锚在
        // 工作区内，当前成员集下不可观测（`WHOLE_WORKSPACE` 已不含空串）。
        // 但它不是冗余：若日后往里加 `..` 类成员，该 guard 即变必要（复核实测）。
        // （整工作区**语义**本身有覆盖：`wholeWorkspaceConflictsWithEverything` 钉查冲突短路、
        //  `wholeWorkspaceDeclarationLeavesPathsUnrestricted` 钉写工具放行。）
        val valid = listOf(
            "app/src/test/Foo.kt", "app/src", "src", "app/./src", "app/src/../src/Foo.kt",
            "./app/src", "a", "app/src/test/",
            "~/workspace/app", "~/.aicode/x", "/root/workspace/app", "/etc/hosts", "*", ".",
            "/../x", "/etc/../..", "~/../../x"
        )
        assertEquals(emptyList<String>(), lease.impossibleRelativePaths(valid))
    }
}
