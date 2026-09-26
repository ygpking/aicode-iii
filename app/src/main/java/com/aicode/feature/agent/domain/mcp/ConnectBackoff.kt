package com.aicode.feature.agent.domain.mcp

/**
 * 连接失败的指数退避冷却表。
 *
 * 每个 server 独立计失败次数：首次失败后冷却 [DEFAULT_BASE_DELAY_MS]，之后逐次翻倍，
 * 封顶 [DEFAULT_MAX_DELAY_MS]；连接成功即清零，下次失败重新从首次计起。
 *
 * 时钟可注入，便于确定性地测试；生产环境使用系统时钟。
 */
internal class ConnectBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class ServerState(var failureCount: Int = 0, var blockedUntilMs: Long = 0L)

    private val states = HashMap<String, ServerState>()

    /** 记录一次连接失败，返回本次冷却应等待的毫秒数。 */
    @Synchronized
    fun onFailure(serverId: String): Long {
        val state = states.getOrPut(serverId) { ServerState() }
        val cooldown = cooldownFor(state.failureCount)
        state.failureCount += 1
        state.blockedUntilMs = clock() + cooldown
        return cooldown
    }

    /** 连接成功：清除该 server 的退避状态。 */
    @Synchronized
    fun onSuccess(serverId: String) {
        states.remove(serverId)
    }

    /** 距离该 server 可再次连接还需等待的毫秒数；0 表示可立即连接。 */
    @Synchronized
    fun delayUntil(serverId: String, nowMs: Long): Long {
        val blockedUntilMs = states[serverId]?.blockedUntilMs ?: return 0L
        return (blockedUntilMs - nowMs).coerceAtLeast(0L)
    }

    /** 清空所有退避状态（用于工作区/容器切换等环境变化后的强制重试）。 */
    @Synchronized
    fun clear() {
        states.clear()
    }

    /** 清除单个 server 的退避状态（用于用户主动保存/编辑该 server 后的强制重试）。 */
    @Synchronized
    fun clear(serverId: String) {
        states.remove(serverId)
    }

    /** [priorFailures] 为本次失败之前已累计的失败次数，0 表示首次失败。 */
    private fun cooldownFor(priorFailures: Int): Long {
        var delay = baseDelayMs
        var remaining = priorFailures
        while (remaining > 0) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay = if (delay > maxDelayMs / 2) maxDelayMs else delay * 2
            remaining--
        }
        return delay.coerceAtMost(maxDelayMs)
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS = 30_000L
        const val DEFAULT_MAX_DELAY_MS = 300_000L
    }
}
