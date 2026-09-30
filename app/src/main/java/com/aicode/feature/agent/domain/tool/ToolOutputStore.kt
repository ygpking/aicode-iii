package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class StoredToolOutput(
    val preview: String,
    val truncated: Boolean,
    val totalChars: Long,
    val outputPath: String? = null,
    val storageError: String? = null,
    /** 去噪真实改写了内容（折叠了重复行 / 清除 ANSI）。未截断时调用方据此改用 [preview]。 */
    val denoised: Boolean = false
)

@Singleton
class ToolOutputStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "ToolOutputStore"
        const val AICODE_ROOT = "/root/.aicode"
        const val OUTPUT_DIR = "tool-output"
        const val HEAD_CHARS = 20_000
        const val TAIL_CHARS = 20_000
        const val MAX_INLINE_CHARS = HEAD_CHARS + TAIL_CHARS
        val LARGE_TEXT_FIELDS = listOf("output", "content", "text", "stdout", "stderr", "body", "result")
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        // 只对命令类输出做通用去噪：进度条/重复行/ANSI 噪音集中在这里；
        // readFile 等要保留原文逐字，不参与折叠。
        val COMPRESS_TOOLS = setOf("Bash", "terminal")
        // 短于此阈值的输出不值得为省一点字符引入去噪，直接原样。
        const val COMPRESS_MIN_CHARS = 2_000
    }

    private val json = Json { encodeDefaults = true }

    /** 落盘输出容量治理：TTL/字节数/条目数三条预算，超限优先删最旧。 */
    private val spillBudget = OutputSpillBudget()

    /** 存档目录（宿主路径）。对外只用于占用统计与清理，写入仍走本类。 */
    val outputDir: File get() = File(containerInstaller.aicodeDir, OUTPUT_DIR)

    fun process(toolName: String, callId: String, result: ToolResult): ToolResult {
        val bounded = when (result) {
            is ToolResult.Success -> ToolResult.Success(processElement(toolName, callId, result.data))
            is ToolResult.Partial -> ToolResult.Partial(processElement(toolName, callId, result.data), result.message)
            is ToolResult.Error -> result
        }
        // 外部内容（网页/MCP 等）加不可信信封。放在截断**之后**：否则预览可能被截断切断闭合标签，
        // 留下未闭合的信封；落盘原文保持不加信封（磁盘上留存的是可逐字核对的原始证据）。
        // 本地文件读取（readFile 等）不在 [UntrustedEnvelope.sourceFor] 的名单内，行为完全不变。
        return UntrustedEnvelope.apply(toolName, bounded)
    }

    fun boundText(toolName: String, callId: String, rawText: String): StoredToolOutput {
        // 先脱敏再去噪再截断/落盘：保证「进模型的正文」与「落盘文件」同一口径，密钥不被持久化。
        val scrubbed = ToolOutputScrubber.scrub(rawText)

        // 通用去噪：仅命令类工具、且原文足够长时才做。折叠说明会附在预览末尾，让模型知情。
        val compressed = if (toolName in COMPRESS_TOOLS && scrubbed.length >= COMPRESS_MIN_CHARS) {
            ToolOutputCompressor.compress(scrubbed)
        } else {
            null
        }
        val text = compressed?.text ?: scrubbed
        val denoised = compressed != null && (compressed.linesFolded > 0 || compressed.ansiStripped)

        if (text.length <= MAX_INLINE_CHARS) {
            return StoredToolOutput(
                // 未落盘：不能提「可回读落盘文件」，否则模型会去找不存在的文件。
                preview = text + ToolOutputCompressor.foldNote(
                    compressed?.linesFolded ?: 0, compressed?.ansiStripped ?: false, spilledPath = null
                ),
                truncated = false,
                totalChars = text.length.toLong(),
                denoised = denoised
            )
        }

        // 落盘保留去噪前的脱敏原文，保证被折叠的内容仍可逐字回捞（无损可逆）。
        val writeResult = writeFullOutput(toolName, callId, scrubbed)
        val preview = buildPreview(text, writeResult.outputPath, toolName) + ToolOutputCompressor.foldNote(
            compressed?.linesFolded ?: 0, compressed?.ansiStripped ?: false, spilledPath = writeResult.outputPath
        )
        return StoredToolOutput(
            preview = preview,
            truncated = true,
            totalChars = text.length.toLong(),
            outputPath = writeResult.outputPath,
            storageError = writeResult.storageError,
            denoised = denoised
        )
    }

    private fun processElement(toolName: String, callId: String, rawElement: JsonElement): JsonElement {
        val element = scrubElement(rawElement)
        val primitive = element as? JsonPrimitive
        if (primitive?.isString == true) {
            val stored = boundText(toolName, callId, primitive.content)
            // 截断输出必须换成带 output_path 的新对象；去噪生效时也必须改用 preview——
            // 原先只判 truncated，而命令链路的上游（BoundedOutput 头尾各 2 万）恰好等于
            // MAX_INLINE_CHARS，去噪后必然 ≤ 上限，于是 truncated 恒为 false、preview 被丢掉，
            // 去噪白算（这是本函数此前最隐蔽的一处失效）。
            return if (stored.truncated || stored.denoised) stored.toJsonObject("output") else element
        }

        val obj = element as? JsonObject
        if (obj != null) {
            val largeField = LARGE_TEXT_FIELDS
                .mapNotNull { key ->
                    val field = obj[key] as? JsonPrimitive
                    val value = if (field?.isString == true) field.content else null
                    if (value != null && value.length > MAX_INLINE_CHARS) key to value else null
                }
                .maxByOrNull { it.second.length }

            if (largeField != null) {
                val stored = boundText(toolName, callId, largeField.second)
                val updated = obj.toMutableMap()
                updated[largeField.first] = JsonPrimitive(stored.preview)
                addMetadata(updated, stored)
                return JsonObject(updated)
            }
        }

        val serialized = json.encodeToString(element)
        if (serialized.length <= MAX_INLINE_CHARS) return element

        val stored = boundText(toolName, callId, serialized)
        return stored.toJsonObject("content")
    }

    /** 递归脱敏 JSON 中的所有字符串叶子（保留结构）。 */
    private fun scrubElement(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(ToolOutputScrubber.scrub(element.content)) else element
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> scrubElement(value) })
        is JsonArray -> JsonArray(element.map { scrubElement(it) })
        else -> element
    }

    private fun addMetadata(target: MutableMap<String, JsonElement>, stored: StoredToolOutput) {
        target["output_truncated"] = JsonPrimitive(stored.truncated)
        target["output_total_chars"] = JsonPrimitive(stored.totalChars)
        stored.outputPath?.let { target["output_path"] = JsonPrimitive(it) }
        stored.storageError?.let { target["output_storage_error"] = JsonPrimitive(it) }
    }

    private fun StoredToolOutput.toJsonObject(primaryField: String): JsonObject {
        val data = mutableMapOf<String, JsonElement>(
            primaryField to JsonPrimitive(preview),
            "output_truncated" to JsonPrimitive(truncated),
            "output_total_chars" to JsonPrimitive(totalChars)
        )
        outputPath?.let { data["output_path"] = JsonPrimitive(it) }
        storageError?.let { data["output_storage_error"] = JsonPrimitive(it) }
        return JsonObject(data)
    }

    /** 按工具类型选保留方向回灌：命令类看尾部（结果/报错在后），文件读取两端都留，其余看头部。 */
    private fun buildPreview(text: String, outputPath: String?, toolName: String): String {
        val omitted = text.length - MAX_INLINE_CHARS
        val storageHint = if (outputPath != null) {
            "完整内容已保存到 $outputPath"
        } else {
            "完整内容保存失败"
        }
        // 被截掉的中间段里夹着的报错/栈帧往往比头尾更有诊断价值，单独挑回来附在截断说明前。
        val salient = buildSalientBlock(text, toolName)
        return when (spillBudget.headTailDirection(toolName)) {
            Direction.HEAD -> buildString {
                append(text.take(MAX_INLINE_CHARS))
                append(salient)
                append("\n\n...[输出过长，已省略其后 ")
                append(omitted)
                append(" 个字符；")
                append(storageHint)
                append("]...")
            }
            Direction.TAIL -> buildString {
                append("...[输出过长，已省略其前 ")
                append(omitted)
                append(" 个字符；")
                append(storageHint)
                append("]...")
                append(salient)
                append('\n')
                append(text.takeLast(MAX_INLINE_CHARS))
            }
            Direction.HEAD_TAIL -> buildString {
                append(text.take(HEAD_CHARS))
                append(salient)
                append("\n\n...[输出过长，已省略中间 ")
                append(omitted)
                append(" 个字符；")
                append(storageHint)
                append("]...\n\n")
                append(text.takeLast(TAIL_CHARS))
            }
        }
    }

    /**
     * 只对保留头部的方向提取显著行（尾部方向本就看得到末尾报错，再插会重复）。
     * 显著行来自被截掉的那一段，避免与已展示的头尾重复。
     */
    private fun buildSalientBlock(text: String, toolName: String): String {
        val middle = when (spillBudget.headTailDirection(toolName)) {
            Direction.HEAD -> text.drop(MAX_INLINE_CHARS)
            Direction.HEAD_TAIL -> text.drop(HEAD_CHARS).dropLast(TAIL_CHARS)
            Direction.TAIL -> return ""
        }
        val salient = SalientLines.extract(middle) ?: return ""
        return "\n\n...[已省略段落中的关键行]...\n$salient"
    }

    @Synchronized
    private fun writeFullOutput(toolName: String, callId: String, text: String): StoredPathResult {
        return try {
            val dir = outputDir.apply { mkdirs() }
            val file = uniqueOutputFile(dir, toolName, callId)
            file.writeText(text, Charsets.UTF_8)
            val path = "$AICODE_ROOT/$OUTPUT_DIR/${file.name}"
            FileLogger.i(TAG, "工具输出已保存: $path (${text.length} chars)")
            collectGarbage(dir)
            StoredPathResult(outputPath = path)
        } catch (e: Exception) {
            FileLogger.w(TAG, "保存工具输出失败: ${e.message}", e)
            StoredPathResult(storageError = e.message ?: "保存工具输出失败")
        }
    }

    /** 写入后回收：删除过期与超预算的最旧输出，避免目录无上限增长（在写锁内调用）。 */
    private fun collectGarbage(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile } ?: return
            val entries = files.map { SpillEntry(it.name, it.length(), it.lastModified(), "") }
            val doomed = spillBudget.decide(entries, System.currentTimeMillis())
            if (doomed.isEmpty()) return
            doomed.forEach { name -> File(dir, name).delete() }
            FileLogger.i(TAG, "工具输出 GC：删除 ${doomed.size} 个过期/超预算文件")
        }.onFailure { FileLogger.w(TAG, "工具输出 GC 失败: ${it.message}", it) }
    }

    private fun uniqueOutputFile(dir: File, toolName: String, callId: String): File {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val baseName = buildString {
            append(timestamp)
            append('-')
            append(sanitize(toolName).ifBlank { "tool" })
            val id = sanitize(callId).take(12)
            if (id.isNotBlank()) {
                append('-')
                append(id)
            }
        }

        var candidate = File(dir, "$baseName.log")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName-$index.log")
            index++
        }
        return candidate
    }

    private fun sanitize(value: String): String {
        return value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("").trim('-')
    }

    private data class StoredPathResult(
        val outputPath: String? = null,
        val storageError: String? = null
    )
}
