package com.aicode.feature.virtualscreen.domain.tool

import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.virtualscreen.domain.VirtualScreenController
import com.aicode.feature.virtualscreen.domain.a11y.VirtualScreenA11yService
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 虚拟屏会话工具：在**独立于物理屏**的虚拟显示器里打开并操作 App，用户当前正在用的界面不受影响。
 *
 * 与 `Shizuku` 工具的区别：`Shizuku` 是通用 shell 通道；本工具是「一块屏 + 一个会话」的专用编排，
 * 内置「开屏 → 投 App → 读界面 → 点/输入 → 回收」的完整闭环。
 *
 * 前提：Shizuku（root 或 adb 授权均可，实测 adb/uid 2000 即可建屏）；`dump`/`click`/`input`/`swipe`
 * 另需用户开启本应用的无障碍服务。
 */
class VirtualScreenTool @Inject constructor(
    private val controller: VirtualScreenController,
    private val pathMapper: WorkspacePathMapper
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "VirtualScreenTool"

        /**
         * 截图落盘目录（容器视角）。
         *
         * 必须落在 `~/workspace` 下：该目录被 bind 成容器内可见，`viewImage` 才能读到
         * （它只认容器路径）。放宿主其他位置则 AI 无法用任何工具查看。
         * 用点开头目录，不在文件树里干扰用户的工作区浏览。
         */
        const val SHOT_DIR = "~/workspace/.vdshots"

        /** 保留的截图张数上限，超出按修改时间删最旧的，避免在工作区里无限累积。 */
        const val MAX_KEPT_SCREENSHOTS = 10
    }

    override val name = "virtualScreen"

    override val description =
        "在独立的虚拟显示器里打开并操作 App，全程不影响用户正在使用的物理屏幕（无浮层、不抢焦点）。" +
            "用于需要「实际点开 App 看真实界面」的任务：验证 UI 改动、走一遍注册/登录流程、复现界面问题。" +
            "会话由 AI 独占，操作不进入用户的最近任务；关闭时会强制停止目标应用，避免任务残留。\n" +
            "动作：open=开屏并启动 App；dump=读取界面（结构化文本，含可见文本/控件/坐标）；" +
            "click=按文本或 id 点击；input=向输入框写文本；swipe=滑动；" +
            "screenshot=截取虚拟屏画面（返回图片路径，用 viewImage 查看）；close=关闭并回收；status=查询状态。\n" +
            "典型流程：open → dump → click/input → dump 确认结果 → close。" +
            "**dump 给语义，截图给视觉**：图像/画布/WebView/游戏等节点树表达不了的内容用 screenshot。\n" +
            "**虚拟屏按会话隔离**：每个 AI 会话有自己的一块屏，dump/click/close 只作用于本会话的屏，" +
            "看不到也动不了别的会话的屏；同一应用不能跨会话重复打开（会互相影响），最多同时 4 块。\n" +
            "依赖 Shizuku（需已授权）；界面读写需用户已开启本应用的无障碍服务。"

    override val permissionPolicy = ToolPermissionPolicy.ASK

    /**
     * 沿用 EXECUTE_COMMANDS：本工具经 Shizuku 以 shell 身份执行系统命令（am/unzip）并改动系统显示状态，
     * 与「执行命令」同一风险级别，不应比它更宽松。
     */
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "操作类型", true,
            enum = listOf("open", "dump", "click", "input", "swipe", "screenshot", "close", "status")
        ),
        "packageName" to ToolParameter(
            "packageName", ParameterType.STRING, "要打开的 App 包名（如 com.android.settings）。action=open 必填。", false
        ),
        "text" to ToolParameter(
            "text", ParameterType.STRING, "click：要点击的文本；input：要写入的文本（二者必填其一对应）。", false
        ),
        "id" to ToolParameter(
            "id", ParameterType.STRING, "click：按 viewId 定位（如 android:id/switch_widget）。与 text 二选一。", false
        ),
        "exact" to ToolParameter(
            "exact", ParameterType.BOOLEAN, "click：是否精确匹配文本，默认 false（包含匹配）。", false
        ),
        "index" to ToolParameter(
            "index", ParameterType.INTEGER, "click：匹配到多个时的下标，默认 0。dump 时用可看到序号。", false
        ),
        "bounds" to ToolParameter(
            "bounds", ParameterType.STRING,
            "click：坐标兜底，格式 left,top,right,bottom（dump 输出里可复制）。与 text/id 二选一。", false
        ),
        "waitReady" to ToolParameter(
            "waitReady", ParameterType.BOOLEAN, "dump：是否等待界面就绪（刚投屏后首次读取建议 true）。", false
        ),
        "x1" to ToolParameter("x1", ParameterType.INTEGER, "swipe：起点 x，默认 540。", false),
        "y1" to ToolParameter("y1", ParameterType.INTEGER, "swipe：起点 y，默认 1800。", false),
        "x2" to ToolParameter("x2", ParameterType.INTEGER, "swipe：终点 x，默认 540。", false),
        "y2" to ToolParameter("y2", ParameterType.INTEGER, "swipe：终点 y，默认 600（默认即向上滑一屏）。", false),
        "width" to ToolParameter("width", ParameterType.INTEGER, "虚拟屏宽度像素，默认 1080。", false),
        "height" to ToolParameter("height", ParameterType.INTEGER, "虚拟屏高度像素，默认 2400。", false),
        "dpi" to ToolParameter("dpi", ParameterType.INTEGER, "虚拟屏密度，默认 440。", false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "?"
        val summary = when (action) {
            "open" -> "在虚拟屏中打开 ${args["packageName"]?.jsonPrimitive?.contentOrNull ?: "（缺少包名）"}"
            "click" -> "在虚拟屏中点击「${args["text"]?.jsonPrimitive?.contentOrNull ?: args["id"]?.jsonPrimitive?.contentOrNull ?: "?"}」"
            "input" -> "在虚拟屏中输入文本"
            "swipe" -> "在虚拟屏中滑动"
            "close" -> "关闭虚拟屏会话"
            else -> "读取虚拟屏状态"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认虚拟屏操作",
            summary = summary,
            details = "虚拟屏与物理屏互相独立：不开浮层、不抢焦点、不进入用户的最近任务。\n" +
                "只读取/操作虚拟屏上的界面，不读取用户当前使用的屏幕。",
            argsPreview = argsPreview
        )
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: action", code = "MISSING_ARG")

        return try {
            when (action) {
                "open" -> open(args, context)
                "dump" -> dump(args, context)
                "click" -> click(args, context)
                "input" -> input(args, context)
                "swipe" -> swipe(args, context)
                "screenshot" -> screenshot(args, context)
                "close" -> close(context)
                "status" -> status(context)
                else -> ToolResult.Error(
                    "未知 action: $action（可选 open/dump/click/input/swipe/screenshot/close/status）",
                    code = "BAD_ARG"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "virtualScreen $action 失败", e)
            ToolResult.Error("虚拟屏操作失败: ${e.message}")
        }
    }

    // ── 会话管理 ────────────────────────────────────────────────────────

    private suspend fun open(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val pkg = args["packageName"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("action=open 需要 packageName", code = "MISSING_ARG")

        val result = controller.open(
            packageName = pkg,
            scope = context.sessionId,
            width = args.intOr("width", 1080),
            height = args.intOr("height", 2400),
            dpi = args.intOr("dpi", 440)
        )

        return result.fold(
            onSuccess = { session ->
                ToolResult.Success(JsonObject(mapOf(
                    "displayId" to JsonPrimitive(session.displayId),
                    "packageName" to JsonPrimitive(session.packageName),
                    "size" to JsonPrimitive("${session.width}x${session.height}@${session.dpi}"),
                    "a11yReady" to JsonPrimitive(requireA11y() != null),
                    "message" to JsonPrimitive(
                        "虚拟屏已就绪，应用已启动，物理屏不受影响。" +
                            if (requireA11y() == null) {
                                "（无障碍服务未开启，界面读写不可用，请提示用户到系统设置中开启）"
                            } else {
                                "可执行 dump 读取界面。"
                            }
                    )
                )))
            },
            onFailure = { e ->
                ToolResult.Error(
                    e.message ?: "创建虚拟屏失败",
                    code = if (controller.lastError?.contains("Shizuku") == true) "SHIZUKU_NOT_READY" else "OPEN_FAILED"
                )
            }
        )
    }

    private suspend fun close(context: AgentContext): ToolResult {
        val ok = controller.close(context.sessionId)
        return if (ok) {
            ToolResult.Success(JsonObject(mapOf(
                "message" to JsonPrimitive("虚拟屏已关闭，目标应用已停止，无任务残留。")
            )))
        } else {
            ToolResult.Error(controller.lastError ?: "关闭虚拟屏失败")
        }
    }

    private suspend fun status(context: AgentContext): ToolResult {
        // 「本会话自己的屏」与「全局所有会话」都报：前者回答「我能不能操作」，
        // 后者让 AI 知道别的会话占着哪些应用（同包不能跨会话并存）。
        val mine = controller.sessionFor(context.sessionId)
        return ToolResult.Success(JsonObject(mapOf(
            "daemon" to JsonPrimitive(controller.isDaemonAlive().toString()),
            "a11y" to JsonPrimitive((requireA11y() != null).toString()),
            "mySession" to if (mine == null) {
                JsonPrimitive("本会话无虚拟屏")
            } else {
                JsonObject(mapOf(
                    "displayId" to JsonPrimitive(mine.displayId),
                    "packageName" to JsonPrimitive(mine.packageName)
                ))
            },
            "activeCount" to JsonPrimitive(controller.activeCount),
            "allSessions" to JsonPrimitive(
                controller.activeSessions().joinToString("; ") {
                    "${it.packageName}(displayId=${it.displayId})"
                }.ifEmpty { "无" }
            ),
            "lastError" to JsonPrimitive(controller.lastError ?: "")
        )))
    }

    // ── 界面感知与操作（需 a11y） ───────────────────────────────────────

    private suspend fun dump(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val session = requireSession(context) ?: return noSessionError()
        val service = requireA11y() ?: return noA11yError()

        val waitReady = args["waitReady"]?.jsonPrimitive?.booleanOrNull ?: false
        // 节点遍历在深树（实测 676 节点）与 waitReady 轮询下会阻塞，别占着默认调度器。
        val tree = withContext(Dispatchers.IO) {
            service.dump(session.displayId, waitReady)
        }
        EventTrace.recordFor(context.sessionId, "VD", "dump displayId=${session.displayId} 输出 ${tree.length} 字符")
        return ToolResult.Success(JsonPrimitive(tree))
    }

    private suspend fun click(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val session = requireSession(context) ?: return noSessionError()
        val service = requireA11y() ?: return noA11yError()

        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val id = args["id"]?.jsonPrimitive?.contentOrNull
        val bounds = args["bounds"]?.jsonPrimitive?.contentOrNull
        val exact = args["exact"]?.jsonPrimitive?.booleanOrNull ?: false
        val index = args["index"]?.jsonPrimitive?.intOrNull ?: 0

        val ok = withContext(Dispatchers.IO) {
            when {
                !text.isNullOrBlank() ->
                    service.clickByText(session.displayId, text, exact, index)
                !id.isNullOrBlank() ->
                    service.clickById(session.displayId, id)
                !bounds.isNullOrBlank() -> {
                    val p = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (p.size == 4) {
                        service.clickByBounds(session.displayId, p[0], p[1], p[2], p[3])
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        val target = text ?: id ?: bounds ?: ""
        EventTrace.recordFor(context.sessionId, "VD", "click [$target] -> $ok")
        return if (ok) {
            ToolResult.Success(JsonObject(mapOf(
                "clicked" to JsonPrimitive(true),
                "target" to JsonPrimitive(target),
                "hint" to JsonPrimitive("建议再 dump 一次确认界面已按预期变化。")
            )))
        } else {
            ToolResult.Error(
                "未能点击「$target」。可能原因：该文本不在当前界面、节点不可点击且无可点击祖先、" +
                    "或界面尚未加载完成（可先 dump 确认实际存在的内容）。",
                code = "CLICK_FAILED"
            )
        }
    }

    private suspend fun input(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val session = requireSession(context) ?: return noSessionError()
        val service = requireA11y() ?: return noA11yError()
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("action=input 需要 text", code = "MISSING_ARG")

        val ok = withContext(Dispatchers.IO) { service.setText(session.displayId, text) }
        EventTrace.recordFor(context.sessionId, "VD", "input ${text.length} 字符 -> $ok")
        return if (ok) {
            ToolResult.Success(JsonObject(mapOf(
                "input" to JsonPrimitive(true),
                "message" to JsonPrimitive("文本已写入当前聚焦的输入框。")
            )))
        } else {
            ToolResult.Error(
                "未找到可编辑的输入框。请先 dump 找到输入框并 click 使其聚焦（或确认界面上有 editable 节点）。",
                code = "INPUT_FAILED"
            )
        }
    }

    private suspend fun swipe(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val session = requireSession(context) ?: return noSessionError()
        val service = requireA11y() ?: return noA11yError()
        val x1 = args["x1"]?.jsonPrimitive?.intOrNull ?: 540
        val y1 = args["y1"]?.jsonPrimitive?.intOrNull ?: 1800
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull ?: 540
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull ?: 600

        val ok = withContext(Dispatchers.IO) { service.swipe(session.displayId, x1, y1, x2, y2) }
        EventTrace.recordFor(context.sessionId, "VD", "swipe ($x1,$y1)->($x2,$y2) -> $ok")
        return if (ok) {
            ToolResult.Success(JsonObject(mapOf("swiped" to JsonPrimitive(true))))
        } else {
            ToolResult.Error("滑动未被执行（手势分发失败）。", code = "SWIPE_FAILED")
        }
    }

    /**
     * 截取虚拟屏画面并存到工作区，返回容器路径供 `viewImage` 查看。
     *
     * 为什么需要它：节点树是**语义**视图，图像/Canvas/WebView/游戏这类内容它表达不了，
     * 而「布局没错但显示异常」的视觉问题也只能看图。两者互补：先 dump 定位控件，看不懂再截图。
     *
     * 落盘位置必须在 `~/workspace` 下（bind 进容器），否则模型无法用任何工具读到该图。
     */
    private suspend fun screenshot(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val session = requireSession(context) ?: return noSessionError()
        val service = requireA11y() ?: return noA11yError()

        // 截图需在主线程发起（无障碍 API 约束）；等待回调的阻塞发生在服务方法内部。
        val bytes = withContext(Dispatchers.IO) {
            runCatching { service.screenshot(session.displayId) }.getOrNull()
        } ?: return ToolResult.Error(
            "截图失败。可能原因：系统版本低于 Android 14（截图需 API 34+）、无障碍服务未声明" +
                "截图能力（重装应用后需重新开启无障碍）、或目标界面尚未绘制完成。" +
                "可先用 dump 确认界面状态。",
            code = "SCREENSHOT_FAILED"
        )

        // 文件名取包名末段并过滤非安全字符，避免路径里混进 / 或 .. 之类的意外。
        val safeName = session.packageName.substringAfterLast('.')
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(20)
            .ifEmpty { "app" }
        val containerPath = "$SHOT_DIR/${safeName}_${System.currentTimeMillis()}.png"
        val hostFile = pathMapper.toHostFile(containerPath)
        val writeErr = runCatching {
            hostFile.parentFile?.mkdirs()
            hostFile.writeBytes(bytes)
        }.exceptionOrNull()
        if (writeErr != null) {
            FileLogger.e(TAG, "截图落盘失败: $containerPath", writeErr)
            return ToolResult.Error("截图已生成但写入失败: ${writeErr.message}", code = "WRITE_FAILED")
        }

        pruneOldScreenshots(hostFile.parentFile)
        EventTrace.recordFor(
            context.sessionId, "VD",
            "screenshot displayId=${session.displayId} ${bytes.size}字节 -> $containerPath"
        )
        return ToolResult.Success(JsonObject(mapOf(
            "path" to JsonPrimitive(containerPath),
            "bytes" to JsonPrimitive(bytes.size),
            "hint" to JsonPrimitive(
                "用 viewImage 传 images=[\"$containerPath\"] 即可查看该截图。" +
                    "dump 给出控件语义，截图给出视觉外观，二者互补。"
            )
        )))
    }

    /** 只保留最近 [MAX_KEPT_SCREENSHOTS] 张，避免截图在工作区里无限累积。 */
    private fun pruneOldScreenshots(dir: java.io.File?) {
        runCatching {
            val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (files.size <= MAX_KEPT_SCREENSHOTS) return
            files.drop(MAX_KEPT_SCREENSHOTS).forEach { it.delete() }
        }.onFailure { FileLogger.w(TAG, "清理旧截图失败", it) }
    }

    // ── 前置校验 ────────────────────────────────────────────────────────

    private fun requireSession(context: AgentContext) = controller.sessionFor(context.sessionId)

    private fun noSessionError() = ToolResult.Error(
        "当前会话还没有虚拟屏，请先 action=open。（注意：虚拟屏按会话隔离，" +
            "其他会话开的屏在这里不可见、也不可操作。）",
        code = "NO_SESSION"
    )

    private fun requireA11y() = VirtualScreenA11yService.instance

    private fun noA11yError() = ToolResult.Error(
        "无障碍服务未开启，无法读取或操作虚拟屏界面。请在系统设置 → 无障碍 中开启「AiCode」，" +
            "然后重试（开屏本身不需要无障碍）。",
        code = "A11Y_NOT_ENABLED"
    )

    private fun Map<String, JsonElement>.intOr(key: String, fallback: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: fallback
}
