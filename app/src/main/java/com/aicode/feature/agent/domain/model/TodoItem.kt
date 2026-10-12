package com.aicode.feature.agent.domain.model

enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

data class TodoItem(
    val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: Int = 0,
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
) {
    /**
     * 该待办对输入框上方的面板是否已「过期」。
     *
     * 面板的清理原本只依赖 `clearStaleCompletedTodos`（下一轮用户输入时清**全完成**清单），
     * 而带残留的会话之后往往没有新输入——8 天日志里该清理一次都没触发，
     * 738 轮结束时挂着未清待办，其中 88% 是纯历史遗留（本轮根本没写过 todo），
     * 最长一个面板活了 83.9 小时。
     *
     * 判据只看 updatedAt：真正在推进的任务每轮都会被 todo 工具刷新，
     * 不会命中；只有长期无人问津的清单才会被隐藏。
     * **只隐藏不删除**——数据仍可被工具读到、备份仍完整，用户切走再切回也不会丢。
     */
    fun isStaleForPanel(now: Long = System.currentTimeMillis()): Boolean =
        now - updatedAt > STALE_PANEL_MS

    companion object {
        /** 超过 2 小时未更新的待办不再显示在面板上。 */
        const val STALE_PANEL_MS: Long = 2 * 60 * 60 * 1000L
    }
}
