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
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染只承担搜索、新建和笔记切换的桌面左侧导航。
 *
 * @param state 编辑器状态。
 * @param searchFocusRequester Ctrl+K 请求使用的搜索焦点入口。
 * @param onCreateDocument 创建并立即选择新笔记。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun WorkspaceSidebar(
    state: TagloomEditorState,
    searchFocusRequester: FocusRequester,
    onCreateDocument: () -> Unit,
    modifier: Modifier = Modifier,
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
                        selected = document.id == state.selectedDocumentId,
                        onClick = { state.selectDocument(document.id) },
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
            SidebarSessionStatus()
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
 * 渲染不会与导航争夺注意力的本地解锁状态。
 */
@Composable
private fun SidebarSessionStatus() {
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
            stringResource(Res.string.editor_sidebar_status_local_unlocked),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
