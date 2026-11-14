package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentCitationStatus
import com.oruke.tagloom.core.ai.AgentSourceEntry
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_citation_open_note
import tagloom.app.shared.generated.resources.agent_citation_removed
import tagloom.app.shared.generated.resources.agent_citation_updated
import tagloom.app.shared.generated.resources.agent_citation_verified

/**
 * 渲染已通过 `SourceLedger` 防幻觉核验的可交互 `[1]` 来源角标，以及点击展开的原段落摘录、实时状态核验与原位跳转浮层。
 *
 * @param keySuffix 同一消息内角标实例去重稳定键。
 * @param entry `SourceLedger` 登记的真实权威来源条目。
 * @param status 基于当前工作区笔记快照实时比对得出的修订状态（一致 / 已修改 / 已删除）。
 * @param onJumpToCitation 点击「打开笔记并定位段落」时的原位跳转回调。
 */
@Composable
internal fun CitationBadgeWithPreview(
    keySuffix: String,
    entry: AgentSourceEntry,
    status: AgentCitationStatus,
    onJumpToCitation: (AgentSourceEntry) -> Unit,
) {
    var previewOpen by remember(keySuffix) { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .clickable { previewOpen = true }
                .testTag("agent-citation-badge-${entry.sourceIndex}"),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = "[${entry.sourceIndex}]",
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TagloomDropdownMenu(expanded = previewOpen, onDismissRequest = { previewOpen = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 250.dp, max = 320.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("agent-citation-preview-${entry.sourceIndex}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.noteTitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText = when (status) {
                        AgentCitationStatus.VERIFIED -> stringResource(Res.string.agent_citation_verified)
                        AgentCitationStatus.UPDATED -> stringResource(Res.string.agent_citation_updated)
                        AgentCitationStatus.REMOVED -> stringResource(Res.string.agent_citation_removed)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.excerpt.isNotBlank()) {
                    Text(
                        text = entry.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status != AgentCitationStatus.REMOVED) {
                    TextButton(
                        onClick = {
                            previewOpen = false
                            onJumpToCitation(entry)
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("agent-citation-jump-${entry.sourceIndex}"),
                    ) {
                        Text(stringResource(Res.string.agent_citation_open_note))
                    }
                }
            }
        }
    }
}
