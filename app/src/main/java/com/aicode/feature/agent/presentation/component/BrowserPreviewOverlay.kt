package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.domain.tool.browser.BrowserOperation
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 右上角悬浮预览小窗：AI 操作浏览器时显示实时页面画面 + 当前动作，可展开看完整操作时间线。
 *
 * 内嵌的是**同一个真实 WebView**（经 [BrowserManager.getOrCreatePreviewView] 挂载），
 * 因此展示的就是 AI 正在操作的页面本体，不是截图快照；面板未打开时也能看到实时动作。
 */
@Composable
internal fun BrowserPreviewOverlay(
    browserManager: BrowserManager,
    operations: List<BrowserOperation>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (operations.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var timelineOpen by remember { mutableStateOf(false) }
    val latest = operations.last()
    val hasError = operations.any { it.isError }

    val width = if (expanded) 320.dp else 200.dp
    val previewHeight = if (expanded) 420.dp else 260.dp

    // 放大/收起后重算 WebView 缩放（小窗尺寸变了）。
    LaunchedEffect(expanded) { browserManager.applyPreviewScaling() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            // 整个预览窗吃掉触摸：防止在其上的滑动/点击穿透到下层聊天列表（误触主窗手势）。
            // Main pass 消费：WebView 作为内层先收到并处理（网页滚动等），未命中 WebView 的
            // 区域（标题行/动作行/边距）由这里消费，不再冒泡到 LazyColumn。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题行：图标 + 标题 + 展开/收起 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    FeatherIcons.Globe,
                    contentDescription = null,
                    tint = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Spacing.xs)
                Text(
                    text = "浏览器预览",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 按钮：加大点击区，且用 IconButton 保证命中率
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (expanded) FeatherIcons.Minimize2 else FeatherIcons.Maximize2,
                        contentDescription = if (expanded) "收起" else "放大",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = { onClose() }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = "关闭预览",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 实时画面：内嵌真实 WebView
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .background(androidx.compose.ui.graphics.Color.White)
            ) {
                AndroidView(
                    factory = { context -> browserManager.getOrCreatePreviewView(context) },
                    update = { view ->
                        // 尺寸可能刚变（首帧/放大收起）：重算缩放并重绘。
                        browserManager.applyPreviewScaling()
                        view.requestLayout()
                        view.invalidate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                )
                // 卸载预览窗时把 WebView 交回其它宿主，避免被回收时仍挂在此处
                DisposableEffect(Unit) {
                    onDispose { browserManager.detachPreviewView() }
                }
            }

            // 当前动作行：点击展开时间线
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { timelineOpen = !timelineOpen }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (timelineOpen) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Spacing.xs)
                Text(
                    text = buildString {
                        append(latest.label)
                        if (latest.target.isNotBlank()) append(" · ${latest.target}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${operations.size} 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开：完整操作时间线
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            AnimatedVisibility(visible = timelineOpen) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 惰性渲染：时间线是整段 AI 浏览器任务累积的，长任务可达上百条，
                    // 非惰性 Column + forEach 会一次性组合全部行。
                    itemsIndexed(operations, key = { i, op -> "${op.timestamp}#$i" }) { _, op ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = fmt.format(Date(op.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = (if (op.fromAi) "AI " else "用户 ") + op.label +
                                    if (op.target.isNotBlank()) " · ${op.target}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (op.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组件内使用的小间距；避免与 Spacing 命名冲突时可直接用 dp。 */
@Composable
private fun Spacer(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.size(size))
}
