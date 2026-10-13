package com.aicode.feature.agent.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办面板过期判定（M1-B）。
 *
 * 背景：面板清理原本只靠 `clearStaleCompletedTodos`（下一轮用户输入时清全完成清单），
 * 而带残留的会话之后往往没有新输入——8 天日志里该清理一次都没触发，
 * 738 轮结束时挂着未清待办，其中 88% 是纯历史遗留，最长一个面板活了 83.9 小时。
 */
class TodoItemTest {

    private fun item(updatedAt: Long, status: TodoStatus = TodoStatus.PENDING) =
        TodoItem(
            id = "t1",
            sessionId = "s1",
            subject = "修 bug",
            status = status,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

    @Test
    fun freshItem_isNotStale() {
        val now = 1_000_000L
        assertFalse(item(updatedAt = now - 60_000).isStaleForPanel(now))
        // 刚好 2 小时边界：不算过期（严格大于）
        assertFalse(item(updatedAt = now - TodoItem.STALE_PANEL_MS).isStaleForPanel(now))
    }

    @Test
    fun oldItem_isStale() {
        val now = 1_000_000L
        assertTrue(item(updatedAt = now - TodoItem.STALE_PANEL_MS - 1).isStaleForPanel(now))
        // 实测最长残留 83.9 小时
        assertTrue(item(updatedAt = now - 84 * 60 * 60 * 1000).isStaleForPanel(now))
    }

    @Test
    fun status_doesNotAffectStaleness() {
        val now = 1_000_000L
        val old = now - TodoItem.STALE_PANEL_MS - 1
        // 过期判定只看 updatedAt：在推进的任务每轮都会被刷新，不会命中；
        // 长期无人问津的清单无论什么状态都该从面板隐藏。
        assertTrue(item(old, TodoStatus.IN_PROGRESS).isStaleForPanel(now))
        assertTrue(item(old, TodoStatus.COMPLETED).isStaleForPanel(now))
    }
}
