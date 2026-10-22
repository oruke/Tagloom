package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceStorageMode
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.platform.testTag
import tagloom.app.shared.generated.resources.*

/**
 * 渲染搜索、新建、笔记切换和底部设置入口的桌面左侧导航。
 *
 * @param state 编辑器状态。
 * @param searchFocusRequester Ctrl+K 请求使用的搜索焦点入口。
 * @param onCreateDocument 创建并立即选择新笔记。
 * @param onSelectDocument 选择笔记并返回编辑页面。
 * @param onOpenSettings 打开当前工作区设置；预览无设置能力时为空。
 * @param settingsSelected 当前是否显示设置页面。
 * @param onOpenSupertags 打开超级标签管理。
 * @param supertagsSelected 当前是否显示超级标签维护页。
 * @param modifier 外部布局修饰符。
 * @param storageMode 当前设备已实际启用的工作区模式。
 * @param onOpenLinks 打开链接集合，平台未装配该能力时为空。
 * @param linksSelected 是否正在显示链接集合。
 */
@Composable
internal fun WorkspaceSidebar(
    state: TagloomEditorState,
    searchFocusRequester: FocusRequester,
    onCreateDocument: () -> Unit,
    onSelectDocument: (com.oruke.tagloom.core.model.ObjectId) -> Unit = state::selectDocument,
    onOpenSettings: (() -> Unit)? = null,
    settingsSelected: Boolean = false,
    onOpenSupertags: () -> Unit = {},
    supertagsSelected: Boolean = false,
    modifier: Modifier = Modifier,
    storageMode: WorkspaceStorageMode = WorkspaceStorageMode.LOCAL,
    onOpenLinks: (() -> Unit)? = null,
    linksSelected: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight()) {
            WorkspaceIdentity()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidebarSearchField(state, searchFocusRequester)
                NewDocumentAction(onCreateDocument)
                if (onOpenLinks != null) Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLinks).testTag("link-navigation"),
                    shape = MaterialTheme.shapes.small,
                    color = if (linksSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(Res.drawable.ic_link), null, Modifier.size(18.dp))
                        Text(stringResource(Res.string.link_library), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSupertags).testTag("supertag-navigation"),
                    shape = MaterialTheme.shapes.small,
                    color = if (supertagsSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(Res.drawable.ic_supertag), null, Modifier.size(18.dp))
                        Text(stringResource(Res.string.supertag_library), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.editor_sidebar_all_notes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    state.visibleDocuments.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.visibleDocuments, key = { document -> document.id.value }) { document ->
                    SidebarDocumentItem(
                        document = document,
                        selected = !settingsSelected && !supertagsSelected && !linksSelected && document.id == state.selectedDocumentId,
                        onClick = { onSelectDocument(document.id) },
                    )
                }
                if (state.visibleDocuments.isEmpty()) {
                    item(key = "empty-notes") {
                        Text(
                            if (state.filterText.isBlank()) {
                                stringResource(Res.string.editor_sidebar_empty_notes)
                            } else {
                                stringResource(Res.string.editor_sidebar_no_matching_notes)
                            },
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

/**
 * 渲染工作区身份，不把品牌标记包装成额外卡片。
 */
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
                Text(
                    "T",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                stringResource(Res.string.workspace_app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(Res.string.editor_sidebar_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染支持 Ctrl+K 聚焦的紧凑笔记过滤输入框。
 *
 * @param state 编辑器状态。
 * @param focusRequester 外部快捷键使用的焦点请求器。
 */
@Composable
private fun SidebarSearchField(
    state: TagloomEditorState,
    focusRequester: FocusRequester,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        BasicTextField(
            value = state.filterText,
            onValueChange = { value -> state.filterText = value },
            modifier = Modifier.fillMaxWidth().height(32.dp).focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (state.filterText.isBlank()) {
                            Text(
                                stringResource(Res.string.editor_sidebar_search_notes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                    Text(
                        "Ctrl K",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/**
 * 渲染左栏中始终可见的新建笔记主操作。
 *
 * @param onClick 创建动作。
 */
@Composable
private fun NewDocumentAction(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.editor_sidebar_new_note), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "Ctrl N",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
            )
        }
    }
}

/**
 * 渲染可切换的单篇笔记导航行。
 *
 * @param document 当前列表项文档。
 * @param selected 是否为当前活动笔记。
 * @param onClick 切换动作。
 */
@Composable
private fun SidebarDocumentItem(
    document: EditorDocument,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = document.note.title.ifBlank {
                    stringResource(Res.string.editor_sidebar_untitled_note)
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (document.dirty) {
                Surface(
                    modifier = Modifier.size(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {}
            }
        }
    }
}

/**
 * 渲染已解锁状态与当前实际存储模式，不能把服务器模式显示为仅本地。
 * @param mode 此设备当前工作区模式。
 */
@Composable
private fun SidebarSessionStatus(mode: WorkspaceStorageMode) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(7.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
        ) {}
        Text(
            stringResource(if (mode == WorkspaceStorageMode.SERVER) Res.string.workspace_mode_server_unlocked
                else Res.string.editor_sidebar_status_local_unlocked),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
