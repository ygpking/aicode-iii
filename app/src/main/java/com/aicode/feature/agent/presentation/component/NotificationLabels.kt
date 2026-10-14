package com.aicode.feature.agent.presentation.component

import androidx.annotation.StringRes
import com.aicode.R

/**
 * 通知提示条要渲染的一行文案：资源 id + 已解析出的参数，由调用方交给 `stringResource`。
 *
 * 拆成「资源 id + 参数」而不是直接拼字符串，是为了让文案映射能在 JVM 单测里钉住
 * （同 `toolRunningLabelRes` 的做法），也让译文切换不影响这里的判断。
 */
internal data class NotificationLabel(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList()
)

/** 通知正文里的一个 XML 块（一次 drain 可能合并多条）。 */
internal data class NotificationBlock(
    val kind: String,
    val status: String?,
    val fromKind: String?
)

private val BLOCK = Regex(
    "<(task-notification|subagent-notification|agent-message|mode-change)>(.*?)</\\1>",
    RegexOption.DOT_MATCHES_ALL
)
private val FIELD_STATUS = Regex("<status>(.*?)</status>")
private val FIELD_FROM_KIND = Regex("<from-kind>(.*?)</from-kind>")

/**
 * 解析通知正文里的结构化字段（块级）。
 *
 * 块内嵌套的 `<tail-output>` 等已被 domain 侧转义，不会截断块匹配；
 * 拿不到 `<status>`/`<from-kind>`（旧版本落库的历史消息）时对应字段为 null，由文案映射回退。
 */
internal fun parseNotificationBlocks(content: String): List<NotificationBlock> =
    BLOCK.findAll(content).map { m ->
        val body = m.groupValues[2]
        NotificationBlock(
            kind = m.groupValues[1],
            status = FIELD_STATUS.find(body)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
            fromKind = FIELD_FROM_KIND.find(body)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        )
    }.toList()

/**
 * 把通知 XML 块里的结构化字段映射成本地化文案。
 *
 * 为什么不让 UI 直接渲染 domain 生成的 `<summary>`：那段文本还要原样喂给 AI（是 AI 侧的契约），
 * 只能固定中文；直接拿去渲染会在英文界面下露出中文。这里改为 UI 自己按字段拼，domain 的
 * `<summary>` 保持不动。
 *
 * 旧版本落库的历史通知没有 `<from-kind>`，此时回退到不带发言方身份的通用文案，不显示空白。
 */
internal fun backgroundNotificationLabel(
    kind: String?,
    status: String?,
    fromKind: String?
): NotificationLabel = when (kind) {
    "task-notification" -> when (status) {
        "stopped" -> NotificationLabel(R.string.chat_notify_task_stopped)
        "failed" -> NotificationLabel(R.string.chat_notify_task_failed)
        else -> NotificationLabel(R.string.chat_notify_task_done)
    }
    "subagent-notification" -> when (status) {
        "stopped" -> NotificationLabel(R.string.chat_notify_subagent_stopped)
        "failed" -> NotificationLabel(R.string.chat_notify_subagent_failed)
        else -> NotificationLabel(R.string.chat_notify_subagent_done)
    }
    "agent-message" -> when (fromKind) {
        "parent" -> NotificationLabel(R.string.chat_notify_message_from_parent)
        "sibling" -> NotificationLabel(R.string.chat_notify_message_from_sibling)
        "sub" -> NotificationLabel(R.string.chat_notify_message_from_sub)
        else -> NotificationLabel(R.string.chat_notify_message_generic)
    }
    "mode-change" -> NotificationLabel(R.string.chat_notify_mode_changed)
    else -> NotificationLabel(R.string.chat_notify_unknown)
}
