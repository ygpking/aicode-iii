package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolRenderHint
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
 * rg 风格的项目搜索工具。参数原样传给容器内的 ripgrep；支持末尾接只读管道加工输出
 * （命令名白名单 + 逐命令选项白名单 + 参数字符集三道约束，见 [pipeSegmentAllowed]），容器未就绪则报错。
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
    // 呈现意图：UI 卡片按此选样式（抄 DSH defineTool 的 present 元数据）
    override val renderHint = ToolRenderHint.SEARCH
    override val description =
        "按 rg 风格搜索文本（rg 默认递归、默认带文件名，勿加 -r——那是替换标志）。" +
            "例：args=\"-n \\\"fun main\\\" ~/workspace/app\"。" +
            "支持末尾接只读管道（head/tail/grep/rg/sort/uniq/wc/cut/tr/nl/rev/tac）加工输出；" +
            "管道段只能带该命令的常用只读选项，也不要给它文件名——管道段只能加工上游输出、不能另读文件" +
            "（`| grep x /etc/passwd` 会被拒；`| head -30`、`| grep -i foo` 正常）；" +
            "正则括号需成对（不配对会直接被挡下，不必等 rg 报错）；纯文本搜索加 -F 关闭正则最省心。" +
            "路径不存在会直接报错，不确定时先 list 确认。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "rg 风格参数。不填无效。常用：-i -F -e -g --hidden --。" +
                "支持末尾接只读管道（head/tail/grep/rg/sort/uniq/wc/cut/tr/nl/rev/tac），" +
                "管道段只能用这些命令的常用只读选项（写文件/执行外部程序的选项一律拒），" +
                "并且只能加工上游输出、不能另读文件（各命令允许的位置参数个数固定：" +
                "head/tail/sort/uniq/wc/cut/nl/rev/tac 不允许带参数，grep/rg 只接一个检索词" +
                "（用 -e/--regexp 提供检索式后不再接任何位置参数））；" +
                "重定向（> >> <）与写文件类命令（cp/mv/tee/install 等）不支持。",
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

            // 正则括号配对预检：不配对时本地直接挡下，省掉一次 rg 容器往返
            // （实测 73 次 regex parse error，40 次是括号不配对，单会话连错 10 次）。
            // 只校验 rg 段：管道段（`| grep '(bar'`）的正则不属于 rg，把整份 tokens 传进去
            // 会让「rg 的正则合法、管道段的括号不配对」被误判成 rg 报错，白拒一次合法调用。
            // splitPipes 返回 null 仅当 args 以 `|` 开头（无 rg 参数）——那种情况交给
            // buildSearchCommand 去报 INVALID_PIPE，这里跳过预检。
            splitPipes(tokens)?.first?.let { rgTokens ->
                rgPatternParenMismatch(rgTokens)?.let { reason ->
                    return ToolResult.Error(reason, "INVALID_PATTERN")
                }
            }

            val command = buildSearchCommand(tokens, pathHomeResolver.home())
                ?: return ToolResult.Error(
                    "管道段不被支持。只允许只读加工命令（head/tail/grep/rg/sort/uniq/wc/cut/tr/nl/rev/tac），" +
                        "且它们只能带常用只读选项、只能加工上游输出（不能另接文件名或路径），" +
                        "参数中也不得含重定向（> >> <）、反引号或命令分隔符。" +
                        "如果管道只是为了截断行数，可直接用 `| head -N`。",
                    "INVALID_PIPE"
                )

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
