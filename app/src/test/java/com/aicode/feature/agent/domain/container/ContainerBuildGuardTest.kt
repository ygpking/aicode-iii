package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerBuildGuardTest {

    @Test
    fun cargoBuildGetsJobLimit() {
        val r = ContainerBuildGuard.guard("cd /root/work/app && cargo build --release")
        assertTrue(r.rewritten)
        assertTrue(r.command.startsWith("CARGO_BUILD_JOBS=2 "))
        assertTrue(r.command.endsWith("cargo build --release"))
    }

    @Test
    fun cargoTestNeedsGuarding() {
        // 实测崩溃时正是在跑 cargo test（debug 产物 806MB）
        val r = ContainerBuildGuard.guard("cargo test -- --report-time")
        assertTrue(r.rewritten)
        assertTrue(r.command.contains("CARGO_BUILD_JOBS=2"))
    }

    @Test
    fun explicitJobsIsRespected() {
        // 用户/模型已显式指定并行度：不覆盖其明确选择
        val cmd = "CARGO_BUILD_JOBS=8 cargo build"
        val r = ContainerBuildGuard.guard(cmd)
        assertFalse(r.rewritten)
        assertEquals(cmd, r.command)
    }

    @Test
    fun cargoFlagJobsIsRespected() {
        val cmd = "cargo build -j 4"
        val r = ContainerBuildGuard.guard(cmd)
        assertFalse(r.rewritten)
        assertEquals(cmd, r.command)
    }

    @Test
    fun cargoInstallIsNotTouched() {
        // cargo install 不支持 -j，注入会导致命令报错
        val cmd = "cargo install cargo-ndk"
        val r = ContainerBuildGuard.guard(cmd)
        assertFalse(r.rewritten)
    }

    @Test
    fun gradleGetsMaxWorkers() {
        val r = ContainerBuildGuard.guard("./gradlew assembleDebug")
        assertTrue(r.rewritten)
        assertTrue(r.command.contains("--max-workers=2"))
    }

    @Test
    fun gradleWithMaxWorkersIsRespected() {
        val cmd = "./gradlew assembleRelease --max-workers=8"
        assertFalse(ContainerBuildGuard.guard(cmd).rewritten)
    }

    @Test
    fun makeGetsJobLimit() {
        val r = ContainerBuildGuard.guard("make -C build")
        assertTrue(r.rewritten)
        assertTrue(r.command.endsWith("-j2"))
    }

    @Test
    fun makeWithJobsIsRespected() {
        assertFalse(ContainerBuildGuard.guard("make -j8").rewritten)
    }

    @Test
    fun nodeBuildGetsHeapLimit() {
        val r = ContainerBuildGuard.guard("npx tsc --noEmit")
        assertTrue(r.rewritten)
        assertTrue(r.command.contains("--max-old-space-size=2048"))
    }

    @Test
    fun ordinaryCommandIsUntouched() {
        val cmd = "ls -la /root && git status"
        val r = ContainerBuildGuard.guard(cmd)
        assertFalse(r.rewritten)
        assertEquals(cmd, r.command)
    }

    @Test
    fun guardNoteExplainsWhy() {
        val r = ContainerBuildGuard.guard("cargo build")
        assertTrue(r.note!!.contains("并行度"))
        assertTrue(r.note!!.contains("内存计入 App"))
    }
}
