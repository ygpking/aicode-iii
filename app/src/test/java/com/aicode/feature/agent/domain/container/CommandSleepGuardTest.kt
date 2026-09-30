package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSleepGuardTest {

    @Test
    fun `拦截信息显示原始数字而非 Double 形式`() {
        val reason = CommandSleepGuard.blockReason("sleep 40")
        assertNotNull(reason)
        assertTrue(reason!!.contains("检测到独立 sleep 40（40s）"))
        assertFalse(reason.contains("40.0"))
    }

    @Test
    fun `带单位拦截信息不重复小数点`() {
        val reason = CommandSleepGuard.blockReason("sleep 45s")
        assertNotNull(reason)
        assertTrue(reason!!.contains("检测到独立 sleep 45s（45s）"))
        assertFalse(reason.contains("45.0"))
    }

    @Test
    fun `长 sleep 拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 45"))
    }

    @Test
    fun `超长 sleep 拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 240; echo waited"))
    }

    @Test
    fun `复合命令中段 sleep 拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("cd ~/workspace && sleep 45; gh run view"))
    }

    @Test
    fun `sleep m 单位换算拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 1m"))
    }

    @Test
    fun `sleep h 单位换算拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 1h"))
    }

    @Test
    fun `30 秒阈值内放行`() {
        assertNull(CommandSleepGuard.blockReason("sleep 30"))
    }

    @Test
    fun `短 sleep 放行`() {
        assertNull(CommandSleepGuard.blockReason("sleep 3"))
    }

    @Test
    fun `小数 sleep 放行`() {
        assertNull(CommandSleepGuard.blockReason("sleep 0.5"))
    }

    @Test
    fun `for 循环内短重试放行`() {
        assertNull(
            CommandSleepGuard.blockReason(
                """for i in 1 2 3; do out=$(timeout 25 ssh -T git@github.com 2>&1); case "${'$'}out" in *successfully*) break;; esac; sleep 3; done"""
            )
        )
    }

    @Test
    fun `echo 引用文本中的 sleep 不误伤`() {
        assertNull(CommandSleepGuard.blockReason("""echo "sleep 60""""))
    }

    @Test
    fun `echo 行内 sleep 不误伤`() {
        assertNull(CommandSleepGuard.blockReason("echo sleep 60"))
    }

    @Test
    fun `引号内带分隔符的 sleep 不误伤`() {
        assertNull(CommandSleepGuard.blockReason("""echo "a; sleep 60""""))
    }

    @Test
    fun `单引号内带分隔符的 sleep 不误伤`() {
        assertNull(CommandSleepGuard.blockReason("echo 'a; sleep 60'"))
    }

    @Test
    fun `多行命令行首 sleep 拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("cd /tmp\nsleep 60"))
    }

    @Test
    fun `显式 s 单位拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 45s"))
    }

    @Test
    fun `d 单位换算拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("sleep 2d"))
    }

    @Test
    fun `双竖线分隔 sleep 拦截`() {
        assertNotNull(CommandSleepGuard.blockReason("foo || sleep 60"))
    }

    @Test
    fun `普通命令放行`() {
        assertNull(CommandSleepGuard.blockReason("ls -la && git status"))
    }
}