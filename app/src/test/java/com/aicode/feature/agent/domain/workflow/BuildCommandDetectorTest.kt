package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

/** 构建类命令识别：必须识别真实构建，且不把只读调用误判成构建。 */
class BuildCommandDetectorTest {

    @Test
    fun detectsRealBuildCommands() {
        // 本仓库的验证命令（真实用例）
        assertEquals(true, BuildCommandDetector.isBuildCommand("sh gradlew :app:testUniversalDebugUnitTest"))
        assertEquals(true, BuildCommandDetector.isBuildCommand("sh gradlew :app:assembleUniversalDebug"))
        assertEquals(true, BuildCommandDetector.isBuildCommand("./gradlew assembleRelease"))
        // 前缀组合命令（实测最常见形态）
        assertEquals(
            true,
            BuildCommandDetector.isBuildCommand(
                "cd ~/workspace && unset ANDROID_HOME; export ANDROID_HOME=/root/sdk-arm64; sh gradlew test"
            ),
        )
        // workbuddy 项目：脚本内部跑 gradlew（项目记忆里的实测教训）
        assertEquals(true, BuildCommandDetector.isBuildCommand("python3 scripts/build-all.sh"))
        // 其它语言栈
        assertEquals(true, BuildCommandDetector.isBuildCommand("cd core-go && go build ./..."))
        assertEquals(true, BuildCommandDetector.isBuildCommand("go test ./core-go/..."))
        assertEquals(true, BuildCommandDetector.isBuildCommand("npm run build"))
        assertEquals(true, BuildCommandDetector.isBuildCommand("cargo test"))
        assertEquals(true, BuildCommandDetector.isBuildCommand("make -j2"))
    }

    @Test
    fun doesNotFlagReadOnlyCommands() {
        // 这四类正是今日真机日志里被跨会话阻塞的命令，识别错会把收益全部吃掉
        assertEquals(false, BuildCommandDetector.isBuildCommand("ls -lt ~/workspace/workbuddy-android/core-go"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("grep -rn \"foo\" app/src"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("du -sh ~/workspace/workbuddy-android/core-go"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("cat /tmp/apk/smali/a/n2.smali"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("rg --line-number 'ToolMutation' app/src"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("which java && java -version"))
    }

    @Test
    fun toolNameAloneIsNotEnough() {
        // 只有工具名、没有构建动作 → 不算构建（避免 go version / npm --version 误判）
        assertEquals(false, BuildCommandDetector.isBuildCommand("go version"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("npm --version"))
    }

    @Test
    fun dryRunIsNotBuild() {
        // make -n / --dry-run 只打印命令不执行，不启动构建进程
        assertEquals(false, BuildCommandDetector.isBuildCommand("make -n"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("make --dry-run"))
        assertEquals(false, BuildCommandDetector.isBuildCommand("gmake -n"))
        // 真构建仍要识别
        assertEquals(true, BuildCommandDetector.isBuildCommand("make"))
        assertEquals(true, BuildCommandDetector.isBuildCommand("make -j2"))
    }

    @Test
    fun handlesBlankAndNull() {
        assertEquals(false, BuildCommandDetector.isBuildCommand(null))
        assertEquals(false, BuildCommandDetector.isBuildCommand(""))
        assertEquals(false, BuildCommandDetector.isBuildCommand("   "))
    }

    @Test
    fun mentionInSearchIsNotBuild() {
        // 搜「gradle」这个词本身不是构建（误判方向安全，但成本仍应避免）
        assertEquals(false, BuildCommandDetector.isBuildCommand("grep -rn gradle app/build.gradle.kts"))
    }

    /**
     * 与 [CommandOutcome.isVerifyCommand] 的**分岔哨兵**。
     *
     * 两者各有一套同构的「切段 + 剥包装器」逻辑，但判据表不同（两边 wrapper 集已分岔过：
     * 本文件含 `command`、那边含 `dash`），**结果不同是允许的，无声漂移不允许**。
     * 这三条钉住当前分岔点：改任一侧的切段/剥壳规则时，另一侧的断言会红，
     * 提醒回来看差异是有意的还是漏同步。
     */
    @Test
    fun divergesFromVerifyDetectorOnPurpose() {
        // 真跑验证、但不启动高开销构建（门禁脚本只是跑 python，不需串行）
        assertEquals(false, BuildCommandDetector.isBuildCommand("cd x && bash scripts/verify-all.sh"))
        // 两边都认：既会构建也构成验证凭证
        assertEquals(true, BuildCommandDetector.isBuildCommand("sh gradlew test"))
        // 启动构建、但不构成验证凭证（build-all 不是门禁脚本形态）
        assertEquals(true, BuildCommandDetector.isBuildCommand("python3 scripts/build-all.sh"))
    }
}
