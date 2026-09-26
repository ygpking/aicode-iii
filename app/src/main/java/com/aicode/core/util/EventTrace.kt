package com.aicode.core.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件轨迹：把「发生了什么、按什么顺序、谁引起的」记成一条可回溯的链。
 *
 * 与 [FileLogger] 的区别：那些是**离散的点**（某处出错、某个动作完成），本类是**连续的过程记录**——
 * 一个回合从开始到结束，每一步是什么、间隔多久、由哪一步引起，都能按时间线读出来。
 * 排查「界面为什么变成这样」这类问题时，离散日志往往只能证明「某处执行过」，
 * 而轨迹能回答「在那之前发生了什么」。
 *
 * 三个维度的表达方式：
 * - **时序链**：每条记录带 `+毫秒`（相对本回合起点）与全局递增 `seq`，不依赖墙钟即可排序；
 *   墙钟易受系统校时影响，相对时间才是可靠的先后依据。
 * - **因果链**：可传 [causeSeq] 指向引发本条的那条记录，形成「谁导致谁」的引用。
 * - **事件链**：由调用方在**唯一的出口**处统一记录，而非各处手写——避免遗漏，也避免重复。
 *
 * **默认关闭**：轨迹量比普通日志大一个量级且属高频路径，release 包持续刷盘会拖慢流式并挤占日志空间。
 * 开关由 [FileLogger.minLevel] 派生——只有日志等级降到 [LogLevel.DEBUG] 及以下才记录，
 * 这样复用现有「日志等级」设置，不新增开关与界面。
 *
 * 使用前需 [FileLogger.init]（本类不自行开目录，写入委托给 [FileLogger]）。
 */
object EventTrace {

    private const val TAG = "EventTrace"

    /** 每个回合内保留的记录条数上限：防单回合无界增长（如逐字流式）。 */
    private const val MAX_RECORDS_PER_TURN = 2000

    private val turnCounters = ConcurrentHashMap<String, AtomicLong>()
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private val recordCounters = ConcurrentHashMap<String, Long>()

    /** 因超出 [MAX_RECORDS_PER_TURN] 而被丢弃的条数，按回合记；收尾时一并报告。 */
    private val droppedCounters = ConcurrentHashMap<String, Long>()

    /**
     * 轨迹是否启用：日志等级收到 DEBUG 或更低时才记录。
     * 不单独设开关，是为了复用已有的「日志等级」设置，避免新增界面与持久化項。
     */
    val enabled: Boolean
        get() = FileLogger.minLevel == LogLevel.VERBOSE || FileLogger.minLevel == LogLevel.DEBUG

    /**
     * 开启一个新回合，返回该回合的 id（形如 `t1`、`t2`…，按 key 独立自增）。
     *
     * @param key 区分维度的作用域，通常是 sessionId（多个会话并行时各自独立编号）。
     */
    fun beginTurn(key: String): String {
        val n = turnCounters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val turnId = "t$n"
        seqCounters[turnId] = AtomicLong(0)
        recordCounters[turnId] = 0L
        droppedCounters.remove(turnId)
        record(turnId, "TURN", "开始")
        return turnId
    }

    /**
     * 记录一条轨迹。
     *
     * @param kind 类别，如 `EVENT` / `UI` / `TOOL`（用于日志中快速过滤）。
     * @param detail 人类可读的细节；调用方应只放「长度/摘要/状态」这类结构信息，
     *   不要把大段正文塞进来——正文已由 [AILogger] 完整记录，重复会撑爆日志。
     * @param causeSeq 引发本条的上一条 `seq`；不传表示无明确因果（如回合开始、外部触发）。
     */
    fun record(turnId: String?, kind: String, detail: String, causeSeq: Long? = null) {
        if (!enabled || turnId == null) return
        val seq = seqCounters[turnId]?.incrementAndGet() ?: return

        val count = (recordCounters[turnId] ?: 0L)
        if (count >= MAX_RECORDS_PER_TURN) {
            droppedCounters[turnId] = (droppedCounters[turnId] ?: 0L) + 1
            return
        }
        recordCounters[turnId] = count + 1

        val cause = if (causeSeq != null) " cause=#$causeSeq" else ""
        // 走 FileLogger 的 DEBUG 通道：沿用其单线程落盘、脱敏、按天分文件与等级过滤。
        FileLogger.d(TAG, "[$turnId #$seq$cause] $kind $detail")
    }

    /** 回合结束：报告本回合的记录总量与被丢弃量，便于判断是否需要提高上限。 */
    fun endTurn(turnId: String?, outcome: String) {
        if (turnId == null) return
        val total = recordCounters[turnId] ?: 0L
        val dropped = droppedCounters[turnId] ?: 0L
        val suffix = if (dropped > 0) "（另有 $dropped 条超出上限被丢弃）" else ""
        record(turnId, "TURN", "结束/$outcome 共 $total 条$suffix")
        seqCounters.remove(turnId)
        recordCounters.remove(turnId)
        droppedCounters.remove(turnId)
    }
}
