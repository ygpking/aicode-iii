package com.aicode.feature.virtualscreen.presentation

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.virtualscreen.domain.a11y.VirtualScreenA11yService
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Eye
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.XCircle

/**
 * 「虚拟屏」设置页。
 *
 * 存在的理由：无障碍服务**只能由用户在系统设置里手动开启**，而且实测 MIUI 会在
 * 重装应用或 `force-stop` 之后把本服务从已启用列表里悄悄移除。没有这个入口，用户遇到
 * 「只能开不能操作」时既看不出问题在哪，也没有一键跳转的去处。
 *
 * 页面只做三件事：**如实显示状态、给出一键跳转、允许回收残留会话**。
 * 不做「自动帮用户开启无障碍」——那需要静默写 secure settings，绕过了用户知情，不做。
 */
@Composable
internal fun VirtualScreenSection(
    state: VirtualScreenSettingsState,
    onRefresh: () -> Unit,
    onCloseActiveSession: () -> Unit
) {
    val context = LocalContext.current

    // 从系统设置（无障碍页 / 电池优化页）返回时自动重新体检，用户不必手动刷新。
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    Column(
        // 不自带 verticalScroll / fillMaxSize：本组件嵌入「软件权限」页，
        // 那页整体已是一个滚动 Column，再嵌一层滚动会冲突、手势被内层吃掉。
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_virtual_screen_title))
        SettingsGroup {
            // ── 无障碍：本页最重要的一项 ───────────────────────────────
            val a11yHint = when {
                state.a11yConnected -> stringResource(R.string.settings_virtual_screen_a11y_on_hint)
                // 「已在设置里勾选但还没连上」与「压根没开启」是两种不同处境，提示不能混。
                state.a11yEnabledInSettings -> stringResource(R.string.settings_virtual_screen_a11y_pending_hint)
                else -> stringResource(R.string.settings_virtual_screen_a11y_off_hint)
            }
            SettingsRow(
                icon = FeatherIcons.Eye,
                title = stringResource(R.string.settings_virtual_screen_a11y),
                subtitle = a11yHint,
                // 已连上时点它也无意义（没得开），故只在未就绪时给点击。
                onClick = if (state.a11yConnected) null else {
                    { openAccessibilitySettings(context) }
                },
                trailing = { A11yStatusText(state) }
            )

            SettingsDivider()

            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.settings_virtual_screen_refresh),
                subtitle = stringResource(R.string.settings_virtual_screen_refresh_subtitle),
                onClick = onRefresh,
                enabled = !state.checking
            )

            SettingsDivider()

            SettingsRow(
                icon = FeatherIcons.Activity,
                title = stringResource(R.string.settings_virtual_screen_daemon),
                subtitle = stringResource(R.string.settings_virtual_screen_daemon_desc),
                trailing = {
                    Text(
                        text = stringResource(
                            if (state.daemonAlive) R.string.settings_virtual_screen_daemon_running
                            else R.string.settings_virtual_screen_daemon_stopped
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        // ── 残留会话：只在真有的时候才显示，避免平时占位造成困惑 ──────────
        // 多会话后可能同时存在多块屏（每个 AI 会话一块），故逐条列出包名与 displayId，
        // 而不是只报一个 displayId——用户需要知道「哪些会话还占着屏」。
        val sessions = state.activeSessions
        if (sessions.isNotEmpty()) {
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.XCircle,
                    title = stringResource(R.string.settings_virtual_screen_session_active, sessions.size),
                    subtitle = sessions.joinToString("\n") {
                        "${it.packageName}（displayId=${it.displayId}）"
                    } + "\n" + stringResource(R.string.settings_virtual_screen_session_active_desc),
                    onClick = onCloseActiveSession
                )
            }
        }
    }
}

/** 无障碍状态文字：三态（已连接 / 已开启未连接 / 未开启）。 */
@Composable
private fun A11yStatusText(state: VirtualScreenSettingsState) {
    val textRes = when {
        state.a11yConnected -> R.string.settings_permission_granted
        state.a11yEnabledInSettings -> R.string.settings_virtual_screen_a11y_pending
        else -> R.string.settings_permission_denied
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 跳到系统无障碍设置页。
 *
 * 只用标准的 `ACTION_ACCESSIBILITY_SETTINGS`，**不额外传「直接定位到本服务」的 extra**。
 * 那个 extra（AOSP Settings 的 `EXTRA_COMPONENT_NAME`）是 Settings 应用的包内实现细节，
 * 非公开 API、跳 MIUI 等自定义 ROM 不保证兼容，传错反而可能让 Intent 行为异常。
 * 代价只是用户需在列表里找到「AiCode III」——不值得为一个省一次翻找而依赖私有契约。
 */
private fun openAccessibilitySettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        // 个别 ROM 不认该 action 时兜底到「应用详情」，用户仍能从那里找到无障碍入口。
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}
