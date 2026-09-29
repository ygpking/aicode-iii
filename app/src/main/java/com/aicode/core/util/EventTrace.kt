package com.aicode.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局事件轨迹：跨层的结构化事实流，回答「谁、在何时、对什么、做了什么、由谁引起」。
 *
 * ## 与另两个日志的分工（三者职责不重叠）
 *
 * | 层 | 内容 | 默认 |
 * | --- | --- | --- |
 * | [FileLogger] | 调试日志（异常、分支、耗时） | release 只记 INFO 及以上 |
 * | [AILogger] | 模型交互**原文**（请求体、响应、原始 SSE） | 记录 |
 * | [EventTrace] | **跨层事件事实**（状态如何一步步变成现在这样） | **记录** |
 *
 * ## 为什么独立成层、且默认开启
 *
 * 用户报告的多是「界面状态不对」类问题（面板消不掉、消息乱序）。这类问题的共同点是
 * **跨层**：模型吐了什么、工具写了什么、UI 渲染成什么，三者要对齐才能定位。
 * 而它**只在日常使用中复现**，不是调试期才出现——若把轨迹挂在语言等级（DEBUG）之下，
 * release 包默认不记录，用户复现后拿不出任何证据，等于没做。
 *
 * 因此本层不参与 [LogLevel] 的常规过滤：只要用户没有把日志等级显式设为 [LogLevel.NONE]，
 * 就一直记录（[enabled]）。写入量靠下面两条约束控制，而不是靠默认关闭：
 * - **只记状态变化，不记增量流**：逐字流式不记，只记「开始/结束/阶段切换」（由 [AgentEventTracer] 保证）；
 * - 独立文件 + 按大小轮转 + 按天清理，占用有上限。
 *
 * ## 三个维度如何表达
 * - **事件链**：各层在**唯一出口**统一记录，而非散落各处手写，避免遗漏与重复。
 * - **时序链**：每条带全局递增 `seq`；顺序以 seq 为准，不依赖墙钟（墙钟会被系统校时影响）。
 * - **因果链**：每条默认指向**同回合的上一条**（`causeSeq` 可显式覆盖），使链路从起因到结果可一路倒推、不断档。
 *
 * 使用前需在 [android.app.Application.onCreate] 调用一次 [init]。
 */
object EventTrace {

    private const val TAG = "EventTrace"
    private const val DIR_NAME = "traces"

    /** 单个轨迹文件上限 4MB；超出后轮转（保留 [MAX_ROTATIONS] 代）。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_ROTATIONS = 3

    private const val MAX_AGE_DAYS = 7

    /** 单回合记录上限：防止单轮异常刷屏（正常一轮数百条）。 */
    private const val MAX_RECORDS_PER_TURN = 2000

