package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.container.ContainerInstaller
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
