package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_history_empty
import tagloom.app.shared.generated.resources.agent_history_search_placeholder
import tagloom.app.shared.generated.resources.agent_history_tab_active
import tagloom.app.shared.generated.resources.agent_history_tab_archived
import tagloom.app.shared.generated.resources.agent_history_title
import tagloom.app.shared.generated.resources.agent_thread_archive
import tagloom.app.shared.generated.resources.agent_thread_delete
import tagloom.app.shared.generated.resources.agent_thread_restore
import tagloom.app.shared.generated.resources.ic_archive
import tagloom.app.shared.generated.resources.ic_incognito
import tagloom.app.shared.generated.resources.ic_search
import tagloom.app.shared.generated.resources.ic_trash
import tagloom.app.shared.generated.resources.workspace_action_done

/**
 * 渲染从侧栏「最近会话 -> 全部 ▸」唤出的全部历史会话搜索、归档切换与删除对话框。
 */
@Composable
internal fun AgentThreadHistoryDialog(
    controller: WorkspaceAgentHarnessController,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }

    val filtered = remember(controller.threads, searchQuery, showArchived) {
        val kw = searchQuery.trim()
        controller.threads.filter { entry ->
            entry.thread.archived == showArchived &&
                (kw.isEmpty() || entry.thread.title.contains(kw, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("agent-history-dialog"),
        title = { Text(stringResource(Res.string.agent_history_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("agent-history-search-input"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.weight(1f)) {
                                    if (searchQuery.isBlank()) {
                                        Text(
                                            stringResource(Res.string.agent_history_search_placeholder),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            }
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        false to Res.string.agent_history_tab_active,
                        true to Res.string.agent_history_tab_archived,
                    ).forEach { (archivedTab, labelRes) ->
                        val selected = showArchived == archivedTab
                        Surface(
                            modifier = Modifier
                                .clickable { showArchived = archivedTab }
                                .testTag("agent-history-tab-${if (archivedTab) "archived" else "active"}"),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                stringResource(labelRes),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.agent_history_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id.value }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        scope.launch { controller.selectThreadForViewing(entry.id, openFullScreen = true) }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                                    .testTag("agent-history-row-${entry.id.value}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (entry.incognito) {
                                    Icon(painterResource(Res.drawable.ic_incognito), null, Modifier.size(14.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = entry.thread.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${entry.thread.messages.size} msgs",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!entry.incognito) {
                                    IconButton(
                                        onClick = { scope.launch { controller.setThreadArchived(entry.id, !entry.thread.archived) } },
                                        modifier = Modifier.size(30.dp).testTag("agent-history-archive-${entry.id.value}"),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_archive),
                                            contentDescription = stringResource(
                                                if (entry.thread.archived) Res.string.agent_thread_restore else Res.string.agent_thread_archive,
                                            ),
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { scope.launch { controller.deleteThread(entry.id) } },
                                    modifier = Modifier.size(30.dp).testTag("agent-history-delete-${entry.id.value}"),
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_trash),
                                        contentDescription = stringResource(Res.string.agent_thread_delete),
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.workspace_action_done))
            }
        },
    )
}
