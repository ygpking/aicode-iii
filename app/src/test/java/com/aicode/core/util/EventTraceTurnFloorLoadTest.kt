package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [EventTrace.loadTurnFloor] 对**真实轨迹文件**的解析契约（走生产同一路径，非等价副本）。
 *
 * 为什么要单独测「读文件」这一层：高水位一旦读错，修复会**静默失效**——回合号看着照常递增，
 * 只是依旧与上个进程重号，没有任何报错。而读错的两种典型正是这里覆盖的两条边界：
 * ① 遇到 `PROCESS START` 之后的内容属于上一代进程，不该再往前读；
 * ② 同一会话的回合号要取**最大**值，而非最后一次出现的值（后者可能更小）。
 *
 * 用 [TemporaryFolder] 造目录结构，不依赖设备。
 */
class EventTraceTurnFloorLoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, vararg lines: String) {
        File(tmp.root, name).writeText(lines.joinToString("\n") + "\n")
    }

    private fun traceLine(session: String, turn: Int, seq: Int = 1) =
        "2026-10-05 10:00:00.000  s=$session t$turn #$seq  TURN  轮次开始"

    /** 同一会话多个回合时取最大值——不能取「最后一个」，文件里回合未必递增。 */
    @Test
    fun takesMaxTurnPerSession() {
        write(
            "trace-2026-10-05.log",
            traceLine("aaaa1111", 3),
            traceLine("aaaa1111", 27),
            traceLine("aaaa1111", 5),
        )

        EventTrace.reloadTurnFloorForTest(tmp.root)

        assertEquals(
            "应取该会话出现过的最大回合号 27",
            mapOf("aaaa1111" to 27L),
            EventTrace.turnFloorForTest(),
        )
    }

    /**
     * `PROCESS START` **不**终止回读：更早进程用过的号同样不能重用。
     *
     * 这是与未收尾清点（[EventTrace] 的 `reportStaleTurns`）的关键差异——那个只看上个进程的
     * 残局，必须停在最后一个 START；而高水位要的是「历史上已占用的号」，扫全量才不撞车。
     */
    @Test
    fun doesNotStopAtProcessStart() {
        write(
            "trace-2026-10-05.log",
            traceLine("bbbb2222", 99),
            "2026-10-05 10:05:00.000  -      -    -    -  LIFECYCLE  PROCESS START",
            traceLine("bbbb2222", 4),
        )

        EventTrace.reloadTurnFloorForTest(tmp.root)

        assertEquals(
            "START 之前的 t99 同样已被占用，高水位应取 99 而非 4",
            mapOf("bbbb2222" to 99L),
            EventTrace.turnFloorForTest(),
        )
    }

    /** 多会话各自独立：一个会话的高回合号不抬高另一个会话。 */
    @Test
    fun separatesSessions() {
        write(
            "trace-2026-10-05.log",
            traceLine("cccc3333", 50),
            traceLine("dddd4444", 2),
            "2026-10-05 10:05:00.000  -      -    -    -  LIFECYCLE  PROCESS START",
        )

        EventTrace.reloadTurnFloorForTest(tmp.root)

        assertEquals(
            mapOf("cccc3333" to 50L, "dddd4444" to 2L),
            EventTrace.turnFloorForTest(),
        )
    }

    /** 无历史文件时高水位为空——首次安装不该被当成异常。 */
    @Test
    fun emptyDirYieldsNoFloor() {
        EventTrace.reloadTurnFloorForTest(tmp.root)

        assertEquals("无轨迹文件时应无任何高水位", emptyMap<String, Long>(), EventTrace.turnFloorForTest())
    }

    /** 最新文件没有内容时依能读到更早文件的高水位。 */
    @Test
    fun readsAcrossFiles() {
        write("trace-2026-10-04.log", traceLine("eeee5555", 88))
        write("trace-2026-10-05.log", traceLine("eeee5555", 3))

        EventTrace.reloadTurnFloorForTest(tmp.root)

        assertEquals(
            "跨文件应取最大值 88",
            mapOf("eeee5555" to 88L),
            EventTrace.turnFloorForTest(),
        )
    }
}
