package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputCompressorTest {

    @Test
    fun foldsConsecutiveIdenticalLines() {
        val text = (1..10).joinToString("\n") { "Downloading package" }
        val r = ToolOutputCompressor.compress(text)
        assertTrue(r.linesFolded > 0)
        assertTrue(r.text.contains("...[上一行重复 10 次]..."))
        assertTrue(r.text.lines().size < 10)
    }

    @Test
    fun keepsShortRepetitionBelowThreshold() {
        val text = "line\nline\nother"
        val r = ToolOutputCompressor.compress(text)
        assertEquals(0, r.linesFolded)
        assertEquals(text, r.text)
    }

    @Test
    fun stripsAnsiCodes() {
        val text = "\u001B[32mok\u001B[0m normal"
        val r = ToolOutputCompressor.compress(text)
        assertTrue(r.ansiStripped)
        assertEquals("ok normal", r.text)
    }

    @Test
    fun collapsesCarriageReturnProgress() {
        val text = "10%\r50%\r100% done"
        val r = ToolOutputCompressor.compress(text)
        assertEquals("100% done", r.text)
    }

    @Test
    fun preservesDistinctLines() {
        val text = "a\nb\nc\nd"
        val r = ToolOutputCompressor.compress(text)
        assertEquals(text, r.text)
        assertFalse(r.ansiStripped)
        assertEquals(0, r.linesFolded)
    }

    @Test
    fun emptyInputIsSafe() {
        val r = ToolOutputCompressor.compress("")
        assertEquals("", r.text)
        assertEquals(0, r.linesFolded)
        assertFalse(r.ansiStripped)
    }

    /**
     * 核心等价性断言：逐行喂入 [LineFolder] 必须与一次性 [ToolOutputCompressor.compress]
     * 得到**逐字相同**的输出。两条链路（命令执行期限幅之前 vs 入库整体去噪）若不一致，
     * 同一份输出在不同路径下会呈现不同内容，排查时无法互相对照。
     */
    @Test
    fun lineFolder_matchesWholeTextCompress_onEverySample() {
        val samples = listOf(
            "",
            "a\nb\nc",
            (1..10).joinToString("\n") { "Downloading package" },
            "line\nline\nother",
            "\u001B[32mok\u001B[0m normal",
            "10%\r50%\r100% done",
            (1..5).joinToString("\n") { "same" } + "\nafter",
            "x".repeat(50) + "\n" + "x".repeat(50) + "\n" + "x".repeat(50),
        )
        for (text in samples) {
            val whole = ToolOutputCompressor.compress(text)
            val folder = LineFolder()
            val streamed = buildList {
                if (text.isNotEmpty()) text.split('\n').forEach { addAll(folder.feed(it)) }
                addAll(folder.finish())
            }.joinToString("\n")

            assertEquals("输出不一致: ${text.take(30)}", whole.text, streamed)
            assertEquals("折叠计数不一致: ${text.take(30)}", whole.linesFolded, folder.linesFolded)
            assertEquals("ANSI 标记不一致: ${text.take(30)}", whole.ansiStripped, folder.ansiStripped)
        }
    }

    /** 跨边界的长重复段：重复行末端不落在流末尾时，前一段必须及时吐出，不能吞着等下一行。 */
    @Test
    fun lineFolder_flushesBlockBeforeNextDistinctLine() {
        val folder = LineFolder()
        val emitted = buildList {
            addAll(folder.feed("noise"))
            addAll(folder.feed("noise"))
            addAll(folder.feed("noise"))
            addAll(folder.feed("done")) // 触发前一段吐出
            addAll(folder.finish())
        }
        assertEquals(listOf("noise", "...[上一行重复 3 次]...", "done"), emitted)
        assertEquals(2, folder.linesFolded)
    }

    /** 不足阈值的重复不得折叠；finish 要把尾部 pending 吐出，不能随流结束丢掉最后一行。 */
    @Test
    fun lineFolder_keepsShortTailAndFlushesAtFinish() {
        val folder = LineFolder()
        val emitted = buildList {
            addAll(folder.feed("a"))
            addAll(folder.feed("b"))
            addAll(folder.finish())
            addAll(folder.finish()) // 可重复调用
        }
        assertEquals(listOf("a", "b"), emitted)
        assertEquals(0, folder.linesFolded)
    }

    /** 去噪说明文案：未落盘时不得声称「可回读落盘文件」，否则模型会去找不存在的文件。 */
    @Test
    fun foldNote_omitsSpillHintWhenNothingSpilled() {
        val notSpilled = ToolOutputCompressor.foldNote(5, ansiStripped = false, spilledPath = null)
        assertTrue(notSpilled.contains("折叠重复行 5 行"))
        assertFalse(notSpilled.contains("可回读落盘文件"))

        val spilled = ToolOutputCompressor.foldNote(5, true, "/root/.aicode/tool-output/x.log")
        assertTrue(spilled.contains("可回读落盘文件"))

        // 无任何改写时不出说明，避免给模型加无信息量的噪音。
        assertEquals("", ToolOutputCompressor.foldNote(0, false, null))
    }
}
