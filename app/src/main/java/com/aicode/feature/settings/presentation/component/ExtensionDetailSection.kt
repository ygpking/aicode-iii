package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.presentation.SettingsViewModel

/**
 * 扩展详情页：描述卡、贡献明细卡（各贡献目录逐行列出，含 MCP 配置文件）、
 * 根路径卡；解析期有问题的扩展在页顶红字列出错误。
 */
@Composable
internal fun ExtensionDetailSection(
    entry: SettingsViewModel.ExtensionUiEntry
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (entry.errors.isNotEmpty()) {
            SettingsGroupHeader(text = stringResource(R.string.extension_errors_header))
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entry.errors.forEach { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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

        SettingsGroupHeader(text = stringResource(R.string.extension_contributions_header))
        SettingsGroup {
            ContributionLines(
                label = stringResource(R.string.extension_contrib_skills),
                items = entry.skillDirs
            )
            if (entry.promptDirs.isNotEmpty()) SettingsDivider()
            ContributionLines(
                label = stringResource(R.string.extension_contrib_prompts),
                items = entry.promptDirs
            )
            if (entry.memoryDirs.isNotEmpty()) SettingsDivider()
            ContributionLines(
                label = stringResource(R.string.extension_contrib_memory),
                items = entry.memoryDirs
            )
            if (entry.agentDirs.isNotEmpty()) SettingsDivider()
            ContributionLines(
                label = stringResource(R.string.extension_contrib_agents),
                items = entry.agentDirs
            )
            if (entry.mcpFile != null) SettingsDivider()
            ContributionLines(
                label = stringResource(R.string.extension_contrib_mcp),
                items = entry.mcpFile?.let { listOf(it) } ?: emptyList()
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.extension_root_header))
        SettingsGroup {
            Text(
                text = entry.rootPath,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ContributionLines(label: String, items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.extension_contrib_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        } else {
            items.forEach { item ->
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
