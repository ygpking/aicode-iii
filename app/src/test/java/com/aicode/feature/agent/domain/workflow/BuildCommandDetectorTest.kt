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
}
