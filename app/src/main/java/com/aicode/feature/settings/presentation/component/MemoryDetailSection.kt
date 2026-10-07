package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.settings.presentation.SettingsViewModel
import java.text.DateFormat
import java.util.Date

/**
 * 记忆详情页：元数据卡（kind/作用域/召回/更新时间/triggers）+ 正文卡（Markdown 渲染，限高滚动）。
 */
@Composable
internal fun MemoryDetailSection(
    entry: SettingsViewModel.MemoryUiEntry,
    content: String?,
    cache: MarkdownRenderCache? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.memory_detail_meta))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetaLine(
                    label = stringResource(R.string.memory_kind_label, entry.kind)
                )
                MetaLine(
                    label = stringResource(
                        if (entry.scope == com.aicode.feature.agent.domain.memory.MemoryScope.GLOBAL) {
                            R.string.perm_global
                        } else {
                            R.string.extension_scope_project
                        }
                    )
                )
                MetaLine(
                    label = if (entry.recallCount > 0) {
                        stringResource(R.string.memory_recall_count, entry.recallCount)
                    } else {
                        stringResource(R.string.memory_never_recalled)
                    }
                )
                if (entry.updatedAtMs > 0) {
                    MetaLine(
                        label = stringResource(
                            R.string.memory_updated_at,
                            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.updatedAtMs))
                        )
                    )
                }
                if (entry.pinned) {
                    MetaLine(label = stringResource(R.string.memory_pinned))
                }
                if (entry.malformed) {
                    MetaLine(
                        label = stringResource(R.string.memory_malformed),
                        isError = true
                    )
                }
            }
        }

        if (entry.description.isNotBlank()) {
            SettingsGroupHeader(text = stringResource(R.string.skills_summary))
            SettingsGroup {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_detail_content))
        SettingsGroup {
            MarkdownContent(
                text = content ?: stringResource(R.string.memory_content_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
                cache = cache,
                lazyScroll = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.62f)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, isError: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.semanticColors.subtleText
    )
}
