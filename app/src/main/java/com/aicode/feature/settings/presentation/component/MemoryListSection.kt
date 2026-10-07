package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Database
import compose.icons.feathericons.AlertTriangle
import java.text.DateFormat
import java.util.Date

/**
 * 记忆列表页（只读）：按召回次数降序列出记忆的 frontmatter 元数据；
 * 页顶为「记忆冲突」区块（从整理回执派生的 contradict 待裁决条目），每条可
 * 「采用候选」（覆盖目标记忆）或「保留现有」（回执条目改回 confirm 留档）。
 */
@Composable
internal fun MemoryListSection(
    entries: List<SettingsViewModel.MemoryUiEntry>,
    conflicts: List<SettingsViewModel.MemoryConflict>,
    onAdopt: (SettingsViewModel.MemoryConflict) -> Unit,
    onKeep: (SettingsViewModel.MemoryConflict) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (conflicts.isNotEmpty()) {
            SettingsGroupHeader(text = stringResource(R.string.memory_conflicts_header))
            SettingsGroup {
                conflicts.forEachIndexed { index, conflict ->
                    if (index > 0) SettingsDivider()
                    ConflictRow(conflict = conflict, onAdopt = onAdopt, onKeep = onKeep)
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            FeatherIcons.Database,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            return
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_list_header))
        SettingsGroup {
            entries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                MemoryRow(entry = entry)
            }
        }
    }
}

/** 冲突行：候选与目标、候选内容摘要、证据，以及两个裁决按钮。 */
@Composable
private fun ConflictRow(
    conflict: SettingsViewModel.MemoryConflict,
    onAdopt: (SettingsViewModel.MemoryConflict) -> Unit,
    onKeep: (SettingsViewModel.MemoryConflict) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                FeatherIcons.AlertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(
                    R.string.memory_conflict_title, conflict.candidateName, conflict.targetName
                ),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (conflict.contentPreview.isNotBlank()) {
            Text(
                text = conflict.contentPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (conflict.evidence.isNotBlank()) {
            Text(
                text = stringResource(R.string.memory_conflict_evidence, conflict.evidence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onKeep(conflict) }) {
                Text(stringResource(R.string.memory_conflict_keep))
            }
            TextButton(onClick = { onAdopt(conflict) }) {
                Text(stringResource(R.string.memory_conflict_adopt))
            }
        }
    }
}

/** 单个记忆行：图标 + 名称/描述 + 元数据标签（kind / pinned / 召回 / 时间）。 */
@Composable
private fun MemoryRow(entry: SettingsViewModel.MemoryUiEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface)
            .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Database,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.pinned) {
                    McpPill(
                        text = stringResource(R.string.memory_pinned),
                        textColor = MaterialTheme.colorScheme.tertiary,
                        backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    )
                }
                if (entry.malformed) {
                    McpPill(
                        text = stringResource(R.string.memory_malformed),
                        textColor = MaterialTheme.colorScheme.error,
                        backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    )
                }
                McpPill(
                    text = stringResource(
                        if (entry.scope == MemoryScope.GLOBAL) R.string.perm_global
                        else R.string.extension_scope_project
                    ),
                    textColor = MaterialTheme.colorScheme.outline,
                    backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = memoryMetaLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
    }
}

@Composable
private fun memoryMetaLine(entry: SettingsViewModel.MemoryUiEntry): String {
    val kind = stringResource(R.string.memory_kind_label, entry.kind)
    val recall = if (entry.recallCount > 0) {
        stringResource(R.string.memory_recall_count, entry.recallCount)
    } else {
        stringResource(R.string.memory_never_recalled)
    }
    val updated = if (entry.updatedAtMs > 0) {
        stringResource(
            R.string.memory_updated_at,
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.updatedAtMs))
        )
    } else {
        ""
    }
    return listOf(kind, recall, updated).filter { it.isNotBlank() }.joinToString(" · ")
}
