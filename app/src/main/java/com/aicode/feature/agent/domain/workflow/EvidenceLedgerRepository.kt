package com.aicode.feature.agent.domain.workflow

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.util.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨回合声明账本：记录本会话历次收尾裁决与验证命令的执行结果。
 *
 * ## 为什么需要
 *
 * 守卫的 `evidenceRecords` 是 **run 级**，每次 `executeEvents` 新建、收尾后即丢。于是：
 *  - 模型这轮说「已修复」被拦，下轮改说「已解决」再报一次，守卫不认识；
 *  - 长会话**上下文压缩**后，模型看不到早期被拉回的消息，会话流自证失效——账本是压缩后
 *    唯一的历史提醒（这也是不依赖会话消息历史的正当性论据）。
 *
 * ## 存储位置（关键约束，勿改）
 *
 * 落 `filesDir/evidence-ledger/`，与 `filesDir/aicode/` **平级**。
 * `aicode/` 被 proot 绑定为容器内 `/root/.aicode`（**读写**，见 ContainerInstaller.aicodeDir 与
 * LinuxContainerEngine 的 `-b` 参数），模型在容器里能读能写；账本若放那里，模型 `cat` 一眼
 * 看到自己的 FAIL 历史、`echo '{}' >` 一秒洗白。app 私有目录不在容器绑定清单内，
 * 这是防作弊账本的天然屏障。
 *
 * ## 依赖约束（防 DI 环）
 *
 * 本类**只依赖 Context**，不得注入 `FileAccessProvider` / `WorkspaceRepository`。
 * 后者会与 `SessionUseCase` 构成注入环（v1.18.5 实炸过，当时用 `dagger.Lazy` 破环）。
 *
 * ## 失败处置
 *
 * 读写一律「尽力而为」：失败只记日志并返回空账本，守卫降级为无账本模式继续跑，
 * 不崩收尾、不重试循环（照 DurableTaskRepository 的 guarded 模式）。
 *
 * ## 并发取舍
 *
 * 读-改-写未加互斥（同会话并发收尾、或收尾与删除并发时为 last-writer-wins）：
 * 丢一次合并的代价是防护回退一级，不会崩也不会误判；删后写回的孤儿文件由 7 天 prune 兜底。
 * 想收紧就在 `record` 外接一个单例 `Mutex`，但当前收益不抵复杂度。
 */
@Singleton
class EvidenceLedgerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    internal companion object {
        const val TAG = "EvidenceLedger"
        const val DIR_NAME = "evidence-ledger"

        /** 单条命令正文的存储上限（只需够识别类别，不必存全量）。 */
        const val CMD_MAX_CHARS = 200

        /** 环形保留的命令条数。 */
        const val MAX_COMMANDS = 20

        /** 环形保留的写文件记录条数。 */
        const val MAX_WRITES = 20

        /** 账本保留时长：超过则在读取时顺手清理（不做定时器、不扫全目录）。 */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** 进程内只扫一次目录（清过期账本）。挂 load 里会让每次收尾读都多一次目录扫，
         * 与本项目的省电取向相背；进程级一次扫是毫秒级，之后整进程不再扫。 */
        @Volatile
        private var prunedThisProcess = false

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private fun dir(): File = File(context.filesDir, DIR_NAME)

    private fun fileFor(sessionId: String): File =
        File(dir(), "${sessionId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")

    /** 读取账本；不存在或损坏时返回空账本。 */
    suspend fun load(sessionId: String): EvidenceLedger = withContext(Dispatchers.IO) {
        if (!prunedThisProcess) {
            prunedThisProcess = true
            pruneStaleNow()
        }
        val file = fileFor(sessionId)
        if (!file.isFile) return@withContext EvidenceLedger()
        runCatching { json.decodeFromString<EvidenceLedger>(file.readText()) }
            .getOrElse {
                FileLogger.w(TAG, "读取账本失败，按空账本处理: ${it.message}")
                EvidenceLedger()
            }
    }

    /**
     * 记录一次收尾裁决。
     *
     * @param failed 本次裁决是否有未通过项
     * @param claim 本次收尾声明原文（仅失败时留存，供下次提示原样展示）
     * @param verifyCommands 本次 run 内**验证类**命令的执行记录（调用方已筛好）
     * @param writes 本次 run 内**成功的写文件**记录（供下轮跨回合汇报核对凭证）
     */
    suspend fun record(
        sessionId: String,
        failed: Boolean,
        claim: String,
        verifyCommands: List<LedgerCommand>,
        writes: List<LedgerWrite> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val old = load(sessionId)
        val updated = EvidenceLedger.afterRecord(
            old = old,
            failed = failed,
            claim = claim,
            commands = verifyCommands,
            writes = writes,
            now = System.currentTimeMillis(),
            maxCommands = MAX_COMMANDS,
            maxWrites = MAX_WRITES,
        )
        write(sessionId, updated)
    }

    /**
     * 判定「先红后绿」：同一类别的验证命令，最近一次成功、且它的**前一条同类别**记录是失败。
     *
     * 用类别而非完整命令串比对——模型重跑时会改参数（换个 `--tests` 类名），串比对召回率趋零。
     * 「相邻」这个定义天然内置时间窗：隔很久的旧红与新绿中间必然插过其他同类别命令，无需时钟。
     */
    fun hasRedThenGreen(ledger: EvidenceLedger): Boolean = EvidenceLedger.hasRedThenGreenIn(ledger.verifyCommands)

    /** 删除某会话的账本（会话删除时调用）。 */
    suspend fun clearSession(sessionId: String) = withContext(Dispatchers.IO) {
        runCatchingCancellable { fileFor(sessionId).delete() }
            .onFailure { FileLogger.w(TAG, "清理会话 $sessionId 的账本失败: ${it.message}") }
    }

    /** 清理超过保留期的账本。首次 [load] 时在进程内跑一次（见 [prunedThisProcess]）。 */
    private fun pruneStaleNow() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        runCatchingCancellable {
            dir().listFiles { f -> f.isFile && f.lastModified() < cutoff }?.forEach { it.delete() }
        }.onFailure { FileLogger.w(TAG, "清理过期账本失败: ${it.message}") }
    }

    private fun write(sessionId: String, ledger: EvidenceLedger) {
        val file = fileFor(sessionId)
        val tmp = File(dir(), "${file.name}.tmp")
        runCatchingCancellable {
            dir().mkdirs()
            tmp.writeText(json.encodeToString(ledger))
            // 临时文件 + rename 原子落盘，避免写一半崩溃留下截断账本（照 SkillConfigRepository 同一模式）。
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(json.encodeToString(ledger))
            }
        }.onFailure { FileLogger.w(TAG, "写账本失败（本次核对降级为无账本模式）: ${it.message}") }
    }
}
