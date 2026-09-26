package com.aicode.core.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级长任务保活总管：全进程只持有一份 CPU 唤醒锁（[PowerManager.PARTIAL_WAKE_LOCK]）与
 * 一份 Wi-Fi 锁，供各长任务（AI 流式推理、容器内构建等）按「租约」共享。
 *
 * 背景：唤醒锁若由各子系统自持，任一子系统先退出就可能提前解锁——agent 结束释放锁后，
 * 仍在跑的容器构建会被系统冻结；终端后台任务只起了前台服务却没拿锁，锁屏后 CPU 挂起、
 * 构建中断。集中到本单例并用引用计数后，「只要还有一个长任务在跑，锁就不会松」。
 *
 * 用法：长任务开始时 [acquireLease] 拿一个 [ProcessingPowerLease]，结束时 `close()`
 * （推荐 `use {}`）。首个租约加锁、最后一个租约释放才解锁。
 *
 * 锁异常一律吞掉并记日志：保活尽力而为，不应因拿锁失败影响任务本身。
 * 唤醒锁带 [WAKE_TIMEOUT_MS] 兜底超时，防止租约泄漏导致永久持锁耗电。
 */
@Singleton
class RuntimeLifecycleSupervisor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** 不计数（setReferenceCounted(false)），生命周期完全由 [leaseCount] 决定。 */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }
    }

    /** 仅在有 Wi-Fi 服务时创建；无 Wi-Fi 时拿锁无意义。 */
    private val wifiLock by lazy {
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:Wifi")
            ?.apply { setReferenceCounted(false) }
    }

    private val leaseCount = AtomicInteger(0)

    /** 当前活跃租约数（仅用于日志与调试）。 */
    val activeLeases: Int get() = leaseCount.get()

    /**
     * 申请一个保活租约。持有时保证 CPU 不休眠、Wi-Fi 不进入省电休眠。
     * 调用方负责在长任务结束时调用返回值的 [ProcessingPowerLease.close]。
     */
    @Synchronized
    fun acquireLease(holder: String): ProcessingPowerLease {
        val count = leaseCount.incrementAndGet()
        // 不只在 count==1 时加锁：唤醒锁带 1h 兜底超时，超时后系统会自动释放但 leaseCount 仍 >0
        // （如后台 dev server 长期持租约）。若只在 count==1 加锁，超时后再也不会重新获取，
        // 与「只要还有长任务在跑锁就不松」相悖。故每次申请都检查，未持有则补获（含超时自愈）。
        if (!wakeLock.isHeld) {
            runCatching { wakeLock.acquire(WAKE_TIMEOUT_MS) }
                .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
        }
        wifiLock?.takeIf { !it.isHeld }?.let {
            runCatching { it.acquire() }
                .onFailure { e -> FileLogger.e(TAG, "acquire wifiLock failed", e) }
        }
        FileLogger.d(TAG, "acquireLease($holder) active=$count")
        return ProcessingPowerLease(holder, ::releaseLease)
    }

    @Synchronized
    private fun releaseLease(holder: String) {
        val count = leaseCount.updateAndGet { if (it > 0) it - 1 else 0 }
        if (count == 0) {
            if (wakeLock.isHeld) {
                runCatching { wakeLock.release() }
                    .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
            }
            wifiLock?.takeIf { it.isHeld }?.let {
                runCatching { it.release() }
                    .onFailure { e -> FileLogger.e(TAG, "release wifiLock failed", e) }
            }
        }
        FileLogger.d(TAG, "releaseLease($holder) active=$count")
    }

    /** 一个保活租约；重复 [close] 幂等（不会把计数减成负）。 */
    inner class ProcessingPowerLease internal constructor(
        private val holder: String,
        private val onClose: (String) -> Unit
    ) : AutoCloseable {

        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            onClose(holder)
        }
    }

    private companion object {
        const val TAG = "AiCode:Lifecycle"
        /** 唤醒锁兜底超时：构建、装依赖等长任务给足 1 小时；任务正常结束会主动释放。 */
        const val WAKE_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
