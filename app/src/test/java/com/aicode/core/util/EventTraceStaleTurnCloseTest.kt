package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 进程被杀时「收尾事实」的补齐契约（[EventTrace.reportStaleTurns] 的真实路径）。
 *
 * 背景：进程被 `kill -9` 时异常处理器、`finally`、生命周期回调全不执行，应用侧**物理上写不下**
 * 死因。原先只在 LIFECYCLE 层留一行旁白，那个回合的链上没有任何终止节点——按 (会话,回合)
 * 倒查时看到的是一个戛然而止的桶，分不清「进程死了」还是「记录被截断」。
 *
 * 修复后启动时补写一条 `轮次中断/进程消失`，并把系统侧的真实死因（[android.app.ApplicationExitInfo]）
 * 一并写进去。本用例覆盖补写行为本身（死因读取依赖 Android API，属设备侧验证范畴）。
 */
class EventTraceStaleTurnCloseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(session: String, turn: Int, seq: Int, layer: String, detail: String) =
        "2026-10-05 21:00:00.$(seq.toString().padStart(3, '0'))  s=$session t$turn #$seq  $layer  $detail"

    private fun write(vararg lines: String) {
        File(tmp.root, "trace-2026-10-05.log").writeText(
            "2026-10-05 20:00:00.000  -      -    -    -  LIFECYCLE  PROCESS START\n" +
                    lines.joinToString("\n") + "\n"
        )
    }

    private fun traceText(): String = File(tmp.root, "trace-2026-10-05.log").readText()

    /** 开了没收尾的回合必须被补上终止记录，且死因写在行内。 */
    @Test
    fun appendsInterruptRecordForOpenTurn() {
        write(
            line("aaaa1111", 5, 1, "TURN", "轮次开始"),
            line("aaaa1111", 5, 2, "EVENT", "tool_started Bash id=x"),
            line("aaaa1111", 5, 3, "EVENT", "tool_finished Bash id=x 成功 结果=10字"),
        )

        EventTrace.reportStaleTurnsForTest(tmp.root, "reason=3 低内存回收")

        val text = traceText()
        assertTrue("应补写轮次中断记录", text.contains("轮次中断/进程消失"))
        assertTrue("应写明死因", text.contains("reason=3 低内存回收"))
        assertTrue("应写明该桶原有条数", text.contains("已记 3 条"))
    }

    /**
     * 补写的序号必须接在桶内最大值之后——不得与已有记录撞车。
     * 这是本次修复的核心不变量（与刚修好的「跨进程重号」同类问题）。
     */
    @Test
    fun interruptSeqFollowsBucketMax() {
        write(
            line("bbbb2222", 7, 1, "TURN", "轮次开始"),
            line("bbbb2222", 7, 9, "EVENT", "tool_started Bash id=y"),
            line("bbbb2222", 7, 12, "EVENT", "tool_finished Bash id=y 成功"),
        )

        EventTrace.reportStaleTurnsForTest(tmp.root, null)

        // 桶内最大 seq=12，补写应为 #13 且因果指向 #12
        assertTrue(
            "补写序号应接在最大值之后：${traceText().lines().last()}",
            traceText().contains("bbbb2222 t7 #13 ←#12")
        )
    }

    /** 已收尾的回合不得被误判为中断。 */
    @Test
    fun closedTurnIsNotReported() {
        write(
            line("cccc3333", 2, 1, "TURN", "轮次开始"),
            line("cccc3333", 2, 2, "EVENT", "completed"),
            line("cccc3333", 2, 3, "TURN", "轮次结束/finished 共 2 条"),
        )

        EventTrace.reportStaleTurnsForTest(tmp.root, null)

        val text = traceText()
        assertTrue("不该补写中断记录", !text.contains("轮次中断"))
        assertTrue("也不该有未收尾告警", !text.contains("未收尾"))
    }

    /** 多会话各有一个未收尾回合时，两个都要补——实测真机出现过这种情形。 */
    @Test
    fun handlesMultipleOpenSessions() {
        write(
            line("dddd4444", 1, 1, "TURN", "轮次开始"),
            line("dddd4444", 1, 2, "EVENT", "assistant_text"),
            line("eeee5555", 1, 1, "TURN", "轮次开始"),
            line("eeee5555", 1, 2, "EVENT", "assistant_text"),
        )

        EventTrace.reportStaleTurnsForTest(tmp.root, null)

        val text = traceText()
        assertTrue("dddd4444 应被补", text.contains("s=dddd4444 t1 #3"))
        assertTrue("eeee5555 应被补", text.contains("s=eeee5555 t1 #3"))
        assertTrue("告警应说明有 2 个", text.contains("有 2 个回合未收尾"))
    }

    /** 同一会话内先收尾再开新回合且未收尾：只应报新的那个。 */
    @Test
    fun onlyLatestOpenTurnPerSession() {
        write(
            line("ffff6666", 1, 1, "TURN", "轮次开始"),
            line("ffff6666", 1, 2, "TURN", "轮次结束/finished 共 1 条"),
            line("ffff6666", 2, 1, "TURN", "轮次开始"),
            line("ffff6666", 2, 2, "EVENT", "tool_started Bash id=z"),
        )

        EventTrace.reportStaleTurnsForTest(tmp.root, null)

        val text = traceText()
        assertTrue("应报 t2", text.contains("s=ffff6666 t2 #3 ←#2"))
        assertTrue("不该把已收尾的 t1 也算进来", !text.contains("s=ffff6666 t1 #3"))
    }
}
