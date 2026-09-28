package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.sanitizeCommandForLog
import com.aicode.feature.agent.domain.container.ContainerBuildGuard
import com.aicode.feature.agent.domain.container.HostMemoryProbe
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.LineFolder
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * 命令在当前选中工作区目录下执行，使 AI 的 shell 操作（npm install、git 等）
 * 与文件工具作用于同一目录。
 *
 * 同时实现 [StreamingAgentTool]：优先逐行流式输出，让聊天里能实时看到命令执行过程；
 * [execute] 作为非流式兜底保留，最终聚合结果两者一致（喂回模型不变）。
 */
class ExecuteCommandTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val memoryProbe: HostMemoryProbe
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** 默认超时（秒），与 [LinuxContainerEngine.DEFAULT_TIMEOUT_MS] 对齐。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时上限（秒），与 [LinuxContainerEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Bash"
    override val description = "在当前执行环境（本地 Linux 容器或远程 SSH 服务器）中执行 Shell 命令。支持 npm、git 等绝大多数终端操作。对于耗时任务（如安装大量依赖、启动服务器等），请不要在此命令末尾加 '&' 挂后台，而是强烈建议改用 `terminal` 工具（action=\"start\"）来创建常驻终端页面，这样才能方便后续查看实时输出结果和管理进程。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "The shell command to execute",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。耗时命令（如安装依赖）可适当调大。",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "提权重试：仅当命令因内置安全防护（灾难性删除，如 rm 根目录/系统目录/工作区整体）被拒、且确有必要执行时，置为 true 重试。届时系统会弹窗请求用户一次性授权，用户同意才执行，且不可记忆。仅非 AUTO 模式有效。",
            required = false
        )
    )

    /**
     * 把内存保护的改写说明与内存水位警告附在输出末尾。
     * 两者都是「让模型知晓执行环境实情」的信息，不打进日志而回给模型，
     * 它才能在后续步骤里主动调整（拆小任务 / 提醒用户释放内存）。
     */
    private fun appendGuardNote(output: String, note: String?, memoryWarning: String?): String {
        if (note == null && memoryWarning == null) return output
        return buildString {
            append(output)
            if (note != null) append("\n\n[$note]")
            if (memoryWarning != null) append("\n\n[内存警告] $memoryWarning")
        }
    }

    /**
     * 仅对「被识别为构建命令」的调用采样内存：普通命令（ls/grep）开销小，多一次查询没必要。
     * 采样失败或内存充足时返回 null。
     * 必须**执行前**调用：任务已在跑时才知道内存不足已经来不及阻止。
     */
    private fun memoryWarningFor(isBuildCommand: Boolean): String? =
        if (isBuildCommand) memoryProbe.warnIfLow("构建命令") else null

    /** 解析 timeout（秒）参数并钳到合法范围，返回毫秒；缺省用默认值。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行命令",
            summary = command,
            details = "将在当前执行环境中执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")

        return try {
            // 在当前工作区目录内执行，与文件工具保持同一根目录
            val workdir = workspaceRepository.currentPath()
            val timeoutMs = resolveTimeoutMs(args)
            val guarded = ContainerBuildGuard.guard(command)
            if (guarded.rewritten) FileLogger.i(TAG, "命令已加内存保护: ${sanitizeCommandForLog(guarded.command)}")
            FileLogger.d(TAG, "execute_command (timeout=${timeoutMs}ms): ${sanitizeCommandForLog(guarded.command)}")
            // 执行前采样：事后采样无法提前预警，就失去了意义
            val memWarning = memoryWarningFor(guarded.rewritten)
            val output = commandEngine.runCommandSync(guarded.command, workdir, timeoutMs)
            FileLogger.v(TAG, "execute_command 完成，输出 ${output.length} 字符")
            ToolResult.Success(JsonPrimitive(appendGuardNote(output, guarded.note, memWarning)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command 失败: ${sanitizeCommandForLog(command)}", e)
            ToolResult.Error("执行命令失败: ${e.message}")
        }
    }

    /**
     * 流式执行：逐行 emit [ToolStreamEvent.Progress]，命令结束 emit [ToolStreamEvent.Completed]，
     * 其最终结果与 [execute] 等价（同样经 [BoundedOutput] 限幅：超大输出仅保留开头+结尾），
     * 保证喂回模型的内容一致且不会撑爆上下文。
     *
     * 与 [execute] 的差别：这里在累积**之前**先用 [LineFolder] 逐行去噪，把重复行折叠掉再进限幅窗口，
     * 因此尾窗里留下的是真正的结尾报错，而非上百行重复日志；实时展示区仍逐行发原文（含进度回刷）。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("缺少必需参数: command")))
            return@flow
        }

        // 限幅累积：喂回模型的最终结果只保留开头+结尾，避免超大输出撑爆上下文。
        // 去噪前移到此处（而非入库时）：否则中段成千上万行重复会先把头尾窗口占满，
        // 真正有诊断价值的结尾报错反而被挤出尾窗，折叠计数也失真。
        val accumulated = BoundedOutput()
        val folder = LineFolder()
        try {
            val workdir = workspaceRepository.currentPath()
            val timeoutMs = resolveTimeoutMs(args)
            val guarded = ContainerBuildGuard.guard(command)
            if (guarded.rewritten) FileLogger.i(TAG, "命令已加内存保护: ${sanitizeCommandForLog(guarded.command)}")
            FileLogger.d(TAG, "execute_command(流式, timeout=${timeoutMs}ms): ${sanitizeCommandForLog(guarded.command)}")
            // 执行前采样：事后采样无法提前预警，就失去了意义
            val memWarning = memoryWarningFor(guarded.rewritten)
            commandEngine.runCommandStream(guarded.command, workdir, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        folder.feed(event.text).forEach { line ->
                            accumulated.append(line)
                            accumulated.append("\n")
                        }
                        // 实时区展示原始行：进度条回刷等噪点让用户看到“在动”，不参与模型上下文。
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> { /* 结束在流完成后统一聚合 */ }
                }
            }
            folder.finish().forEach { line ->
                accumulated.append(line)
                accumulated.append("\n")
            }
            FileLogger.v(TAG, "execute_command(流式) 完成，输出 ${accumulated.totalChars} 字符")
            emit(ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive(appendGuardNote(accumulated.build(), guarded.note, memWarning)))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：底层 flow 异常终止时，已逐行 emit 给用户的 Progress 仍应作为最终结果保留，
            // 而不是被这里抛出的空 Error 覆盖掉（否则模型只看到“执行失败”，之前展示的输出全丢）。
            FileLogger.e(TAG, "execute_command(流式) 异常(已保留此前输出 ${accumulated.totalChars} 字符): $command", e)
            // 先把 pending 行刷出，否则最后一行会随异常一起丢掉。
            runCatching {
                folder.finish().forEach { line ->
                    accumulated.append(line)
                    accumulated.append("\n")
                }
            }
            val saved = accumulated.build()
            val result = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(saved))
            } else {
                ToolResult.Error("执行命令失败: ${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }
}
