package com.aicode.feature.agent.domain.tool

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话级「文件已读/已写」状态：记录每个会话内 AI 接触过哪些文件、接触时的最后修改时间。
 *
 * 供 `editFile` 做写前校验，挡掉两类典型事故：
 * - AI 凭记忆改写一个从未读过内容的文件；
 * - AI 读过后文件已被外部（用户手动编辑、构建产物、其它工具）改动，此时的 `old_string`
 *   锚定的是旧内容，落盘会覆盖掉别人的改动。
 *
 * 刻意只放内存、不落盘：一次 App 重启就让全部状态失效、需重新读取，代价是重读一次文件，
 * 换来实现上的零 IO 与零数据库迁移。状态按 [sessionId] 隔离，避免多会话互相误判；
 * 会话内文件数超上限时整体清空（同 `SystemPromptProvider` 的会话级缓存策略），
 * 仅防极端情况下的无界累积。
 *
 * [record] 的时间戳为 [UNKNOWN_MTIME]（远程 SFTP 等拿不到可靠时间戳）时，只保留「是否读过」的判定，
 * 跳过新鲜度比较——宁可少报一次「已过期」，也不误拒一次本可成功的编辑。
 */
@Singleton
class FileReadStateStore @Inject constructor() {

    private val states = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /**
     * 记录一次成功的读/写。
     *
     * @param sessionKey 会话标识；空白表示无会话上下文，此时不记录（避免不同会话共用同一空 key）。
     * @param pathKey 已完成归一化的文件路径键（调用方用 `FileAccessProvider.toDisplayPath` 生成）。
     * @param mtime 接触时的最后修改时间（epoch 毫秒）；未知传 [UNKNOWN_MTIME]。
     */
    fun record(sessionKey: String, pathKey: String, mtime: Long) {
        if (sessionKey.isBlank() || pathKey.isBlank()) return
        val map = states.computeIfAbsent(sessionKey) { ConcurrentHashMap() }
        if (map.size >= MAX_FILES_PER_SESSION && !map.containsKey(pathKey)) map.clear()
        map[pathKey] = mtime
    }

    /** 该会话内是否成功读过该文件（不计写入的隐式记录）。 */
    fun wasRead(sessionKey: String, pathKey: String): Boolean =
        states[sessionKey]?.containsKey(pathKey) == true

    /**
     * 记录是否仍然新鲜。未记录过返回 false；记录时间未知（[UNKNOWN_MTIME]）时只要求「记录存在」；
     * 否则要求当前 [currentMtime] 与记录一致。
     */
    fun isFresh(sessionKey: String, pathKey: String, currentMtime: Long): Boolean {
        val recorded = states[sessionKey]?.get(pathKey) ?: return false
        if (recorded == UNKNOWN_MTIME || currentMtime <= 0) return true
        return recorded == currentMtime
    }

    /** 丢弃某文件的记录（删除/移动文件后调用，避免残留过期状态）。 */
    fun forget(sessionKey: String, pathKey: String) {
        states[sessionKey]?.remove(pathKey)
    }

    /** 清空某会话的全部状态（会话删除时调用）。 */
    fun clearSession(sessionKey: String) {
        states.remove(sessionKey)
    }

    companion object {
        /** 表示时间戳不可用；比较时跳过新鲜度判定。 */
        const val UNKNOWN_MTIME: Long = -1L

        /** 单会话记录文件数上限，超过后整体清空；正常编辑流程远小于此。 */
        const val MAX_FILES_PER_SESSION = 256
    }
}
