package com.aicode.feature.agent.presentation.component

import com.aicode.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知提示条的文案映射。
 *
 * 这张表决定用户看到的是不是自己的语言：提示条此前直接渲染 domain 生成的 `<summary>`
 * （那段文本还要喂给 AI，只能固定中文），英文界面下就会露出中文。这里钉住「字段 → 资源」
 * 的映射不再回退到渲染原文，并确保旧版本落库、缺 `<from-kind>` 的通知也有文案可显示。
 */
class NotificationLabelsTest {

    @Test
    fun backgroundTask_byStatus() {
        assertEquals(R.string.chat_notify_task_done, backgroundNotificationLabel("task-notification", "completed", null).textRes)
        assertEquals(R.string.chat_notify_task_failed, backgroundNotificationLabel("task-notification", "failed", null).textRes)
        assertEquals(R.string.chat_notify_task_stopped, backgroundNotificationLabel("task-notification", "stopped", null).textRes)
    }

    @Test
    fun subAgent_byStatus() {
        assertEquals(R.string.chat_notify_subagent_done, backgroundNotificationLabel("subagent-notification", "completed", null).textRes)
        assertEquals(R.string.chat_notify_subagent_failed, backgroundNotificationLabel("subagent-notification", "failed", null).textRes)
        assertEquals(R.string.chat_notify_subagent_stopped, backgroundNotificationLabel("subagent-notification", "stopped", null).textRes)
    }

    @Test
    fun agentMessage_bySenderKind() {
        assertEquals(R.string.chat_notify_message_from_parent, backgroundNotificationLabel("agent-message", "message", "parent").textRes)
        assertEquals(R.string.chat_notify_message_from_sub, backgroundNotificationLabel("agent-message", "message", "sub").textRes)
        assertEquals(R.string.chat_notify_message_from_sibling, backgroundNotificationLabel("agent-message", "message", "sibling").textRes)
    }

    /** 旧版本落库的历史通知没有 from-kind 字段，必须回退，不能留下空标签。 */
    @Test
    fun agentMessage_withoutFromKind_fallsBackToGeneric() {
        assertEquals(R.string.chat_notify_message_generic, backgroundNotificationLabel("agent-message", "message", null).textRes)
        assertEquals(R.string.chat_notify_message_generic, backgroundNotificationLabel("agent-message", "message", "").textRes)
    }

    @Test
    fun modeChange_andUnknownKind() {
        assertEquals(R.string.chat_notify_mode_changed, backgroundNotificationLabel("mode-change", "changed", null).textRes)
        assertEquals(R.string.chat_notify_unknown, backgroundNotificationLabel(null, null, null).textRes)
        assertEquals(R.string.chat_notify_unknown, backgroundNotificationLabel("future-kind", "completed", null).textRes)
    }

    /** 状态缺失（历史消息）按「完成」渲染：宁可少标失败，也不把正常完成误报成失败。 */
    @Test
    fun missingStatus_rendersAsDone() {
        assertEquals(R.string.chat_notify_task_done, backgroundNotificationLabel("task-notification", null, null).textRes)
        assertEquals(R.string.chat_notify_subagent_done, backgroundNotificationLabel("subagent-notification", null, null).textRes)
    }

    @Test
    fun parseBlocks_extractsKindStatusAndFromKind() {
        val content = """
            [系统通知 - 非用户输入]
            <subagent-notification>
              <subagent-id>sub-1</subagent-id>
              <status>failed</status>
              <summary>子代理「调研」已执行失败</summary>
            </subagent-notification>
            <agent-message>
              <from-id>sib</from-id>
              <from-kind>sibling</from-kind>
              <message>帮我看下日志</message>
              <status>message</status>
            </agent-message>
        """.trimIndent()

        val blocks = parseNotificationBlocks(content)

        assertEquals(2, blocks.size)
        assertEquals("subagent-notification", blocks[0].kind)
        assertEquals("failed", blocks[0].status)
        assertEquals(null, blocks[0].fromKind)
        assertEquals("agent-message", blocks[1].kind)
        assertEquals("message", blocks[1].status)
        assertEquals("sibling", blocks[1].fromKind)
    }

    /** 块内 <message> 正文可能含尖括号，不能把块的闭合位置算错。 */
    @Test
    fun parseBlocks_toleratesNestedAngleBrackets() {
        val content = """
            <agent-message>
              <from-kind>sub</from-kind>
              <message>error: &lt;unresolved&gt; &amp; failed</message>
              <status>message</status>
            </agent-message>
        """.trimIndent()

        val blocks = parseNotificationBlocks(content)

        assertEquals(1, blocks.size)
        assertEquals("sub", blocks[0].fromKind)
        assertEquals("message", blocks[0].status)
    }

    @Test
    fun parseBlocks_noBlock_returnsEmpty() {
        assertEquals(emptyList<NotificationBlock>(), parseNotificationBlocks("这只是一条普通消息"))
    }
}
