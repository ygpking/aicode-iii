package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理事件类型。前四种为生命周期事件；后两种为收发消息事件，只投递内容，不影响活跃集合。
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /** 主会话向子代理发来一条消息（发送方是父会话）。 */
    MESSAGE_FROM_PARENT,

    /** 子代理向主会话发来一条消息（发送方是子会话）。 */
    MESSAGE_FROM_SUB,

    /** 子代理向兄弟子代理发来一条消息（发送方是同一父会话下的另一子会话）。 */
    MESSAGE_FROM_SIBLING
}

/**
 * 子代理生命周期事件。
 *
 * @property subSessionId 子代理会话 id（同时也是消息的收件人）。
 * @property parentSessionId 父会话 id（子会话记录里 parentId）。
 * @property type 事件类型。
 * @property detail 附加说明：SPAWNED 为任务指令；COMPLETED/FAILED 为子代理最终输出/错误信息；
 *   MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB / MESSAGE_FROM_SIBLING 为消息正文。
 * @property senderSessionId 消息发送方会话 id，仅 [SubAgentEventType.MESSAGE_FROM_SIBLING] 使用：
 *   兄弟消息的发送者既不是父会话也不是收件人本人，需显式带上才能标出「谁发来的」。
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = "",
    val senderSessionId: String? = null
)

/**
 * 子代理事件总线：TaskTool 发出 SPAWNED（子代理已创建），ViewModel 收集后自动
 * 在子会话上启动 AI 工作流；子会话工作流结束时 ViewModel 再发 COMPLETED/FAILED，
 * 父会话据此注入后台通知（类比 terminal 的 notify=true 异步回调）。
 *
 * 同时维护活跃子代理会话 id 集合，供 TaskTool 查询并发上限（最多 5 个运行中）。
 */
