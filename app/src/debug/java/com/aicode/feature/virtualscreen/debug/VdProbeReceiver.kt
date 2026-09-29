package com.aicode.feature.virtualscreen.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import com.aicode.feature.virtualscreen.domain.a11y.VirtualScreenA11yService
import java.io.File

/**
 * **仅 debug 变体**：虚拟屏「感知与操作」自测入口。
 *
 * 存在理由：`VirtualScreenA11yService` 的能力只有真实 AI 会话能触发，而
 * 「dump / click 在虚拟屏上到底行不行」「ACTION_SET_TEXT 中文能不能写进去」
 * 「dispatchGesture 有没有发对屏」这些问题必须在真机上验。
 * 用广播直接驱动**真实服务代码**，比另写一个探针 APK 更可信——测的就是要上线的那份实现。
 *
 * 触发方式（见 AndroidManifest 的 debug 变体声明）：
 * ```
 * am broadcast -n com.aicode.iii.debug/com.aicode.feature.virtualscreen.debug.VdProbeReceiver \
 *   -a com.aicode.vdprobe.CMD --es cmd dump --ei displayId <id>
 * ```
 * 结果写到 `/sdcard/Android/data/<pkg>/files/vdprobe.txt`（该目录无需存储权限，shell 可读）。
 *
 * 注意：本类是 debug 专用，**不会进 release**（在 `src/debug` 源集）。
 */
class VdProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val cmd = intent.getStringExtra("cmd") ?: "status"
        val displayId = intent.getIntExtra("displayId", -1)
        val t0 = SystemClock.elapsedRealtime()
        val sb = StringBuilder()

        fun line(s: String) = sb.append(s).append('\n')

        val svc = VirtualScreenA11yService.instance
        line("cmd=$cmd displayId=$displayId service=${svc != null}")
        if (svc == null) {
            line("ERROR: 无障碍服务未连接，无法自测")
            flush(context, sb, t0)
            return
        }

        try {
            when (cmd) {
                "status" -> {
                    val ws = svc.windowsOn(displayId)
                    line("windowsOn($displayId) = ${ws.size}")
                    ws.forEach { line("  layer=${it.layer} type=${it.type} title=${it.title}") }
                }

                "dump" -> {
                    val waitReady = intent.getBooleanExtra("waitReady", true)
                    val text = svc.dump(displayId, waitReady)
                    line("dump chars=${text.length}")
                    line("----TREE----")
                    line(text)
                    line("----END----")
                }

                "click" -> {
                    val text = intent.getStringExtra("text").orEmpty()
                    val exact = intent.getBooleanExtra("exact", false)
                    val ok = svc.clickByText(displayId, text, exact, 0)
                    line("clickByText(\"$text\", exact=$exact) = $ok")
                    line("----AFTER(top 15 lines)----")
                    line(svc.dump(displayId, false).lineSequence().take(15).joinToString("\n"))
                }

                "input" -> {
                    val text = intent.getStringExtra("text").orEmpty()
                    val ok = svc.setText(displayId, text)
                    line("setText(${text.length} chars) = $ok")
                    line("----AFTER(top 15 lines)----")
                    line(svc.dump(displayId, false).lineSequence().take(15).joinToString("\n"))
                }

                "swipe" -> {
                    // 先记下物理屏顶部文本：若手势误发到物理屏，这里会变——这是回归该 bug 的关键指纹。
                    val ok = svc.swipe(
                        displayId,
                        intent.getIntExtra("x1", 540), intent.getIntExtra("y1", 1800),
                        intent.getIntExtra("x2", 540), intent.getIntExtra("y2", 600)
                    )
                    line("swipe = $ok")
                    line("----AFTER(top 15 lines)----")
                    line(svc.dump(displayId, false).lineSequence().take(15).joinToString("\n"))
                }

                "pdump" -> {
                    // 读物理屏（displayId=0）作为对照，用于确认虚拟屏操作确实没动物理屏。
                    val text = svc.dump(0, false)
                    line("physical dump chars=${text.length}")
                    line("----PHYSICAL----")
                    line(text.lineSequence().take(20).joinToString("\n"))
                }

                else -> line("ERROR: 未知 cmd=$cmd")
            }
        } catch (t: Throwable) {
            line("EXCEPTION: $t")
        }

        flush(context, sb, t0)
    }

    private fun flush(context: Context, sb: StringBuilder, t0: Long) {
        sb.append("elapsedMs=").append(SystemClock.elapsedRealtime() - t0).append('\n')
        val body = sb.toString()
        FileLogger.i(TAG, "自测完成: ${body.lineSequence().firstOrNull()}")
        // 外部私有目录：无需任何存储权限，shell 可直接读，便于从容器/Shizuku 侧取结果。
        runCatching {
            File(context.getExternalFilesDir(null), FILE_NAME).writeText(body, Charsets.UTF_8)
        }.onFailure { FileLogger.e(TAG, "写自测结果失败", it) }
    }

    private companion object {
        const val TAG = "VdProbe"
        const val ACTION = "com.aicode.vdprobe.CMD"
        const val FILE_NAME = "vdprobe.txt"
    }
}
