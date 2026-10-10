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
    fun accumulatesFromPreviousSummaryBlocksWithTrailingHints() {
        // 真实落库形态：摘要尾部还挂着「本压缩块可恢复」提示（f034cef 引入）与丢弃量注记。
        // 清单块因此不在串尾，BLOCK_CLOSE 的 `$` 尾锚点（无 MULTILINE）失配 →
        // 旧实现解析到 0 个块，跨轮累积静默失效（真机 JVM 实测）。本用例锁死该回归。
        val previous = AgentMessage.AssistantMessage(
            content = "摘要正文\n\n<read-files>\nold-read.kt\n</read-files>\n\n" +
                "<modified-files>\nold-mod.kt\n</modified-files>\n\n" +
                "---\n> 本压缩块（块 id 前缀 c6cc3fd3）含被折叠的早期消息原文，如摘要缺关键细节：" +
                "先用 restoreCompactedRange 的 preview 模式检索定位。"
        )
        val ops = CompactionFileTracker.extract(listOf(previous, assistant(call("readFile", "new.kt"))))
        assertEquals(listOf("old-read.kt", "new.kt"), ops.read)
        assertEquals(listOf("old-mod.kt"), ops.modified)
    }

    @Test
    fun accumulatesWhenDropNoticeFollowsBlocks() {
        // 丢弃量注记（B3-b 新增）同样在清单块之后，不得影响读回。
        val previous = AgentMessage.AssistantMessage(
            content = "摘要正文\n\n<read-files>\nold-read.kt\n</read-files>\n\n" +
                "<modified-files>\nold-mod.kt\n</modified-files>\n" +
                "> 注：更早的 3 条消息（约 12345 token）超出压缩模型窗口，未能纳入本摘要。\n\n" +
                "---\n> 本压缩块（块 id 前缀 abcd1234）含被折叠的早期消息原文。"
        )
        val ops = CompactionFileTracker.extract(listOf(previous))
        assertEquals(listOf("old-read.kt"), ops.read)
        assertEquals(listOf("old-mod.kt"), ops.modified)
    }

    @Test
    fun isolatedCloseTagInBodyDoesNotHideRealBlocks() {
        // 正文里叙述清单机制时会写孤立闭标签（无配对开标签）。裁剪只认「有配对开标签」的真块，
        // 否则会把孤立标签之后的正文与真块一起裁掉。
        val previous = AgentMessage.AssistantMessage(
            content = "摘要正文\n这里提到 </read-files> 这个标签\n重要正文不能丢\n\n" +
                "<read-files>\nreal.kt\n</read-files>"
        )
        val ops = CompactionFileTracker.extract(listOf(previous))
        assertEquals(listOf("real.kt"), ops.read)
    }

    @Test
    fun trailingHintsDoNotPolluteExtraction() {
        // 尾注里的中文标点与 token 数字不得被当路径收下。
        val previous = AgentMessage.AssistantMessage(
            content = "摘要\n\n<read-files>\nreal.kt\n</read-files>\n\n" +
                "<modified-files>\nmod.kt\n</modified-files>\n" +
                "> 注：更早的 3 条消息（约 12345 token）超出压缩模型窗口，未能纳入本摘要。\n" +
                "---\n> 本压缩块（块 id 前缀 abcd1234）含被折叠的早期消息原文。"
        )
        val ops = CompactionFileTracker.extract(listOf(previous))
        assertEquals(listOf("real.kt"), ops.read)
        assertEquals(listOf("mod.kt"), ops.modified)
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
    fun keepsRelativeAndBareNamesFromToolCalls() {
        // 工具调用里的相对路径与裸文件名同样是合法路径（它们不过 isPathLike 那道滤网，直接从参数入表）。
        val ops = CompactionFileTracker.extract(
            listOf(
                assistant(call("readFile", "app/src/main/Main.kt")),
                assistant(call("editFile", "build.gradle.kts"))
            )
        )
        assertEquals(listOf("app/src/main/Main.kt"), ops.read)
        assertEquals(listOf("build.gradle.kts"), ops.modified)
    }

    @Test
    fun ignoresBlockLikeTextInsideSummaryBody() {
        // 真实污染样本：摘要正文叙述清单机制时自带标签字样，旧实现会把中间整段正文当路径收下
        // （实测该块涨到 12235 字符而真实路径 0 条）。
        val polluted = AgentMessage.AssistantMessage(
            content = "正文开始\n<read-files>\n` 块使清单跨轮累积（正则）。KDoc 自陈局限：\n</read-files>\n" +
                "正文继续\n\n<read-files>\n~/workspace/real-read.kt\n</read-files>\n\n" +
                "<modified-files>\n~/workspace/real-mod.kt\n</modified-files>"
        )
        val ops = CompactionFileTracker.extract(listOf(polluted))

        assertEquals(listOf("~/workspace/real-read.kt"), ops.read)
        assertEquals(listOf("~/workspace/real-mod.kt"), ops.modified)
    }

    @Test
    fun keepsCjkAndSpacedRealPaths() {
        // 中文文件名与含空格的路径都是真实路径，不得当脏行过滤掉（实测 304 条里 24 条含中文）。
        val ops = CompactionFileTracker.extract(
            listOf(
                AgentMessage.AssistantMessage(
                    content = "摘要\n\n<modified-files>\n~/workspace/docs/缺陷排查-按功能.md\n" +
                        "~/workspace/.aicode/attachments/新建文本文档 (7).txt\n</modified-files>"
                )
            )
        )
        assertEquals(
            listOf("~/workspace/docs/缺陷排查-按功能.md", "~/workspace/.aicode/attachments/新建文本文档 (7).txt"),
            ops.modified
        )
    }

    @Test
    fun appendStripsOnlyTrailingBlocks() {
        // 正文里的标签字样不得被当成块删掉——只剥尾部的真块。
        val summary = "叙述：块格式是 `\n<read-files>\n~/x.kt\n</read-files>`\n\n正文结尾\n\n" +
            "<modified-files>\n~/workspace/old.kt\n</modified-files>"
        val result = CompactionFileTracker.append(
            summary,
            CompactionFileTracker.FileOps(modified = listOf("~/workspace/new.kt"))
        )

        assertTrue("正文中的标签字样应保留，实际=$result", result.contains("叙述：块格式是"))
        assertTrue("正文结尾应保留，实际=$result", result.contains("正文结尾"))
        assertTrue("旧清单应被替换，实际=$result", !result.contains("old.kt"))
        assertTrue("新清单应写入，实际=$result", result.contains("~/workspace/new.kt"))
        assertEquals("真块应只有一个", 1, Regex("</modified-files>").findAll(result).count())
    }

    @Test
    fun appendIsNoOpWhenEmpty() {
        assertEquals("摘要正文", CompactionFileTracker.append("摘要正文", CompactionFileTracker.FileOps()))
    }

    @Test
    fun appendKeepsOnlyRecentWithinCharBudget() {
        // 清单跨轮只增不减，若全量回写会让摘要被历史路径撑爆（实测单块 56000 字符）。
        val paths = (1..200).map { "~/workspace/app/src/main/java/com/aicode/feature/agent/domain/workflow/Padding$it.kt" }
        val result = CompactionFileTracker.append("摘要", CompactionFileTracker.FileOps(modified = paths))
        val block = Regex("(?s)<modified-files>(.*?)</modified-files>").find(result)!!.groupValues[1]
        // 新行为：截断时附「(更早的 N 条因清单预算被省略)」提示行，不算路径；
        // 该行含中文标点，读取侧 isPathLike 会拒收，不污染下一轮解析。
        val kept = block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.startsWith("(更早的") }.toList()
        val omitted = block.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("(更早的") }

        assertTrue("清单总长应控制在预算量级，实际=${block.length}", block.length <= 2_100)
        assertTrue("应丢弃最旧的路径，实际保留=${kept.size}", kept.size < paths.size)
        assertEquals("保留的应是最近的", paths.takeLast(kept.size), kept)
        assertTrue("应附省略提示且条数正确，实际=$omitted", omitted?.contains("更早的 ${paths.size - kept.size} 条") == true)
    }

    @Test
    fun oversizedSinglePathStillKept() {
        // 单条路径就超预算时不能返回空清单——留一条总比什么都没有更有用。
        val long = "~/workspace/" + "d".repeat(3_000) + ".kt"
        val result = CompactionFileTracker.append("摘要", CompactionFileTracker.FileOps(modified = listOf(long)))
        assertTrue(result.contains(long))
    }

    @Test
    fun appendedBlocksSurviveNextExtractionRoundTrip() {
        // 关键闭环：追加后能被下一轮解析回来，累积才成立。
        val first = CompactionFileTracker.append("摘要", CompactionFileTracker.FileOps(read = listOf("x.kt")))
        val ops = CompactionFileTracker.extract(listOf(AgentMessage.AssistantMessage(content = first)))
        assertEquals(listOf("x.kt"), ops.read)
    }
}
