package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 端到端集成测试：真实 [ToolOutputStore] + 真实临时目录 + 真实 [RetrieveToolResultTool]。
 *
 * 与单测的区别：这里走**完整链路**（超长输出 → 落盘 → 回取 → 分页 → 信封），
 * 只把 [ContainerInstaller] 换成指向临时目录的桩。单测只验单个函数，
 * 无法证明「落盘的 key 与回取解析出的 key 一致」这类跨组件契约——
 * 而那正是最容易出错的地方（文件名生成与解析必须严格对称）。
 */
class SpillAndRetrieveIntegrationTest {

    private val tmpDir = Files.createTempDirectory("spill-e2e").toFile()
    private val store = ToolOutputStore(mockk<ContainerInstaller> { every { aicodeDir } returns tmpDir })
    private val retrieveTool = RetrieveToolResultTool(store)

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/tmp/ws",
        language = "zh",
    )

    private fun longText(lines: Int) =
        (1..lines).joinToString("\n") { "line %05d | payload-abcdefghijklmnopqrstuvwxyz-0123456789 | end".format(it) }

    private fun retrieve(path: String, start: Int? = null, max: Int? = null): JsonObject {
        val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive(path))
        start?.let { args["start_line"] = JsonPrimitive(it) }
        max?.let { args["max_lines"] = JsonPrimitive(it) }
        val result = runBlocking { retrieveTool.executeWithContext(args, context) }
        assertTrue("回取应成功，实际: $result", result is ToolResult.Success)
        return (result as ToolResult.Success).data as JsonObject
    }

    /** 核心链路：超长输出 → 内联带 output_path → 用该 path 回取 → 内容可逐字校验。 */
    @Test
    fun spill_then_retrieve_roundTrip() {
        val text = longText(8_000)   // 约 52 万字符，远超 4 万内联上限
        val processed = store.process("Bash", "call-e2e-1", ToolResult.Success(JsonPrimitive(text)))
        val obj = (processed as ToolResult.Success).data as JsonObject

        assertEquals("true", obj["output_truncated"]!!.jsonPrimitive.content)
        val path = obj["output_path"]!!.jsonPrimitive.content
        assertTrue("落盘路径应指向 tool-output 目录，实际 $path", path.contains("tool-output"))

        // 回取第一页
        val page1 = retrieve(path)
        assertEquals("应为完整行数", 8_000, page1["total_lines"]!!.jsonPrimitive.intOrNull)
        assertEquals("true", page1["has_more"]!!.jsonPrimitive.content)
        assertEquals("回取内容应含首行", true, page1["content"]!!.jsonPrimitive.content.contains("line 00001"))

        // 续读末页
        val page2 = retrieve(path, start = 7_901, max = 200)
        assertEquals("false", page2["has_more"]!!.jsonPrimitive.content)
        assertTrue("末页应含末行", page2["content"]!!.jsonPrimitive.content.contains("line 08000"))
    }

    /**
     * 安全关键：原文来自不可信工具（webfetch）时，**回取必须重新套信封**。
     * 落盘文件是不带信封的原始证据，若原样喂回模型就等于绕过注入隔离。
     */
    @Test
    fun retrieve_fromUntrustedSource_rewrapsEnvelope() {
        val payload = "line 00001\n" + longText(8_000)
        store.process("webfetch", "call-e2e-2", ToolResult.Success(JsonPrimitive(payload)))
        // 从上一次落盘的文件名取回（同进程内 sourceToolOf 应能认出 webfetch）
        val dir = java.io.File(tmpDir, "tool-output")
        val file = dir.listFiles()!!.maxByOrNull { it.lastModified() }!!
        val path = "/root/.aicode/tool-output/${file.name}"

        val page = retrieve(path)
        val content = page["content"]!!.jsonPrimitive.content
        assertTrue("回取不可信来源内容必须重新套信封，实际: ${content.take(120)}", content.startsWith("<untrusted source=\"webfetch\">"))
        assertTrue("信封必须闭合", content.trimEnd().endsWith("</untrusted>"))
        assertEquals("webfetch", page["source_tool"]!!.jsonPrimitive.content)
    }

    /** 本地工具（readFile）来源不受信名单约束，回取不应加信封。 */
    @Test
    fun retrieve_fromLocalSource_isNotEnveloped() {
        store.process("readFile", "call-e2e-3", ToolResult.Success(JsonPrimitive(longText(8_000))))
        val dir = java.io.File(tmpDir, "tool-output")
        val file = dir.listFiles()!!.maxByOrNull { it.lastModified() }!!
        val path = "/root/.aicode/tool-output/${file.name}"

        val page = retrieve(path)
        val content = page["content"]!!.jsonPrimitive.content
        assertFalse("本地来源不应被加信封", content.startsWith("<untrusted"))
        assertEquals("readFile", page["source_tool"]!!.jsonPrimitive.content)
    }

    /** 路径穿越防护：只认 basename，越界路径必须失败而不是读到别处文件。 */
    @Test
    fun retrieve_pathTraversal_isRejected() {
        for (evil in listOf("../../etc/passwd", "/etc/passwd", "../aicode.json")) {
            val args = mapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive(evil))
            val result = runBlocking { retrieveTool.executeWithContext(args, context) }
            assertTrue("越界路径「$evil」应失败，实际: $result", result is ToolResult.Error)
        }
    }

    /** 已清理/不存在的落盘文件：给出可理解错误，而不是抛异常。 */
    @Test
    fun retrieve_missingFile_givesActionableError() {
        val args = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "path" to JsonPrimitive("/root/.aicode/tool-output/does-not-exist.log")
        )
        val result = runBlocking { retrieveTool.executeWithContext(args, context) }
        assertTrue(result is ToolResult.Error)
        assertTrue(
            "错误信息应给出下一步（重跑或 readFile）",
            (result as ToolResult.Error).message.contains("重新执行")
        )
    }

    /**
     * run 预算端到端：预算耗尽后，后续输出被收紧内联并落盘；
     * 这些落盘内容仍能用 retrieveToolResult 完整取回（预算不该导致数据丢失）。
     */
    @Test
    fun runBudget_tightening_stillKeepsDataRetrievable() {
        val budget = ToolRunBudget(1L)   // 首次预留即超限 → forceSpill
        val text = longText(8_000)
        val processed = store.process("Bash", "call-e2e-4", ToolResult.Success(JsonPrimitive(text)), budget)
        val obj = (processed as ToolResult.Success).data as JsonObject

        val inline = obj["output"]!!.jsonPrimitive.content
        assertTrue("内联应被收紧（实际 ${inline.length} 字符）", inline.length < 20_000)

        val path = obj["output_path"]!!.jsonPrimitive.content
        assertNotNull(path)
        val full = store.readBack(path)
        assertEquals("落盘必须是完整原文——预算只该影响内联，不该丢数据", text, full)
    }
}