    /** 无回合上下文的丢弃告警频率：首条 + 每这么多条一次。 */
    private const val ORPHAN_REPORT_EVERY = 50L

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-trace").apply { isDaemon = true }
    }
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    @Volatile
    private var logDir: File? = null

    // 以下仅由 ioExecutor 单线程访问。
    private var writer: java.io.BufferedWriter? = null
    private var writerDay: String? = null

    private val turnCounters = ConcurrentHashMap<String, AtomicLong>()
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private val recordCounters = ConcurrentHashMap<String, Long>()
    private val droppedCounters = ConcurrentHashMap<String, Long>()
    private val activeTurns = ConcurrentHashMap<String, String>()

    /** 无回合上下文而被丢弃的记录数，用于限频告警。 */
    private val orphanDropped = AtomicLong()

    /**
     * 是否记录。
     *
     * 刻意**不与常规等级阈值比较**：release 默认 INFO，若要求 DEBUG 才记录，正式包将永不产生轨迹，
     * 而用户遇到的问题正是在正式包上复现的。只有显式选择 NONE（完全关闭日志）才停止记录。
     */
    val enabled: Boolean
        get() = FileLogger.minLevel != LogLevel.NONE

    /** 初始化轨迹目录。重复调用安全。 */
    fun init(context: Context) {
        if (logDir != null) return
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME).apply { mkdirs() }
        logDir = dir
        ioExecutor.execute {
            cleanupOldLogs(dir)
            reportPreviousExit(dir)
            writeLifecycleMarker(dir, "PROCESS START")
        }
        FileLogger.i(TAG, "事件轨迹目录: ${dir.absolutePath}（等级 ${FileLogger.minLevel}，记录=${enabled}）")
    }

    /**
     * 进程正常退出时调用（如 [android.app.Application.onTerminate]）写下的收尾标记。
     * 该标记的存在与否，是事后判「上次是正常退出还是被杀」的**唯一依据**。
     */
    private const val MARKER_FILE = "process.marker"

    /**
     * 标记进程正常退出。进程被杀时不会执行到这里，标记也不会更新——正是这个差异让「被杀」可被证实。
     *
     * 注意：[android.app.Application.onTerminate] 在真机上几乎不会被调用（杀进程时不会走正常退出流程），
     * 所以本标记主要靠「不写」来传递信号：写成功说明是温和退出，没写说明上一次是被杀的。
     */
    fun markCleanExit() {
        val dir = logDir ?: return
        ioExecutor.execute { writeLifecycleMarker(dir, "PROCESS STOP") }
    }

    private fun writeLifecycleMarker(dir: File, text: String) {
        runCatching {
            val f = File(dir, MARKER_FILE)
            // 先写时序行记入当天日志，再把标记文件更新为「最后一次已知状态」。
            // 标记文件内容存时间戳：下次启动时可算出中断时长。
            val now = System.currentTimeMillis()
            appendLine(dir, "${timestampFormat.format(Instant.ofEpochMilli(now))}  -      -    -    -  LIFECYCLE  $text\n")
            f.writeText("$text|$now")
        }.onFailure { Log.e(TAG, "写进程标记失败", it) }
    }

    /**
     * 启动时检查上一次退出方式。
     *
     * 若上次写的是 `PROCESS START` 而没有对应的 `PROCESS STOP`，说明进程**没有走正常退出流程就消失了**
     * ——即被系统杀掉（低内存或后台限制）。这是目前唯一能在下次启动时自动认定「上次被杀」的手段：
     * 被杀时异常处理器与 finally 都不会执行，不会有堆栈，日志里只有一段空白。
     */
    private fun reportPreviousExit(dir: File) {
        runCatching {
            val f = File(dir, MARKER_FILE)
            if (!f.isFile) return@runCatching
            val parts = f.readText().split('|')
            val state = parts.getOrNull(0)?.trim()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (state != "PROCESS START" || at == null) return@runCatching
            val gapSec = (System.currentTimeMillis() - at) / 1000
            appendLine(
                dir,
                "${timestampFormat.format(Instant.now())}  -      -    -    -  LIFECYCLE  " +
                    "上次未正常退出（距今 ${gapSec}s）——无 STOP 标记，判定为进程被杀非异常崩溃\n"
            )
        }.onFailure { Log.e(TAG, "检查上次退出状态失败", it) }
    }

    /** 直接向当天轨迹文件追加一行（供生命周期标记使用，不经 writer 缓冲）。 */
    private fun appendLine(dir: File, line: String) {
        runCatching {
            val day = dayFormat.format(Instant.now())
            File(dir, "trace-$day.log").appendText(line)
        }.onFailure { Log.e(TAG, "追加轨迹行失败", it) }
    }

    /** 返回轨迹文件列表（含轮转归档），供占用统计与查看界面使用。 */
    fun listTraceFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("trace-") }?.sortedBy { it.name } ?: emptyList()
    }

    /** 轨迹占用字节数（含轮转归档）；供存储统计使用。 */
    fun totalBytes(): Long = listTraceFiles().sumOf { it.length() }

    /**
     * 清空轨迹文件（含轮转归档），返回释放的字节数。
     *
     * 只删轨迹，不动 [MARKER_FILE]：它是「上次是否正常退出」的唯一依据，删了会让下次启动误报被杀。
     * 删除必须排到 [ioExecutor] 上并**先关闭写入句柄**（同 [FileLogger.clearLogs]）：当天的文件正被
     * [writer] 持有，不关就删的话，后续写入会继续落进已删除的 inode——文件看不见却仍占空间。
     */
    fun clearTraceFiles(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                writer?.close()
                writer = null
                writerDay = null
                listTraceFiles().forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
            }.onFailure { Log.e(TAG, "清空事件轨迹失败", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    /**
     * 开启一个新回合，返回回合 id（形如 `t1`、`t2`…，按 [key] 独立自增）。
     *
     * @param key 作用域，通常是 sessionId——多会话并行时各自独立编号，避免混线。
     */
    fun beginTurn(key: String): String {
        // 开启新回合前，先看同作用域上一回合是否留有未收尾状态。
        // 这是「被杀」在日志里唯一的自动痕迹：进程突然消失时，finally 与异常处理都不执行，
        // 上一回合永远等不到 endTurn。检测到就写一行显式说明，而不是留下一段无解释的空白。
        activeTurns[key]?.let { stale ->
            if (seqCounters.containsKey(stale)) {
                val total = recordCounters[stale] ?: 0L
                record(
                    stale, key, "TURN",
                    "上一回合 $stale 未见收尾（已记 $total 条）——进程可能被系统杀掉，非正常结束"
                )
            }
        }
        val n = turnCounters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val turnId = "t$n"
        seqCounters[turnId] = AtomicLong(0)
        recordCounters[turnId] = 0L
        droppedCounters.remove(turnId)
        activeTurns[key] = turnId
        record(turnId, key, "TURN", "轮次开始")
        return turnId
    }

    /**
     * 记录一条事件。
     *
     * @param layer 层标签，便于过滤：`TURN`（回合起止）/ `EVENT`（Agent 事件，经 [AgentEventTracer]）/
     *   `UI`（界面状态跳变）/ `SNAPSHOT/<kind>`（收尾残留清点）。**只列实际在用的**——
     *   历史上这里还写过 `TOOL`/`SESSION`，但从未有调用方，属「文档说有、实际没有」，已删。
     * @param detail 人类可读细节。**只放结构与度量**（长度、数量、状态、标识），不要塞正文——
     *   正文已由 [AILogger] 完整留存，重复只会撑爆轨迹。
     * @param causeSeq 引发本条的上一条 `seq`；不传时**默认指向同回合的上一条**（有更精确的因果关系时显式传入覆盖）。
     * @return 本条分配到的 `seq`；未记录（未启用 / 回合未知 / 超上限）时返回 null。
     */
    fun record(turnId: String?, scope: String?, layer: String, detail: String, causeSeq: Long? = null): Long? {
        if (!enabled) return null
        if (turnId == null) {
            // 原先直接 return，让轨迹出现无法解释的空白（而本模块的设计初衷恰恰是
            // 「不要留下无解释的空白」）。改为限频落一行，不再静默。
            noteOrphan(scope, layer, detail, why = "未携带回合上下文")
            return null
        }
        // 先判上限、再取 seq：seq 只为「已接受」的记录分配，保证连续无空洞，
        // 于是「上一条 = seq-1」恒成立（否则默认因果会指向被丢弃的序号）。
        val count = recordCounters[turnId] ?: 0L
        if (count >= MAX_RECORDS_PER_TURN) {
            droppedCounters[turnId] = (droppedCounters[turnId] ?: 0L) + 1
            return null
        }
        val seqCounter = seqCounters[turnId]
        if (seqCounter == null) {
            // 回合已 endTurn（或从未 beginTurn）却仍在记录：同样不能静默。
            noteOrphan(scope, layer, detail, why = "回合 $turnId 已收尾")
            return null
        }
        val seq = seqCounter.incrementAndGet()
        recordCounters[turnId] = count + 1

        write(turnId, scope, seq, layer, detail, causeSeq ?: (seq - 1).takeIf { it >= 1 })
        return seq
    }

    /**
     * 记录一条「本该进轨迹但进不去」的丢弃事实——限频：首条 + 每 [ORPHAN_REPORT_EVERY] 条一次，
     * 避免它自己刷屏（与 [MAX_RECORDS_PER_TURN] 的用意一致）。
     */
    private fun noteOrphan(scope: String?, layer: String, detail: String, why: String) {
        val n = orphanDropped.incrementAndGet()
        if (n != 1L && n % ORPHAN_REPORT_EVERY != 0L) return
        write(
            turnId = "-", scope = scope, seq = 0L, layer = "TRACE_DROPPED",
            detail = "$why，本条未入轨迹（累计 $n 条）: [$layer] $detail", causeSeq = null
        )
    }

    /**
     * 按作用域记录（供拿不到 turnId 的层使用，如工具内部、DAO）。
     *
     * 动机：回合 id 只在上层持有，工具内部发生的事（写库、清状态）原本无法归入当前回合，
     * 于是「面板创建了却没被清理」这类跨层事实永远进不了日志。按 key 反查当前回合，
     * 任何一层都能把事件挂到正在进行的时间线上；无活动回合时静默丢弃。
     */
    fun recordFor(scope: String?, layer: String, detail: String) {
        if (!enabled || scope == null) return
        val turnId = activeTurns[scope]
        if (turnId == null) {
            noteOrphan(scope, layer, detail, why = "作用域 $scope 无活跃回合")
            return
        }
        // scope 一并带上：反查得到的回合号在日志里不够用，仍需标明是哪个会话
        record(turnId, scope, layer, detail)
    }

    /**
     * 记录一条**状态快照**：描述「此刻什么东西仍然存在」，而非「发生了什么动作」。
     *
     * 「任务已完成但面板不消失」这类缺陷的共性是**该清理的没被清理**——没有任何代码会产生日志，
     * 被动记录永远看不见。只能在收尾等处主动清点，故单独提供语义明确的入口，
     * 便于事后过滤出所有 SNAPSHOT 行直接看「结束时还剩什么」。
     */
    fun snapshot(scope: String?, kind: String, detail: String) {
        recordFor(scope, "SNAPSHOT/$kind", detail)
    }

    /** 回合结束：报告总量与被丢弃量，便于判断是否需要提高上限。 */
    fun endTurn(turnId: String?, scope: String?, outcome: String) {
        if (turnId == null) return
        val total = recordCounters[turnId] ?: 0L
        val dropped = droppedCounters[turnId] ?: 0L
        val suffix = if (dropped > 0) "（另有 $dropped 条超上限被丢弃）" else ""
        record(turnId, scope, "TURN", "轮次结束/$outcome 共 $total 条$suffix")
        seqCounters.remove(turnId)
        recordCounters.remove(turnId)
        droppedCounters.remove(turnId)
        activeTurns.entries.removeIf { it.value == turnId }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────

    /**
     * 行格式（定宽字段 + 人类可读，便于 grep 与肉眼阅读）：
     * ```
     * 2026-09-26 22:59:01.123  s=a1b2c3d4 t3 #42 ←#41  TOOL  todo 写入 5 项 [completed=5] 已全部完成
     * ```
     * 字段依次为：时间、会话短号、回合号、序号、因果来源（指向同回合上一条）、层标签、细节。
     */
    private fun write(turnId: String, scope: String?, seq: Long, layer: String, detail: String, causeSeq: Long?) {
        val dir = logDir ?: return
        val now = Instant.now()
        val session = scope?.take(8) ?: "-"
        val cause = if (causeSeq != null) " ←#$causeSeq" else ""
        val line = "${timestampFormat.format(now)}  s=$session $turnId #$seq$cause  $layer  ${MediaRedactor.redact(detail)}\n"
        ioExecutor.execute {
            runCatching {
                val day = dayFormat.format(now)
                val file = File(dir, "trace-$day.log")
                if (writerDay != day) {
                    writer?.close()
                    writer = FileOutputStream(file, true).bufferedWriter()
                    writerDay = day
                } else if (file.length() > MAX_FILE_BYTES) {
                    rotate(file, day)
                    writer = FileOutputStream(file, true).bufferedWriter()
                }
                writer?.append(line)
                writer?.flush()
            }.onFailure { Log.e(TAG, "写入事件轨迹失败", it) }
        }
    }

    /**
     * 轮转：把当前文件依次后移（`.1` → `.2` …），超出代数的最旧一份删除。
     * 与 [FileLogger]/[AILogger] 的「只留一代」不同，这里保留 [MAX_ROTATIONS] 代——
     * 轨迹是排查依据，多留几代才算「有历史可查」。
     */
    private fun rotate(file: File, day: String) {
        runCatching { writer?.close() }
        val dir = file.parentFile ?: return
        for (i in MAX_ROTATIONS - 1 downTo 1) {
            val src = File(dir, "trace-$day.log.$i")
            if (src.isFile) runCatching { src.renameTo(File(dir, "trace-$day.log.${i + 1}")) }
        }
        runCatching { file.renameTo(File(dir, "trace-$day.log.1")) }
    }

    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("trace-") }?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