@Singleton
class SubAgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /** 当前活跃（运行中）的子代理会话 id 集合。 */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /** 运行中的子代理数量。 */
    val activeCount: Int get() = _activeSubSessionIds.value.size

    /** 是否已达并发上限。 */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    // —— 会话流归属（多实例去重）——
    // MainActivity 为 standard 模式，可并存多个 Activity 实例；每个实例有独立的
    // AIAgentViewModel，各自订阅同一事件总线，同一事件会被每份订阅各处理一次。
    // 为避免「同一事件被多份状态各自处理」导致同一会话双流并发（子代理重复启动、
    // 通知重复触发），这里维护「会话 → 持有其活跃流的 VM 实例」映射：处理事件前先看归属，
    // 持有者搭车入队、别处持有则跳过、无主时抢占成功者才触发新流。
    // owner 用 VM 实例引用（引用相等判定），release 只释放自己持有的归属。
    // 存弱引用：VM 整体不可达（含其协程链一起被回收）时，即使 releaseFlow 未走到，
    // 归属也随 GC 失效、新实例可抢占；若存强引用则已销毁 VM 永不被回收，归属永久残留
    // （实测 2026-10-12 07:28 stream reject 卡死、用户重启才恢复）。
    // 注意：仍有活跃协程的 VM 不会被 GC，那类僵尸归属由 ViewModel.onCleared 的
    // releaseAllFlows 兜底——弱引用与生命周期释放互补。
    // 已知两者共同覆盖不到的盲区（不假装覆盖）：进程被杀时 onCleared 不执行，
    // 且若 VM 仍被外部（如通知/Activity）强引用而流又卡死，则弱引用也不失效；
    // 此时归属仍会残留，需靠新实例的 reject 兜底轮询在同会话重新抢占（见 AIAgentViewModel）。
    private val flowOwners = ConcurrentHashMap<String, WeakReference<Any>>()

    /**
     * 认领会话流归属；此前无主（或持有者已被 GC）则成功并返回 true，
     * 已被（本实例或其他实例）持有则返回 false。
     * 并发安全：无主时用 putIfAbsent/replace 原子竞争，多个实例同时抢同一会话时只有一个成功。
     */
    fun tryAcquireFlow(sessionId: String, owner: Any): Boolean {
        while (true) {
            val current = flowOwners[sessionId]
            val live = current?.get()
            if (live != null && live !== owner) return false // 别处持有，让位
            if (live === owner) return false // 本人已持有，与现语义一致：不重复 acquire
            // 无主或持有者已 GC：尝试原子放入/替换，失败（被并发抢先）则重试
            if (current == null) {
                if (flowOwners.putIfAbsent(sessionId, WeakReference(owner)) == null) return true
            } else if (flowOwners.replace(sessionId, current, WeakReference(owner))) {
                return true
            }
        }
    }

    /**
     * 释放本实例持有的会话流归属（幂等）。
     *
     * 两种清理：
     * 1. 本人持有 → 置 null 删除条目；
     * 2. **换代空弱引用**（持有者已被 GC，条目仍占着 key）→ 顺带清掉。
     *    只做 1 不做 2 的话，每个被 GC 过的 owner 都会在 map 里留下永不清理的 key（泄漏）。
     */
    fun releaseFlow(sessionId: String, owner: Any) {
        flowOwners.computeIfPresent(sessionId) { _, current ->
            val live = current.get()
            if (live === owner || live == null) null else current
        }
    }

    /** 释放 [owner] 持有的全部会话流归属（幂等；供 ViewModel.onCleared 兜底调用）。 */
    fun releaseAllFlows(owner: Any) {
        flowOwners.entries.removeIf { it.value.get() === owner }
    }

    /** 该会话流是否由 [owner] 本人持有。 */
    fun isFlowOwnedBy(sessionId: String, owner: Any): Boolean {
        val current = flowOwners[sessionId] ?: return false
        return current.get() === owner
    }

    /** 该会话流是否已被其他实例持有（本实例不持有但别处在跑）。 */
    fun isFlowOwnedElsewhere(sessionId: String, owner: Any): Boolean {
        val current = flowOwners[sessionId] ?: return false
        val live = current.get() ?: return false // 持有者已 GC = 无主
        return live !== owner
    }

    /**
     * 直接把某个子代理移出活跃集合，不广播事件；返回它此前是否处于活跃状态。
     *
     * 用户在界面上手动停止或删除运行中的子会话时走这条路径：不能改用 [emit]，
     * 因为 ViewModel 收到 STOPPED 事件后又会回调 stopAgentSession，形成无限循环。
     * 返回值同时用于区分「用户手动终止」与「TaskTool 已处理过」（后者返回 false），
     * 避免向父代理重复投递通知。
     */
    fun release(subSessionId: String): Boolean {
        var removed = false
        // 用 update 原子读-改-写：emit（子代理协程）与 release（用户停止）可能并发，
        // `value = value - id` 会互相覆盖，造成名额泄漏/虚高。
        _activeSubSessionIds.update { current ->
            if (subSessionId in current) { removed = true; current - subSessionId } else current
        }
        return removed
    }

    /**
     * 直接把某个子代理登记为活跃，不广播事件。
     *
     * 专供「已完成的子代理被 `send` 唤醒、起新一轮」的路径：该轮不经 [SubAgentEventType.SPAWNED]
     * （只在 task create 时发），若不在此补登记，活跃集合会自始至终缺这个 id，导致
     * [isFull] 漏算（可跑超过 MAX_RUNNING 个）、read/list 把运行中的它报成 completed、
     * [SubAgentWriteLease] 的 pruneInactive 误删其写租约（失去写冲突保护）。
     *
     * 幂等：已在集合中则无操作；调用方应在对应运行结束时 [release] 交回槽位。
     */
    fun markActive(subSessionId: String) {
        _activeSubSessionIds.update { if (subSessionId in it) it else it + subSessionId }
    }

    fun emit(event: SubAgentEvent) {
        // 同步维护活跃集合
        when (event.type) {
            SubAgentEventType.SPAWNED -> {
                _activeSubSessionIds.update { it + event.subSessionId }
            }
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.STOPPED -> {
                _activeSubSessionIds.update { it - event.subSessionId }
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB,
            SubAgentEventType.MESSAGE_FROM_SIBLING -> {
                // 收发消息不改变运行状态，活跃集合保持不变。
            }
        }
        val delivered = _events.tryEmit(event)
        // SPAWNED 投递失败（缓冲满）时事件会丢，子代理永远不会被启动，但活跃集合已 +1——
        // 名额被永久占用（isFull 误判、activeCount 虚高）。回滚该次占位。
        //
        // 为什么只看 tryEmit 的返回值、不额外判「无订阅者」：订阅是 AIAgentViewModel.init 里的
        // 常驻 collect，只在 VM 未创建/已销毁时为 0，而 SPAWNED 只会在一次运行中的工具调用里发出
        // （VM 必已订阅）；该窗口实际不可达，多判反而让「emit 即计入活跃」的契约不再成立。
        if (!delivered && event.type == SubAgentEventType.SPAWNED) {
            _activeSubSessionIds.update { it - event.subSessionId }
            FileLogger.w(
                "SubAgentEventBus",
                "SPAWNED 事件投递失败（缓冲满），已回滚活跃集合：${event.subSessionId}"
            )
        }
    }

    companion object {
        /** 同时运行的子代理上限。判定以此为准，工具层只引用不自己再写一份。 */
        const val MAX_RUNNING = 5
    }
}