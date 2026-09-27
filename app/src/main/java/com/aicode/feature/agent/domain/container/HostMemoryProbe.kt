package com.aicode.feature.agent.domain.container

import android.app.ActivityManager
import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宿主内存探针：在启动高内存开销任务前读一次整机内存水位，偏低时给出警告。
 *
 * ## 为什么要探宿主而不是容器
 *
 * 容器（proot）进程的内存**计入宿主 App**，被系统回收时看的是**整机压力**而非 App 自身占用
 * （实测崩溃时 App 仅 198MB，仍被 `reason=3 (LOW_MEMORY)` 杀掉）。因此：
 * - 容器内 `free` 看不到有意义的信息；
 * - 用户「清理本 App 内存」也无效——LMK 判断的是全局可用内存。
 *
 * 唯一有意义的观测点是从 App 侧读 [ActivityManager.MemoryInfo]，它直接反映系统视角的
 * 可用内存与低内存标志。
 *
 * ## 用法
 *
 * 只作**提示**，不作拦截：内存偏低时把警告附在工具输出里，让 AI 与用户都知情，
 * 由他们决定是否继续（拦截会误伤「内存刚好在边界但任务很小」的正常场景）。
 */
@Singleton
class HostMemoryProbe @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HostMemoryProbe"

        /** 可用内存低于该值时提示：经验阈值，低于它启动编译容易触发回收。 */
        const val LOW_AVAIL_BYTES = 1_500L * 1024 * 1024

        /** 可用内存占比低于该值同样提示（大内存机型上绝对值可能仍高，但比例已紧张）。 */
        const val LOW_AVAIL_RATIO = 0.12
    }

    /** 一次采样结果。 */
    data class Snapshot(
        val availBytes: Long,
        val totalBytes: Long,
        val lowMemory: Boolean,
        val thresholdBytes: Long,
    ) {
        val availMb: Long get() = availBytes / 1024 / 1024
        val totalMb: Long get() = totalBytes / 1024 / 1024
    }

    /**
     * 读取当前内存水位；读取失败返回 null（诊断不应影响主流程）。
     */
    fun sample(): Snapshot? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val threshold = maxOf(
            LOW_AVAIL_BYTES,
            (info.totalMem * LOW_AVAIL_RATIO).toLong(),
        )
        Snapshot(
            availBytes = info.availMem,
            totalBytes = info.totalMem,
            lowMemory = info.lowMemory || info.availMem < threshold,
            thresholdBytes = threshold,
        )
    }.onFailure { FileLogger.w(TAG, "读取内存信息失败: ${it.message}") }.getOrNull()

    /**
     * 为「即将执行高内存任务」生成的警告文本；内存充足时返回 null。
     *
     * @param what 正在启动的事物的描述，用于让提示可读（如「构建命令」）。
     */
    fun warnIfLow(what: String): String? {
        val snap = sample() ?: return null
        if (!snap.lowMemory) return null
        return "宿主可用内存偏低（${snap.availMb}MB / 共 ${snap.totalMb}MB，阈值 " +
            "${snap.thresholdBytes / 1024 / 1024}MB）。容器进程的内存计入 App，" +
            "启动$what 可能触发系统低内存回收、造成 App 被强制结束（表现为无报错的突然重启）。" +
            "建议先关闭其它占用内存的应用，或拆小任务分步执行。"
    }
}
