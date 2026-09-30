package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 回归测试：去噪结果必须真的送达模型，且未落盘时不得声称「可回读落盘文件」。
 *
 * 背景（本类此前最隐蔽的失效）：`processElement` 原先只在 `truncated` 时采用去噪后的
 * `preview`；而命令链路的上游 `BoundedOutput` 上限（头尾各 2 万）恰好等于 `MAX_INLINE_CHARS`，
 * 于是 `truncated` 恒为 false，去噪白算一个字符都进不了上下文。
 *
 * 这里用 MockK 桩掉 [ContainerInstaller]（未截断路径不会触碰其目录，仅需满足构造）。
 */
class ToolOutputStoreTest {

    private val store = ToolOutputStore(mockk<ContainerInstaller>(relaxed = true))

    /** 200 行完全相同，共 4000 字符：超过去噪门槛，去噪后远低于上限，故必定走「未截断」分支。 */
    private val repetitiveOutput = (1..200).joinToString("\n") { "Downloading package" }

    @Test
    fun denoisedOutput_isReturnedAsRewrittenObject_evenWhenNotTruncated() {
        val result = store.process("Bash", "call-1", ToolResult.Success(JsonPrimitive(repetitiveOutput)))

        val data = (result as ToolResult.Success).data
        assertTrue("去噪后必须换成带说明的新对象，否则去噪结果被丢弃", data is JsonObject)
        val output = (data as JsonObject)["output"]!!.jsonPrimitive.content
        assertTrue("重复行应被折叠", output.contains("...[上一行重复 200 次]..."))
        assertTrue("应附去噪说明", output.contains("已去噪"))
        assertFalse("未截断，不应标 true", data["output_truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("未落盘却写了回落盘路径，会误导模型", "output_path" !in data)
        assertFalse(
            "未落盘不得声称可回读落盘文件",
            output.contains("可回读落盘文件")
        )
    }

    @Test
    fun shortOutput_isPassedThroughUntouched() {
        val short = "all good"
        val result = store.process("Bash", "call-2", ToolResult.Success(JsonPrimitive(short)))

        val data = (result as ToolResult.Success).data
        assertEquals(short, (data as JsonPrimitive).content)
    }

    /** 非命令类工具（readFile 等）要保留逐字原文，绝不折叠。 */
    @Test
    fun nonCommandTool_isNeverFolded() {
        val result = store.process("readFile", "call-3", ToolResult.Success(JsonPrimitive(repetitiveOutput)))

        val data = (result as ToolResult.Success).data
        assertEquals(repetitiveOutput, (data as JsonPrimitive).content)
    }

    /** 去噪未实际改写内容时，不该给模型加无信息量的「已去噪」噪音。 */
    @Test
    fun distinctLines_getNoDenoiseNote() {
        val distinct = (1..500).joinToString("\n") { "line number $it" }
        val result = store.process("Bash", "call-4", ToolResult.Success(JsonPrimitive(distinct)))

        val data = (result as ToolResult.Success).data
        assertEquals("未去噪则原样透传", distinct, (data as JsonPrimitive).content)
        assertFalse(data.content.contains("已去噪"))
    }

    // ── run 级预算协同 ─────────────────────────────────────────────────

    /** 未传预算时行为与之前完全一致（合入本类不得改变现有会话的行为）。 */
    @Test
    fun noRunBudget_behavesExactlyAsBefore() {
        val result = store.process("readFile", "call-5", ToolResult.Success(JsonPrimitive(repetitiveOutput)))
        val data = (result as ToolResult.Success).data
        assertEquals(repetitiveOutput, (data as JsonPrimitive).content)
    }

    /**
     * 预算充裕时不收紧：不应因为「传了预算对象」就改变输出。
     * 这条防的是「启用预算 = 立刻改变所有会话输出」这类回归。
     */
    @Test
    fun runBudget_withinLimit_doesNotChangeOutput() {
        val budget = ToolRunBudget(10_000_000L)
        val result = store.process("readFile", "call-6", ToolResult.Success(JsonPrimitive(repetitiveOutput)), budget)
        val data = (result as ToolResult.Success).data
        assertEquals("预算充裕时输出应与无预算时一致", repetitiveOutput, (data as JsonPrimitive).content)
        assertEquals(repetitiveOutput.length.toLong(), budget.committedChars)
    }

    /**
     * 预算耗尽后，超长输出被强制收紧内联（不只是「多写一个文件」）——
     * 否则预算省不下任何上下文，形同虚设。
     *
     * 这里用真实临时目录而非 mock 目录：forceSpill 会真的落盘，需验证 output_path 确实可用。
     */
    @Test
    fun runBudget_exhausted_forcesCompactInlinePreview() {
        val tmp = Files.createTempDirectory("tool-output-test").toFile()
        val diskStore = ToolOutputStore(mockk<ContainerInstaller> { every { aicodeDir } returns tmp })
        // 上限设 1，首次预留就超限 → forceSpill。
        val budget = ToolRunBudget(1L)
        val longText = (1..5_000).joinToString("\n") { "line number $it" }
        val result = diskStore.process("Bash", "call-7", ToolResult.Success(JsonPrimitive(longText)), budget)

        val data = (result as ToolResult.Success).data
        assertTrue("超长输出应转为结构化对象（携 output_path）", data is JsonObject)
        val obj = data as JsonObject
        val output = obj["output"]!!.jsonPrimitive.content
        assertTrue("内联应被收紧到远小于原文（原文约 ${longText.length} 字符）", output.length < 20_000)
        assertTrue("应给出回读入口", obj["output_path"] != null)
        assertEquals("true", obj["output_truncated"]!!.jsonPrimitive.content)
        assertTrue("落盘文件应真实存在", diskStore.readBack(obj["output_path"]!!.jsonPrimitive.content) == longText)
    }
}
