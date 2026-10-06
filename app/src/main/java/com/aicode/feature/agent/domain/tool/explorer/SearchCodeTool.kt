package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * rg 风格的项目搜索工具。参数原样传给容器内的 ripgrep；支持 `| head [-n N]` 截断输出，
 * 其余管道命令一律拒绝（见 [buildSearchCommand]），容器未就绪则报错。
 */
class SearchCodeTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver
) : AgentTool() {

    private companion object {
        const val TAG = "SearchTool"
        const val SEARCH_TIMEOUT_MS = 30_000L
    }

    override val name = "search"
    override val description = "按 rg 风格搜索文本。例：args=\"-n \\\"fun main\\\" ~/workspace/app\"。支持追加 `| head [-n N]` 截断输出。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "rg 风格参数。不填无效。常用：-i -F -e -g --hidden --。支持末尾追加 `| head [-n N]` 截断输出；其它管道命令（grep/sort/wc 等）不支持。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val rawArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawArgs.isEmpty()) return ToolResult.Error(
                "缺少搜索参数 args。例：args=\"-n \\\"关键词\\\" ~/workspace/app\"；路径不存在会报错，不确定时先 list 确认。",
                "MISSING_ARGS"
            )

            val tokens = parseShellWords(rawArgs)
                ?: return ToolResult.Error(
                    "args 中存在未闭合的引号。检查引号是否成对（正则里的 () | {} 也需转义，否则报 regex parse error）。",
                    "INVALID_ARGS"
                )
            if (tokens.isEmpty()) return ToolResult.Error("缺少搜索参数 args", "MISSING_ARGS")

            val command = buildSearchCommand(tokens, pathHomeResolver.home())
                ?: return ToolResult.Error("search 仅支持 | head [-n N] 截断输出，不支持其它管道命令", "INVALID_PIPE")

            val startedAt = System.currentTimeMillis()
            val result = commandEngine.runCommandSyncIfReady(
                command = command,
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = SEARCH_TIMEOUT_MS
            ) ?: return ToolResult.Error("容器未就绪，无法执行 rg", "CONTAINER_NOT_READY")

            // 127 = shell 找不到命令；合并 stderr 后通常也能看到 command not found，双保险
            if (isRgMissing(result.output) || result.exitCode == 127) {
                return ToolResult.Error("容器内未安装 rg。容器镜像不含 rg 时可先执行 apt install ripgrep 安装后重试。", "RG_MISSING")
            }
            if (result.exitCode != null && result.exitCode > 1) {
                val raw = result.output.ifBlank { "rg 执行失败" }
                return ToolResult.Error(
                    if (raw.contains("regex parse error")) "$raw\n提示：search 走 rg 正则，特殊字符 ( ) | { } 需转义；纯文本搜索加 -F 关闭正则。"
                    else raw,
                    "RG_ERROR"
                )
            }

            val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
            ToolResult.Success(JsonObject(mapOf(
                "content" to JsonPrimitive(result.output),
                "matches" to JsonPrimitive(lines.size),
                "truncated" to JsonPrimitive(result.outputTruncated),
                "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                "backend" to JsonPrimitive("rg")
            )))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "search 异常", e)
            ToolResult.Error(e.message ?: "搜索失败", "SEARCH_FAILED")
        }
    }

    private fun isRgMissing(output: String): Boolean {
        return output.contains("command not found", ignoreCase = true) ||
            output.contains("rg: not found", ignoreCase = true)
    }
}
