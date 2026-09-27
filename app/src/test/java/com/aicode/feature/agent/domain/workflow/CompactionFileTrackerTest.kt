package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionFileTrackerTest {

    private fun call(name: String, path: String) = ToolCall(
        id = "call-$name-$path",
        name = name,
        arguments = mapOf("path" to JsonPrimitive(path))
    )

    private fun assistant(vararg toolCalls: ToolCall) =
        AgentMessage.AssistantMessage(content = "正文", toolCalls = toolCalls.toList())

    @Test
    fun bucketsReadAndModifiedByToolName() {
        val ops = CompactionFileTracker.extract(
            listOf(
                assistant(call("readFile", "a.kt")),
                assistant(call("editFile", "b.kt")),
                assistant(call("writeFile", "c.kt"))
            )
        )
        assertEquals(listOf("a.kt"), ops.read)
        assertEquals(listOf("b.kt", "c.kt"), ops.modified)
    }

    @Test
    fun modifiedFileIsRemovedFromReadList() {
        // 先读后改：文件应只出现在「已改」里，避免模型以为还要照原样去读。
        val ops = CompactionFileTracker.extract(
            listOf(
                assistant(call("readFile", "same.kt")),
                assistant(call("editFile", "same.kt"))
            )
        )
        assertTrue("已改文件不应留在读过清单里，实际=${ops.read}", ops.read.isEmpty())
        assertEquals(listOf("same.kt"), ops.modified)
    }

    @Test
    fun accumulatesFromPreviousSummaryBlocks() {
        // 重复压缩：上一轮摘要里的清单块必须被解析并保留，否则历史文件信息逐轮丢失。
        val previous = AgentMessage.AssistantMessage(
            content = "摘要正文\n\n<read-files>\nold-read.kt\n</read-files>\n\n<modified-files>\nold-mod.kt\n</modified-files>"
        )
        val ops = CompactionFileTracker.extract(listOf(previous, assistant(call("readFile", "new.kt"))))
        assertEquals(listOf("old-read.kt", "new.kt"), ops.read)
        assertEquals(listOf("old-mod.kt"), ops.modified)
    }

    @Test
    fun ignoresUserMessageAndBlankPaths() {
        // 用户消息里的同名字样属巧合，不是元数据；缺参数的工具调用跳过。
        val user = AgentMessage.UserMessage(content = "<modified-files>\n不存在的.kt\n</modified-files>")
        val noPath = ToolCall(id = "x", name = "readFile", arguments = emptyMap())
        val ops = CompactionFileTracker.extract(listOf(user, assistant(noPath, call("readFile", "real.kt"))))
        assertEquals(listOf("real.kt"), ops.read)
        assertTrue(ops.modified.isEmpty())
    }

    @Test
    fun unrelatedToolsAreIgnored() {
        val ops = CompactionFileTracker.extract(
            listOf(assistant(call("search", "query"), call("Bash", "ls")))
        )
        assertTrue(ops.isEmpty)
    }

    @Test
    fun appendAddsBlocksWithAbsolutePaths() {
        val ops = CompactionFileTracker.FileOps(read = listOf("~/workspace/a.kt"), modified = listOf("~/workspace/b.kt"))
        val result = CompactionFileTracker.append("摘要正文", ops)
        assertTrue(result.startsWith("摘要正文"))
        assertTrue(result.contains("<read-files>\n~/workspace/a.kt\n</read-files>"))
        assertTrue(result.contains("<modified-files>\n~/workspace/b.kt\n</modified-files>"))
    }

    @Test
    fun appendIsNoOpWhenEmpty() {
        assertEquals("摘要正文", CompactionFileTracker.append("摘要正文", CompactionFileTracker.FileOps()))
    }

    @Test
    fun appendedBlocksSurviveNextExtractionRoundTrip() {
        // 关键闭环：追加后能被下一轮解析回来，累积才成立。
        val first = CompactionFileTracker.append("摘要", CompactionFileTracker.FileOps(read = listOf("x.kt")))
        val ops = CompactionFileTracker.extract(listOf(AgentMessage.AssistantMessage(content = first)))
        assertEquals(listOf("x.kt"), ops.read)
    }
}
