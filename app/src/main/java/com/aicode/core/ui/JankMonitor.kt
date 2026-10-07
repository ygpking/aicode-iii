package com.aicode.core.ui

import android.view.Choreographer
import com.aicode.core.util.EventTrace

/**
 * 主线程卡顿帧监控：用 Choreographer 帧回调自循环测相邻帧间隔，间隔超过阈值视为一次卡顿。
 *
 * 上报采用「burst 聚合」：连续卡顿（每一帧都超阈值）期间只累计，等一帧恢复正常才写一行
 * `jank burst`——逐帧落盘会把轨迹刷爆，而用户感知的「一卡」往往是连续多帧的一段。
 * 记录走 EventTrace 的 UI 观察层（scope 固定 "jank"），回合外可写、不污染会话因果链。
 *
 * 线程模型：回调全部在主线程执行，状态为普通字段即可；无分配热路径、无锁。
 * 阈值取 250ms：远超正常帧间隔（60Hz 为 16.7ms），gfxinfo 实测 P99≈28ms，250ms 以上必为
 * 用户可感知的停顿，同时滤掉系统调度抖动。
 */
object JankMonitor {

    private const val SCOPE = "jank"
    private const val JANK_THRESHOLD_NANOS = 250L * 1_000_000

    private var running = false
    private var burstStartNanos = 0L
    private var lastFrameNanos = 0L
    private var burstFrames = 0
    private var burstMaxFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameNanos: Long) {
            if (!running) return
            val last = lastFrameNanos
            lastFrameNanos = frameNanos
            if (last != 0L) {
                val gap = frameNanos - last
                if (gap > JANK_THRESHOLD_NANOS) {
                    if (burstStartNanos == 0L) burstStartNanos = last
                    burstFrames++
                    if (gap > burstMaxFrameNanos) burstMaxFrameNanos = gap
                } else if (burstStartNanos != 0L) {
                    reportBurst(frameNanos)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 主进程启动时调用一次；重复调用无害。:crash 子进程不启动。 */
    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun reportBurst(nowNanos: Long) {
        val frames = burstFrames
        val durationMs = (nowNanos - burstStartNanos) / 1_000_000
        val maxFrameMs = burstMaxFrameNanos / 1_000_000
        burstStartNanos = 0L
        burstFrames = 0
        burstMaxFrameNanos = 0L
        EventTrace.recordFor(
            SCOPE,
            "UI",
            "jank burst 时长=${durationMs}ms 帧数=$frames 最长单帧=${maxFrameMs}ms"
        )
    }
}
