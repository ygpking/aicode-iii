package com.aicode.feature.agent.domain.workflow

import java.util.concurrent.ConcurrentHashMap

/**
 * 子代理派发追踪器：模型整场自己一个文件一个文件地写、从未派发过子代理时，
 * 注入一次提醒，让「本该并行/委派出去的活」不再被整条忽略。
 *
 * 进程内按会话计数，无清理点——重启后重新计数（无害，顶多多提醒一次）。
 */
object SubAgentDelegationTracker {

    private val wroteSuccessfully = ConcurrentHashMap.newKeySet<String>()
    private val delegated = ConcurrentHashMap.newKeySet<String>()
    private val notified = ConcurrentHashMap.newKeySet<String>()

    /** 本批出现「写文件类工具且成功」时调用（同一批多次调用只计一次）。 */
    fun recordWriteSuccess(sessionId: String) {
        wroteSuccessfully.add(sessionId)
    }

    /** 本批出现子代理派发（task create）时调用。 */
    fun recordDelegation(sessionId: String) {
        delegated.add(sessionId)
    }

    /**
     * 是否应注入提醒。为 true＝「本会话有成功写入 + 从未派发子代理 + 尚未提醒过」，
     * 并在内部标记已提醒（重复调用不再返回 true，保证只提醒一次）。
     */
    fun shouldNotifyAndMark(sessionId: String): Boolean {
        // 先原子占位：同一 run 内多个出口同时到达时，只有一个能走到判定。
        if (!notified.add(sessionId)) return false
        if (wroteSuccessfully.contains(sessionId) && !delegated.contains(sessionId)) return true
        // 判定不成立则回退占位，否则以后条件成立也不再提醒。
        notified.remove(sessionId)
        return false
    }

    /** 测试与清理用：清空某会话的全部状态。 */
    fun reset(sessionId: String) {
        wroteSuccessfully.remove(sessionId)
        delegated.remove(sessionId)
        notified.remove(sessionId)
    }
}
