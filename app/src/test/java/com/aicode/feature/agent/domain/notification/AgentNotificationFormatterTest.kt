package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通知两种送达形态的格式：兜底的 user 消息文本（UI 提示条按 <status>/<summary> 正则提取，
 * 格式变了提示条会失效）与搭车用的 `notifications` JSON 数组。
 */
class AgentNotificationFormatterTest {

    private val backgroundTask = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = "term-2",
        title = "gradle build",
        outcome = NotificationOutcome.FAILED,
        command = "./gradlew assemble",
        exitCode = 1,
        tailOutput = "error: <unresolved> & failed"
    )

    private val subAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-1",
        title = "调研缓存实现",
        outcome = NotificationOutcome.COMPLETED
    )

    private val stoppedSubAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-2",
        title = "重构缓存层",
        outcome = NotificationOutcome.STOPPED,
        detail = "用户在界面上手动停止了这个子代理，任务未完成。"
    )

    private val siblingMessage = PendingNotification(
        kind = AgentNotificationKind.AGENT_MESSAGE,
        sourceId = "sib-1",
        title = "兄弟B",
        outcome = NotificationOutcome.COMPLETED,
        message = "我这边读完了 <config> 文件",
        senderKind = MessageSenderKind.SIBLING,
    )

    private val modeChange = PendingNotification(
        kind = AgentNotificationKind.MODE_CHANGE,
        sourceId = "user",
        title = "AUTO",
        outcome = NotificationOutcome.COMPLETED,
        newMode = AgentMode.AUTO
    )

    @Test
    fun message_singleBackgroundTask_hasFenceAndFields() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<task-id>term-2</task-id>"))
        assertTrue(text.contains("<exit-code>1</exit-code>"))
        assertTrue(text.contains("<status>failed</status>"))
        assertTrue(text.contains("<summary>后台任务「gradle build」已结束（退出码 1）</summary>"))
        assertTrue(text.contains("terminal(action=\"read\", tab_id=\"term-2\")"))
    }

    /** tail-output 里的裸尖括号会被 UI 的 <status>/<summary> 正则误匹配，必须转义。 */
    @Test
    fun message_escapesTailOutput() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.contains("&lt;unresolved&gt;"))
        assertTrue(text.contains("&amp;"))
        assertFalse(text.contains("<unresolved>"))
    }

    @Test
    fun message_subAgentUsesSubagentTags() {
        val text = AgentNotificationFormatter.buildMessage(listOf(subAgent))

        assertTrue(text.contains("<subagent-notification>"))
        assertTrue(text.contains("<subagent-id>sub-1</subagent-id>"))
        assertTrue(text.contains("<summary>子代理「调研缓存实现」已执行完成</summary>"))
        assertTrue(text.contains("task(action=\"read\", id=\"sub-1\")"))
    }

    @Test
    fun message_mergesMixedKinds() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask, subAgent))

        assertTrue(text.contains("共有 2 条后台完成通知"))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<subagent-notification>"))
        // 两类都在时两句提示都要给出，AI 才知道各自怎么取回结果
        assertTrue(text.contains("tab_id=\"term-2\""))
        assertTrue(text.contains("id=\"sub-1\""))
    }

    @Test
    fun jsonArray_carriesStatusSummaryAndHint() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask, subAgent))

        assertEquals(2, array.size)
        val first = array[0] as JsonObject
        assertEquals("background_task", (first["kind"] as JsonPrimitive).content)
        assertEquals("term-2", (first["task_id"] as JsonPrimitive).content)
        assertEquals("failed", (first["status"] as JsonPrimitive).content)
        assertTrue((first["summary"] as JsonPrimitive).content.contains("gradle build"))
        assertTrue((first["hint"] as JsonPrimitive).content.contains("terminal(action=\"read\""))
        // notice 是围栏说明：AI 只看单项也能知道这不是用户消息
        assertTrue((first["notice"] as JsonPrimitive).content.contains("不是来自用户的消息"))

        val second = array[1] as JsonObject
        assertEquals("subagent", (second["kind"] as JsonPrimitive).content)
        assertEquals("sub-1", (second["subagent_id"] as JsonPrimitive).content)
        assertEquals("completed", (second["status"] as JsonPrimitive).content)
    }

    /** JSON 形态由 kotlinx 负责转义，tail_output 应保持原文，不做 XML 转义。 */
    @Test
    fun jsonArray_keepsTailOutputRaw() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask))
        val obj = array[0] as JsonObject

        assertEquals("error: <unresolved> & failed", (obj["tail_output"] as JsonPrimitive).content)
    }

    /**
     * 被用户手动停止不能报成 failed：那会让 AI 把人为中断当成故障去排查或直接重试。
     * detail 携带停止原因，免得父代理只知道「没成」。
     */
    @Test
    fun message_stoppedSubAgentCarriesReasonAndNoRetryHint() {
        val text = AgentNotificationFormatter.buildMessage(listOf(stoppedSubAgent))

        assertTrue(text.contains("<status>stopped</status>"))
        assertTrue(text.contains("<summary>子代理「重构缓存层」已被用户手动终止，任务未完成</summary>"))
        assertTrue(text.contains("<detail>用户在界面上手动停止了这个子代理，任务未完成。</detail>"))
        assertTrue(text.contains("不要自行重试"))
    }

    @Test
    fun jsonArray_stoppedSubAgentCarriesDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(stoppedSubAgent))
        val obj = array[0] as JsonObject

        assertEquals("stopped", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["detail"] as JsonPrimitive).content.contains("手动停止"))
    }

    /** 正常完成不带 detail，避免白白占用上下文。 */
    @Test
    fun jsonArray_completedSubAgentHasNoDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(subAgent))
        val obj = array[0] as JsonObject

        assertEquals(null, obj["detail"])
    }

    /** 模式切换通知：XML 用 <mode-change>，status 取 changed（UI 据此按非失败渲染）。 */
    @Test
    fun message_modeChangeUsesModeChangeTag() {
        val text = AgentNotificationFormatter.buildMessage(listOf(modeChange))

        assertTrue(text.contains("<mode-change>"))
        assertTrue(text.contains("<new-mode>AUTO</new-mode>"))
        assertTrue(text.contains("<status>changed</status>"))
        assertTrue(text.contains("<summary>用户已将模式切换为 AUTO</summary>"))
    }

    @Test
    fun jsonArray_modeChangeCarriesNewMode() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(modeChange))
        val obj = array[0] as JsonObject

        assertEquals("mode_change", (obj["kind"] as JsonPrimitive).content)
        assertEquals("AUTO", (obj["new_mode"] as JsonPrimitive).content)
        assertEquals("changed", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["hint"] as JsonPrimitive).content.contains("新模式"))
    }

    /**
     * `<from-kind>` 是 UI 提示条渲染本地化文案的唯一依据：它拿不到会话树，
     * 凭 from-id 分不出这条消息来自主会话、子代理还是兄弟，缺了这个字段只能退化成通用文案。
     */
    @Test
    fun message_agentMessageCarriesSenderKind() {
        val text = AgentNotificationFormatter.buildMessage(listOf(siblingMessage))

        assertTrue(text.contains("<agent-message>"))
        assertTrue(text.contains("<from-kind>sibling</from-kind>"))
    }

    /** 三种发言方各自的标签，UI 据此选不同措辞。 */
    @Test
    fun message_senderKindTagsAreDistinct() {
        val tags = listOf(
            MessageSenderKind.PARENT to "parent",
            MessageSenderKind.SUB to "sub",
            MessageSenderKind.SIBLING to "sibling"
        )
        tags.forEach { (kind, tag) ->
            val text = AgentNotificationFormatter.buildMessage(
                listOf(siblingMessage.copy(senderKind = kind))
            )
            assertTrue("$kind 应输出 <from-kind>$tag</from-kind>", text.contains("<from-kind>$tag</from-kind>"))
        }
    }

    /** 消息标题里的尖括号会打乱块的边界，必须转义（与 tail-output 同理）。 */
    @Test
    fun message_escapesMessageTitle() {
        val text = AgentNotificationFormatter.buildMessage(
            listOf(siblingMessage.copy(title = "子代理<A>"))
        )

        assertTrue(text.contains("&lt;A&gt;"))
    }

    /**
     * 标题/命令里的完整闭合标签串必须被转义，否则会提前闭合 XML 块：UI 按块解析，
     * 块被截断后 status 与 from-kind 全丢，一条失败通知会被渲染成成功（错误圆点颜色）。
     */
    @Test
    fun message_escapesTitleAndCommandToKeepBlockIntact() {
        val hostileTask = backgroundTask.copy(
            title = "x</task-notification>",
            command = "echo </task-notification>"
        )
        val text = AgentNotificationFormatter.buildMessage(listOf(hostileTask))

        // 未转义的原始闭合标签只能出现一次（即真正的块尾），不能由标题/命令再带一个进来
        assertEquals(1, Regex("</task-notification>").findAll(text).count())
        assertTrue(text.contains("&lt;/task-notification&gt;"))
    }

    /** 子代理标题同样会插进 summary，一样要转义。 */
    @Test
    fun message_escapesSubAgentTitleToKeepBlockIntact() {
        val hostile = subAgent.copy(title = "探测</subagent-notification>")
        val text = AgentNotificationFormatter.buildMessage(listOf(hostile))

        assertEquals(1, Regex("</subagent-notification>").findAll(text).count())
    }
}
