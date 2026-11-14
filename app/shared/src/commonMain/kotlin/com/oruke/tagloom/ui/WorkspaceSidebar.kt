package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.resolveNoteAiAccessPolicy
import com.oruke.tagloom.platform.WorkspaceAgentThreadEntry
import com.oruke.tagloom.platform.WorkspaceStorageMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染工作区唯一单左栏导航（搜索、`+ 新建笔记 / ✦ 新会话`、`全部笔记` 系统页、`最近会话 Top 5`、`最近笔记 Top 5` 与设置入口）。
 *
 * 严格去除冗余顶层「Agent 工作台」菜单项，并保障点击「最近会话」与「最近笔记」纯浏览切换时列表零跳动。
 */
@Composable
internal fun WorkspaceSidebar(
    state: TagloomEditorState,
    searchFocusRequester: FocusRequester,
    onCreateDocument: () -> Unit,
    onSelectDocument: (ObjectId) -> Unit = state::selectDocument,
    onOpenSettings: (() -> Unit)? = null,
    settingsSelected: Boolean = false,
    onOpenSupertags: () -> Unit = {},
    supertagsSelected: Boolean = false,
    modifier: Modifier = Modifier,
    storageMode: WorkspaceStorageMode = WorkspaceStorageMode.LOCAL,
    onOpenLinks: (() -> Unit)? = null,
    linksSelected: Boolean = false,
    onOpenAllNotes: (() -> Unit)? = null,
    allNotesSelected: Boolean = false,
    onDismissSpecialPages: () -> Unit = {},
) {
    val agentController = LocalWorkspaceAgentHarness.current
    val scope = rememberCoroutineScope()
    val nonSystemNoteCount = state.documents.count { !SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }

    Surface(
        modifier = modifier.fillMaxHeight().testTag("workspace-sidebar"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight()) {
            WorkspaceIdentity()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidebarSearchField(state, searchFocusRequester)
                SidebarQuickActionsRow(
                    hasAgentHarness = agentController?.available == true,
                    onCreateNote = onCreateDocument,
                    onCreateThread = {
                        onDismissSpecialPages()
                        scope.launch { agentController?.startNewThread(incognito = false, openFullScreen = true) }
                    },
                )
                if (onOpenAllNotes != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAllNotes).testTag("all-notes-navigation"),
                        shape = MaterialTheme.shapes.small,
                        color = if (allNotesSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_database_grid), null, Modifier.size(16.dp))
                            Text(
                                stringResource(Res.string.agent_sidebar_all_notes_db),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                nonSystemNoteCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (onOpenLinks != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLinks).testTag("link-navigation"),
                        shape = MaterialTheme.shapes.small,
                        color = if (linksSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_link), null, Modifier.size(16.dp))
                            Text(stringResource(Res.string.link_library), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSupertags).testTag("supertag-navigation"),
                    shape = MaterialTheme.shapes.small,
                    color = if (supertagsSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(Res.drawable.ic_supertag), null, Modifier.size(16.dp))
                        Text(stringResource(Res.string.supertag_library), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            val topThreads = if (agentController?.available == true) agentController.recentTopThreads else emptyList()
            val displayedNotes = agentController?.recentTopNotes
                ?: state.visibleDocuments.filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (agentController?.available == true) {
                    item(key = "recent-threads-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.agent_sidebar_recent_threads),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onDismissSpecialPages()
                                    scope.launch { agentController.startNewThread(incognito = true, openFullScreen = true) }
                                },
                                modifier = Modifier.size(24.dp).testTag("sidebar-new-incognito-thread"),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_incognito),
                                    contentDescription = stringResource(Res.string.agent_sidebar_incognito_thread),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stringResource(Res.string.agent_sidebar_view_all),
                                modifier = Modifier
                                    .clickable { agentController.historyDialogOpen = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("sidebar-all-threads"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (topThreads.isEmpty()) {
                        item(key = "empty-threads") {
                            Text(
                                stringResource(Res.string.agent_sidebar_empty_threads),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(topThreads, key = { "thread-${it.id.value}" }) { entry ->
                            val selected = !settingsSelected && !supertagsSelected && !linksSelected &&
                                agentController.fullScreenAgentActive && agentController.selectedThreadId == entry.id
                            SidebarThreadItem(
                                entry = entry,
                                selected = selected,
                                onClick = {
                                    onDismissSpecialPages()
                                    scope.launch { agentController.selectThreadForViewing(entry.id, openFullScreen = true) }
                                },
                            )
                        }
                    }
                }

                item(key = "recent-notes-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                if (agentController != null) Res.string.agent_sidebar_recent_notes
                                else Res.string.editor_sidebar_all_notes,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (onOpenAllNotes != null) {
                            Text(
                                text = stringResource(Res.string.agent_sidebar_view_all),
                                modifier = Modifier
                                    .clickable(onClick = onOpenAllNotes)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("sidebar-all-notes-more"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                displayedNotes.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(displayedNotes, key = { document -> document.id.value }) { document ->
                    val noteSelected = !settingsSelected && !supertagsSelected && !linksSelected &&
                        !allNotesSelected && agentController?.fullScreenAgentActive != true &&
                        document.id == state.selectedDocumentId
                    SidebarDocumentItem(
                        state = state,
                        document = document,
                        selected = noteSelected,
                        onClick = { onSelectDocument(document.id) },
                    )
                }
                if (displayedNotes.isEmpty()) {
                    item(key = "empty-notes") {
                        Text(
                            if (state.filterText.isBlank()) stringResource(Res.string.editor_sidebar_empty_notes)
                            else stringResource(Res.string.editor_sidebar_no_matching_notes),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onOpenSettings != null) {
                WorkspaceSettingsEntry(
                    selected = settingsSelected,
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            SidebarSessionStatus(storageMode)
        }
    }
}

/** 渲染工作区品牌身份行。 */
@Composable
private fun WorkspaceIdentity() {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("T", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(Res.string.workspace_app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.editor_sidebar_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 渲染支持 Ctrl+K 聚焦的笔记搜索过滤框。 */
@Composable
private fun SidebarSearchField(state: TagloomEditorState, focusRequester: FocusRequester) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        BasicTextField(
            value = state.filterText,
            onValueChange = { value -> state.filterText = value },
            modifier = Modifier.fillMaxWidth().height(32.dp).focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (state.filterText.isBlank()) {
                            Text(stringResource(Res.string.editor_sidebar_search_notes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                    Text("Ctrl K", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/** 渲染左栏顶部 `[+ 新建笔记]` 与 `[✦ 新会话]` 并排快捷按钮组（预览无 Agent 网关时降级为单按钮）。 */
@Composable
private fun SidebarQuickActionsRow(
    hasAgentHarness: Boolean,
    onCreateNote: () -> Unit,
    onCreateThread: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.weight(1f).clickable(onClick = onCreateNote).testTag("sidebar-new-note"),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.height(32.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (hasAgentHarness) "+ ${stringResource(Res.string.agent_sidebar_new_note)}"
                    else stringResource(Res.string.editor_sidebar_new_note),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        if (hasAgentHarness) {
            Surface(
                modifier = Modifier.weight(1f).clickable(onClick = onCreateThread).testTag("sidebar-new-thread"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.height(32.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(painterResource(Res.drawable.ic_sparkle), null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(Res.string.agent_sidebar_new_thread),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 渲染「最近会话 Top 5」中的单条对话项（点击仅查看，绝不跳序）。 */
@Composable
private fun SidebarThreadItem(
    entry: WorkspaceAgentThreadEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("sidebar-thread-${entry.id.value}"),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.height(30.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(if (entry.incognito) Res.drawable.ic_incognito else Res.drawable.ic_sparkle),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (entry.incognito) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = entry.thread.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/** 渲染「最近笔记 Top 5」中的单篇笔记导航行及非默认 AI 访问策略徽章。 */
@Composable
private fun SidebarDocumentItem(state: TagloomEditorState, document: EditorDocument, selected: Boolean, onClick: () -> Unit) {
    val policy = state.resolveNoteAiAccessPolicy(document.note).effectivePolicy
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("sidebar-note-${document.id.value}"),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = document.note.title.ifBlank { stringResource(Res.string.editor_sidebar_untitled_note) },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AiAccessPolicyBadge(policy = policy, showAllow = false)
            if (document.dirty) {
                Surface(Modifier.size(6.dp), color = MaterialTheme.colorScheme.primary, shape = CircleShape) {}
            }
        }
    }
}

/** 渲染底部当前设备工作区解锁状态与存储模式。 */
@Composable
private fun SidebarSessionStatus(mode: WorkspaceStorageMode) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(7.dp), color = MaterialTheme.colorScheme.primary, shape = CircleShape) {}
        Text(
            stringResource(
                if (mode == WorkspaceStorageMode.SERVER) Res.string.workspace_mode_server_unlocked
                else Res.string.editor_sidebar_status_local_unlocked,
            ),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
