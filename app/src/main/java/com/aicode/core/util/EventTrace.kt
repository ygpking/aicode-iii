package com.aicode.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    /** 轨迹目录名。与 [com.aicode.feature.agent.domain.container.ContainerInstaller.diagnosticViewBindings]
     *  的只读视图绑定保持一致——有单测守护这层对应关系。 */
    internal const val DIR_NAME = "traces"

    /** 单个轨迹文件上限 4MB；超出后轮转（保留 [MAX_ROTATIONS] 代）。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_ROTATIONS = 3

    private const val MAX_AGE_DAYS = 7

    /** 单回合记录上限：防止单轮异常刷屏（正常一轮数百条）。 */
    private const val MAX_RECORDS_PER_TURN = 2000

    /** 无回合上下文的丢弃告警频率：首条 + 每这么多条一次。 */
    private const val ORPHAN_REPORT_EVERY = 50L

    /**
     * 回合外的观察类记录（UI / SNAPSHOT）挂靠的虚拟回合号。
     *
     * 用 `t0` 而非 `t-outside` 之类的名字：现有解析器与 `EventTrace` 自身的行格式
     * 都按 `t<数字>` 识别回合，保持同一形态不需要下游额外适配。
     * 语义上 0 号回合表「不属于任何真实回合的外部观察」，`endTurn` 不会去清它。
     */
    private const val OUT_OF_TURN = "t0"

    /**
     * 轨迹行里的「会话短号 + 回合号」桶标识，如 `s=8bd16761 t3`。
     *
     * 高水位恢复与未收尾清点共用它，保证两处对「什么是回合记录」的判定一致。
     */
    private val TURN_BUCKET_RE = Regex("""s=(\S+)\s+t(\d+)\s+#\d+""")

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

    /**
     * 回合号的下限：各会话在上个进程里用到的最大 `tN`，按**会话短号**索引。
     *
     * 为什么需要它：[turnCounters] 是内存态，进程重启即清零，于是同一会话的 `t1` 会在
     * **每个进程段**重新发放。而轨迹里只写会话短号（`s=` 取前 8 位），`turnId` 又只在会话内
     * 唯一，两者相乘的结果是「`s=<会话> t<N> #<seq>`」这份曾被当作唯一标识的引用**跨进程失效**。
     * 真机实测（10-05 单日）：36/70 个回合桶被复用，7293/10671 条（68.4%）记录落在复用桶里——
     * 按该引用倒查会指到另一个时刻的另一个回合，比没有引用更危险。
     *
     * 索引用短号而非完整 sessionId：高水位从轨迹文件里回读，而文件里只写短号（[write] 的
     * `scope.take(8)`），保持同一形态才不需要额外映射。
     */
    private val turnFloor = ConcurrentHashMap<String, Long>()

    /** 高水位是否已回读。首次 [beginTurn] 前必须为 true，否则会发放重复的回合号。 */
    private val turnFloorLoaded = AtomicBoolean(false)

    /**
     * 回合内「业务键 → 已分配 seq」的绑定，用于表达**真实因果**。
     *
     * 起因：默认因果是「同回合上一条」（[record] 的 `causeSeq` 缺省值），但工具调用是**交错**的——
     * 实测单日 1640 条 `tool_finished` 里 583 条（35.6%）的默认因果指向了另一个工具，
     * 而非自己那次 `tool_started`（交错 277 次、涉及 37 个回合）。此时「上一条」是并发的邻居，
     * 不是它的原因，链看着连续却指错了人。绑定后由调用方显式指回真正的发起记录。
     */
    private val boundCauses = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

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
        // 版本标识：轨迹里此前**没有任何版本信息**，而正式包会随升级换代——
        // 事后看到一段异常轨迹时，无法判断它属于哪个构建，只能靠文件时间猜。
        // 读 packageManager 而非 BuildConfig：无需开 buildFeatures.buildConfig，也不改构建脚本。
        val version = appVersionTag(context)
        ioExecutor.execute {
            cleanupOldLogs(dir)
            // 顺序不可调：先判上次退出方式，再清点上轮未收尾回合（两者都要读「上一个进程写下的内容」），
            // 最后才写本次 START——否则 START 会混进被扫描区间，把上轮残局掩盖掉。
            reportPreviousExit(dir)
            reportStaleTurns(dir)
            // 高水位回读同理必须在写本次 START **之前**：它扫的区间也是「自最后一个 START 起」，
            // 而本次 START 一旦落盘就成了新的「最后一个 START」，区间被截断到本进程，
            // 上个进程的回合号再也读不到（那会让修复静默失效）。
            ensureTurnFloor()
            appendLine(
                dir,
                // 末尾必须带 \n：appendLine 只做 appendText，不像同名的 Kotlin appendLine 会自动补行。
                // 漏了它会把本行与紧随其后的 PROCESS START 拼成一行（实测踩过）。
                "${timestampFormat.format(Instant.now())}  -      -    -    -  LIFECYCLE  APP VERSION $version" +
                    " android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) abi=${Build.SUPPORTED_ABIS.firstOrNull()}\n"
            )
            writeLifecycleMarker(dir, "PROCESS START")
        }
        FileLogger.i(TAG, "事件轨迹目录: ${dir.absolutePath}（等级 ${FileLogger.minLevel}，记录=${enabled}，版本 $version）")
    }

    /** 形如 `v1.13.1(52)`；取不到时返回 `v?`。versionCode 用 longVersionCode 兼容 64 位（API 28+）。 */
    private fun appVersionTag(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        "v${pi.versionName}($code)"
    }.getOrDefault("v?")

    /**
     * 进程离开前台时调用（由 [installBackgroundMarker] 挂在 ProcessLifecycleOwner 的 ON_STOP）。
     * 该标记的存在与否，是事后判「上次是否走过退出流程」的依据之一（**不是**被杀的铁证，见下）。
     */
    private const val MARKER_FILE = "process.marker"

    /**
     * 标记「进程离开前台」。
     *
     * 调用方是 [androidx.lifecycle.ProcessLifecycleOwner] 的 `ON_STOP`（见 AIEditorApp），
     * 而非 [android.app.Application.onTerminate]——后者在真机上几乎不被调用（系统回收进程时不走
     * 正常退出流程），实测结果是「PROCESS STOP 记录恒为 0」，标记永远停在 START。
     * 改用生命周期回调后，切后台即可写下标记；代价是它表达的是「离开前台」而非「干净退出」，
     * 故判「被杀」时只作参考，不下断言。
     */
    fun markCleanExit() {
        val dir = logDir ?: return
        ioExecutor.execute { writeLifecycleMarker(dir, "PROCESS STOP") }
    }

    /**
     * 注册「离开前台」自动标 STOP。
     *
     * 挂在 [androidx.lifecycle.ProcessLifecycleOwner] 的 `ON_STOP`（App 进后台即触发），
     * 取代原先依赖 `Application.onTerminate` 的做法——后者在真机上几乎不被调用，
     * 导致 STOP 恒不落盘（实测 19 次启动 / 0 次 STOP）。
     *
     * 只挂一次（同一进程内重复调用无效）。
     */
    fun installBackgroundMarker() {
        if (!backgroundMarkerInstalled.compareAndSet(false, true)) return
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
                .addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                        markCleanExit()
                    }
                })
        }.onFailure { Log.e(TAG, "注册后台标记失败", it) }
    }

    private val backgroundMarkerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

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
     * 若上次写的是 `PROCESS START` 而没有对应的 `PROCESS STOP`，只能推断「上次未走到退出流程」。
     * 具体是被杀还是系统未给回调机会，本标记无法区分（见 [reportPreviousExit]）。
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
            // 措辞刻意保留不确定性：STOP 标记由 [markCleanExit] 写入，而它依赖生命周期回调，
            // 在「进程被系统直接回收」时同样不会执行——所以「无 STOP」既可能是被杀，
            // 也可能是系统没给回调机会。此前写成「判定为进程被杀」是把猜测当结论（实测 19 次启动
            // 报 19 次「被杀」、STOP 记录 0 条，等于恒真信号、零区分度），会误导排查方向。
            appendLine(
                dir,
                "${timestampFormat.format(Instant.now())}  -      -    -    -  LIFECYCLE  " +
                    "上次未记录到正常退出标记（距今 ${gapSec}s）——进程可能被系统回收，或退出前未进入后台\n"
            )
        }.onFailure { Log.e(TAG, "检查上次退出状态失败", it) }
    }

    /**
     * 回读上个进程写下的轨迹，恢复各会话的回合号高水位。
     *
     * 扫描区间与 [reportStaleTurns] 完全相同（自最后一个 `PROCESS START` 起），差别只在于
     * 这里要的是**最大**回合号而非未收尾的回合。不合并成一个函数：一个要求「有未收尾的回合」，
     * 一个要求「读到了高水位」——硬合并会让「有数据但无未收尾回合」这种常见情形走进不该走的分支。
     *
     * 取不到（无历史文件 / 无 START）时高水位保持为空，等同于当前行为（从 t1 起），不报错：
     * 首次安装本就没有「上轮」，不算异常。
     */
    private fun loadTurnFloor(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("trace-") }
            ?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return
        // 从最新文件往前逐份回读，全部扫完——**不因遇到 `PROCESS START` 而停**。
        // 与 [reportStaleTurns] 的差异就在这：那个只看「上个进程留下的残局」，故必须停在最后
        // 一个 START；而这里要的是「历史上哪些回合号已被占用」，更早的进程用过的号同样不能重用，
        // 只看上个进程反而会漏掉上上个进程用过的号而撞车。高水位偏大只是跳几个号，偏小则直接
        // 让「s+tN+#seq」重新重号，两者代价不对称。
        // 文件数量本身有上限（[cleanupOldLogs] 只留 [MAX_AGE_DAYS] 天），不会无限增长。
        for (file in files) {
            val part = runCatching { file.readLines() }.getOrNull() ?: continue
            for (line in part) {
                val m = TURN_BUCKET_RE.find(line) ?: continue
                val n = m.groupValues[2].toLongOrNull() ?: continue
                turnFloor.merge(m.groupValues[1], n) { a, b -> maxOf(a, b) }
            }
        }
    }

    /** 确保回合号高水位已回读；未 [init] 或已读过时直接返回。 */
    private fun ensureTurnFloor() {
        if (turnFloorLoaded.get()) return
        val dir = logDir ?: return
        // 双检锁：并发首回合可能同时到达，重复回读只是白做功，不产生错误数据。
        synchronized(turnFloorLoaded) {
            if (turnFloorLoaded.get()) return
            runCatching { loadTurnFloor(dir) }
                .onFailure { Log.e(TAG, "恢复回合号高水位失败", it) }
            turnFloorLoaded.set(true)
        }
    }

    /**
     * 启动时清点「上一个进程留下的未收尾回合」。
     *
     * 为什么不能靠内存里的 [activeTurns]：进程重启后它必然是空的，等真正要查时早已无迹可寻。
     * 未收尾回合只存在于**磁盘上上一个进程写下的那段轨迹**里，故必须回读文件推断。
     *
     * 为什么必须补：`beginTurn` 的 stale 检测只在「同会话再开新回合」时才触发，会话终止后
     * 不再开回合的永远不会被察觉（实测 266 开始 / 237 结束 = 29 个未收尾，而显式记录仅 4 条，
     * 覆盖率 14%）——这正是本模块最忌讳的「无解释的空白」。
     *
     * **跨天处理（易错点）**：文件名按天生成，而进程会跨天——23:00 启动的进程，其 START 写在
     * 前一天的文件里，零点之后的记录却写进次日的文件。若只读最新一个文件，就会因为「找不到
     * START」而直接早退，**漏掉跨天残局**。故从最新文件往前逐份回读，直到找到最后一个
     * PROCESS START 为止，再从那里扫到末尾。只丢弃该 START 之前的内容（属于更早的进程）。
     */
    private fun reportStaleTurns(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("trace-") }
                ?.sortedBy { it.name } ?: return@runCatching
            if (files.isEmpty()) return@runCatching

            // 从最新文件往前回读，凑出「上一个进程 START 之后」的全部行（跨文件拼接）。
            // 找到 START 就停——它前面同文件的内容属于更早的进程，不入本次扫描。
            val lines = ArrayList<String>()
            var found = false
            for (file in files.asReversed()) {
                val part = runCatching { file.readLines() }.getOrNull() ?: continue
                val idx = part.indexOfLast { it.contains("LIFECYCLE") && it.contains("PROCESS START") }
                if (idx >= 0) {
                    lines.addAll(0, part.subList(idx, part.size))
                    found = true
                    break
                }
                lines.addAll(0, part)
            }
            if (!found) return@runCatching

            // 只跟踪「回合开始」而无「回合结束」的 (会话 → 回合号)。
            val openTurns = LinkedHashMap<String, String>()
            // 与 [loadTurnFloor] 共用同一个回合号正则：两处对「什么是回合记录」的判定必须一致，
            // 否则会出现「清点时认得、恢复高水位时不认得」这类看似矛盾的行为。
            val turnRe = TURN_BUCKET_RE
            // 从 1 起跳：第 0 行是 START 本身。
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (!line.contains("  TURN  ")) continue
                val m = turnRe.find(line) ?: continue
                val scope = m.groupValues[1]
                val turn = m.groupValues[2]
                when {
                    line.contains("轮次开始") -> openTurns[scope] = turn
                    line.contains("轮次结束") -> if (openTurns[scope] == turn) openTurns.remove(scope)
                }
            }
            if (openTurns.isEmpty()) return@runCatching
            appendLine(
                dir,
                "${timestampFormat.format(Instant.now())}  -      -    -    -  LIFECYCLE  " +
                        "上轮有 ${openTurns.size} 个回合未收尾（${openTurns.entries.joinToString(" ") { "${it.key}/${it.value}" }}）" +
                        "——进程在回合中途消失，这些回合的收尾事实已不可考\n"
            )
        }.onFailure { Log.e(TAG, "清点上轮未收尾回合失败", it) }
    }

    /**
     * 反查某作用域当前正在进行的回合号；无活跃回合时返回 null。
     *
     * 供拿不到 turnId 的层（如 AILogger 落请求头）把记录挂到正在进行的时间线上。
     */
    fun currentTurnOf(scope: String?): String? = scope?.let { activeTurns[it] }

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
     * 只删轨迹，不动 [MARKER_FILE]：它是「上次是否走过退出流程」的依据之一，删了会让下次启动少一条判定线索。
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
    /**
     * 按回合索引的内部状态键。
     *
     * `turnId` 只在**会话内**唯一（`t1`/`t2`…，见 [beginTurn]），故所有以回合为索引的
     * map 都必须用 `scope/turnId` 组合键；否则并发会话会共用同一条目：既共享 seq
     * （时序链错乱），又会在某一方 [endTurn] 时连带清掉另一方的活跃映射，
     * 使其后续事件被误判为「回合已收尾」而丢弃（真机复现：同秒开启的 `t1` 相互干扰）。
     */
    private fun keyOf(scope: String?, turnId: String): String = "${scope ?: "-"}/$turnId"

    fun beginTurn(key: String): String {
        // 先确保回合号高水位已从上个进程恢复：本方法可能早于 init 的异步读取被调用，
        // 那时若直接从 0 起算，发出的就是与上个进程重号的 turnId。
        ensureTurnFloor()
        // 开启新回合前，先看同作用域上一回合是否留有未收尾状态。
        // 这是进程消失留下的自动痕迹之一：进程突然消失时，finally 与异常处理都不执行，
        // 上一回合永远等不到 endTurn。检测到就写一行显式说明，而不是留下一段无解释的空白。
        activeTurns[key]?.let { stale ->
            val staleKey = keyOf(key, stale)
            if (seqCounters.containsKey(staleKey)) {
                val total = recordCounters[staleKey] ?: 0L
                record(
                    stale, key, "TURN",
                    "上一回合 $stale 未见收尾（已记 $total 条）——进程可能被系统杀掉，非正常结束"
                )
            }
        }
        val n = turnFloor[key.take(8)]?.let { floor ->
            turnCounters.computeIfAbsent(key) { AtomicLong(floor) }
        } ?: turnCounters.computeIfAbsent(key) { AtomicLong(0) }
        val turnId = "t${n.incrementAndGet()}"
        val mapKey = keyOf(key, turnId)
        seqCounters[mapKey] = AtomicLong(0)
        recordCounters[mapKey] = 0L
        droppedCounters.remove(mapKey)
        activeTurns[key] = turnId
        // 新回合开始 = 上一轮的回合外观察翻页：把 t0 的计数归零。
        // 否则 t0 的记录数会跨回合单调累积，撞上 MAX_RECORDS_PER_TURN 后被静默丢弃
        // （而它永不等不到 endTurn，没有任何其它清理时机）。
        // seq 随之从 1 重新开始——与真实回合各自从 1 计数的口径一致。
        val outOfTurnKey = keyOf(key, OUT_OF_TURN)
        if (seqCounters.containsKey(outOfTurnKey)) {
            seqCounters[outOfTurnKey] = AtomicLong(0)
            recordCounters[outOfTurnKey] = 0L
            droppedCounters.remove(outOfTurnKey)
        }
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
     * @param causeKey 业务键，用于指回**真正的**起因记录（经 [bindCause] 绑定）。
     *   并发交错时「上一条」是并发的邻居而非原因，必须用它显式指回；解析不到时退回默认行为。
     * @return 本条分配到的 `seq`；未记录（未启用 / 回合未知 / 超上限）时返回 null。
     */
    fun record(
        turnId: String?,
        scope: String?,
        layer: String,
        detail: String,
        causeSeq: Long? = null,
        causeKey: String? = null,
    ): Long? {
        if (!enabled) return null
        if (turnId == null) {
            // 原先直接 return，让轨迹出现无法解释的空白（而本模块的设计初衷恰恰是
            // 「不要留下无解释的空白」）。改为限频落一行，不再静默。
            noteOrphan(scope, layer, detail, why = "未携带回合上下文")
            return null
        }
        // 先判上限、再取 seq：seq 只为「已接受」的记录分配，保证连续无空洞，
        // 于是「上一条 = seq-1」恒成立（否则默认因果会指向被丢弃的序号）。
        val mapKey = keyOf(scope, turnId)
        val count = recordCounters[mapKey] ?: 0L
        if (count >= MAX_RECORDS_PER_TURN) {
            droppedCounters[mapKey] = (droppedCounters[mapKey] ?: 0L) + 1
            return null
        }
        val seqCounter = seqCounters[mapKey]
        if (seqCounter == null) {
            // 回合已 endTurn（或从未 beginTurn）却仍在记录：同样不能静默。
            noteOrphan(scope, layer, detail, why = "回合 $turnId 已收尾")
            return null
        }
        val seq = seqCounter.incrementAndGet()
        recordCounters[mapKey] = count + 1

        val bound = causeKey?.let { boundCauses[mapKey]?.get(it) }
        write(turnId, scope, seq, layer, detail, causeSeq ?: bound ?: (seq - 1).takeIf { it >= 1 })
        return seq
    }

    /** 测试入口：读绑定表，验证的是 [record] 实际使用的那份状态。 */
    internal fun resolveCauseForTest(turnId: String, scope: String?, key: String): Long? =
        boundCauses[keyOf(scope, turnId)]?.get(key)

    /**
     * 测试入口：从指定目录重读高水位。
     *
     * 走的是与生产**完全相同**的 [loadTurnFloor]（含「遇 PROCESS START 即停」与按会话取最大），
     * 而不是另写一份等价逻辑——否则测的是副本，真路径仍然没被覆盖。
     */
    internal fun reloadTurnFloorForTest(dir: File) {
        turnFloor.clear()
        turnFloorLoaded.set(false)
        loadTurnFloor(dir)
        turnFloorLoaded.set(true)
    }

    /** 测试入口：当前高水位快照。 */
    internal fun turnFloorForTest(): Map<String, Long> = turnFloor.toMap()

    /**
     * 测试入口：注入回合号高水位。
     *
     * 真实路径是 [loadTurnFloor] 从轨迹文件回读；测试里造文件会引入文件系统依赖，
     * 而这里要验证的是「拿到高水位之后编号怎么走」，两者正交，故直接注入状态。
     */
    internal fun setTurnFloorForTest(session: String, floor: Long) {
        turnFloor[session] = floor
        turnFloorLoaded.set(true)
    }

    /**
     * 把一条**业务键**绑到刚记录的 `seq` 上，供后续记录经 [record] 的 `causeKey` 指回它。
     *
     * 用途：表达「真正的因果」而非「时间上的相邻」。工具调用是最典型的一例——
     * `tool_finished` 的原因是自己那次 `tool_started`，而非同回合的上一条记录；
     * 并发调用（实测单日交错 277 次）时两者不是同一条，默认因果会把链指错人。
     *
     * @param seq 被绑记录的序号；传 null（未记录）时不建立绑定，后续自然退回默认因果。
     */
    fun bindCause(turnId: String?, scope: String?, key: String, seq: Long?) {
        if (turnId == null || seq == null) return
        // 键里带上 turnId：回合结束后绑定被清，新回合不会误取到上一回合的 seq。
        boundCauses.computeIfAbsent(keyOf(scope, turnId)) { ConcurrentHashMap() }[key] = seq
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
    /**
     * 记录一条不带回合上下文的记录（由 [activeTurns] 反查回合号）。
     *
     * @return 分配到的 `seq`；未记录（未启用 / scope 为空 / 动作类层缺活跃回合）时返回 null。
     *   返回值的意义在于**可观测**——调用方与测试都能判断这条到底进没进轨迹。
     */
    fun recordFor(scope: String?, layer: String, detail: String): Long? {
        if (!enabled || scope == null) return null
        val turnId = activeTurns[scope]
        if (turnId == null) {
            // UI 与 SNAPSHOT 是**观察**类记录（「此刻界面/残留是什么样」），不是回合内的**动作**。
            // 它们本来就该能在回合外记录：典型如「尾巴气泡为何没隐藏」——那恰恰发生在回合**结束之后**，
            // 而 endTurn 已摘掉活跃回合，于是这条打点**必然**被丢弃（真机实测 TRACE_DROPPED 全是被丢的
            // `[UI] 尾巴`，不是偶发时序问题）。用固定虚拟回合号承接，既不丢记录，也不污染真实回合的因果链。
            // TOOL/EVENT 不在此列：它们本该挂在回合上，缺失就是异常，仍走告警路径。
            if (isObservationLayer(layer)) {
                // record() 要求该回合的 seq 计数器存在（否则按「回合已收尾」丢弃）。
                // t0 不是真实回合，永远不会被 endTurn 建立/清理，故在此按需初始化；
                // 它的 seq 空间与会话的真实回合相互独立，不干扰因果链。
                seqCounters.computeIfAbsent(keyOf(scope, OUT_OF_TURN)) { AtomicLong(0) }
                return record(OUT_OF_TURN, scope, layer, detail)
            }
            noteOrphan(scope, layer, detail, why = "作用域 $scope 无活跃回合")
            return null
        }
        // scope 一并带上：反查得到的回合号在日志里不够用，仍需标明是哪个会话
        return record(turnId, scope, layer, detail)
    }

    /** 观察类层：描述「此刻是什么状态」，不描述「发生了什么动作」，可在回合外记录。 */
    private fun isObservationLayer(layer: String): Boolean =
        layer == "UI" || layer.startsWith("SNAPSHOT")

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

    /** 各作用域上一次记录过的 UI 状态行，用于抑制重复。 */
    private val lastUiState = ConcurrentHashMap<String, String>()

    /**
     * 记录一次 UI 状态跳变；**同一作用域内与上次内容相同则丢弃**。
     *
     * 为什么不直接用 [recordFor]：调用点是 Compose 的 `LaunchedEffect`，而它所在的尾巴 item
     * 位于 LazyColumn 内——`creates` 滑出视口即被 dispose、滑回又重建，effect 因此重新执行，
     * 即使状态一个字节没变也会再写一行。实测单日 2039 条 UI 记录里，`busy=true reasoning=false
     * streaming=false settled=false` 这一种组合独占 37%，全是同一状态被反复落盘。
     *
     * 在唯一出口按「同会话 + 同内容」去重，比在调用点调 effect 的 key 更稳：后者挡不住
     * 「重组导致 effect 重启」这条路径（key 根本没变，effect 依旧会重跑）。
     */
    fun recordUiState(scope: String?, detail: String) {
        if (!enabled || scope == null) return
        // put 返回旧值；与本次相同则不落盘。先写后比是安全的：值没变，覆盖无副作用。
        if (lastUiState.put(scope, detail) == detail) return
        recordFor(scope, "UI", detail)
    }

    /** 回合结束：报告总量与被丢弃量，便于判断是否需要提高上限。 */
    fun endTurn(turnId: String?, scope: String?, outcome: String) {
        if (turnId == null) return
        val mapKey = keyOf(scope, turnId)
        val total = recordCounters[mapKey] ?: 0L
        val dropped = droppedCounters[mapKey] ?: 0L
        val suffix = if (dropped > 0) "（另有 $dropped 条超上限被丢弃）" else ""
        record(turnId, scope, "TURN", "轮次结束/$outcome 共 $total 条$suffix")
        seqCounters.remove(mapKey)
        recordCounters.remove(mapKey)
        droppedCounters.remove(mapKey)
        boundCauses.remove(mapKey)
        // 只摘掉本会话自己的活跃映射：原 `removeIf { it.value == turnId }` 会误删
        // **其它会话**的映射（它们的 turnId 同样是 t1/t2…），令其后续事件被丢弃。
        if (scope != null && activeTurns[scope] == turnId) activeTurns.remove(scope)
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
        // 先脱敏，再把换行折叠掉：本格式是「一条记录一行」，detail 若含换行会把一条记录撑成多行，
        // 既破坏 grep（后续行没有时间戳/会话前缀，看起来像格式损坏），也破坏下游按行解析。
        // 真机实测过：一条 HTTP 504 响应体被写进 detail 后裂成 5 行。
        // 折叠而非截断：保留「此处原本有换行」的事实，且是纯 ASCII，不影响 grep 与人工阅读。
        val flatDetail = foldNewlines(MediaRedactor.redact(detail))
        val line = "${timestampFormat.format(now)}  s=$session $turnId #$seq$cause  $layer  $flatDetail\n"
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
     * 把 detail 里的换行折叠成字面量 `\n`，保证「一条记录占一行」的格式契约。
     *
     * 用字面量而非直接删掉换行：读轨迹时要能看出「原文这里断过行」——直接拼接会把
     * 两段不相干的内容粘成一句，比多两个字符更容易误读。
     * 先把 `\r\n` 与孤立 `\r` 归一，避免 Windows 换行变成 `\n` + 残留空行。
     */
    internal fun foldNewlines(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\\n")

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
