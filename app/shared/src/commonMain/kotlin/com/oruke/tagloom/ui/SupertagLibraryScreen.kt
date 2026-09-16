package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.findSupertags
import com.oruke.tagloom.editor.referencesSupertag
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 使用名称和自然语言摘要构成超级标签管理列表，不引入表头或固定统计列。
 * @param state 当前目录与草稿引用。
 * @param controller 事务保存和删除入口。
 * @param onCreate 打开新建表单。
 * @param onEdit 打开编辑或只读表单。
 * @param onBack 返回当前笔记。
 */
@Composable
internal fun SupertagLibraryScreen(
    state: TagloomEditorState, controller: WorkspaceSupertagController,
    onCreate: () -> Unit, onEdit: (TagDefinition) -> Unit, onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = state.findSupertags(query)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                Icon(painterResource(Res.drawable.ic_arrow_back), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.workspace_settings_back))
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.supertag_library),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onCreate, enabled = !controller.busy, modifier = Modifier.testTag("supertag-create")) {
                    Icon(painterResource(Res.drawable.ic_plus), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.editor_tag_create))
                }
            }
            Text(
                stringResource(Res.string.supertag_library_description), Modifier.padding(top = 8.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().testTag("supertag-library-search"), singleLine = true,
                placeholder = { Text(stringResource(Res.string.supertag_search)) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(20.dp)) })
            Text(
                pluralStringResource(Res.plurals.supertag_count, matches.size, matches.size), Modifier.padding(vertical = 20.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (controller.failed) Text(
                stringResource(Res.string.supertag_delete_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(Modifier.weight(1f).testTag("supertag-library-list")) {
                items(matches, key = { it.id.value }) { tag ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !controller.busy) { onEdit(tag) }
                        .padding(vertical = 16.dp).testTag("supertag-library-item-${tag.id.value}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SupertagIcon(tag.iconKey)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tag.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                supertagSummary(state, tag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (tag.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) stringResource(Res.string.supertag_builtin)
                                else stringResource(
                                    Res.string.supertag_reference_summary,
                                    state.documents.count { state.referencesSupertag(it.note, tag.id) },
                                    state.tagDefinitions.count { tag.id in it.parentTagIds }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SupertagRowMenu(state, tag, controller, onEdit)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (matches.isEmpty()) item {
                    Text(
                        stringResource(Res.string.supertag_empty_hint),
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * 显示行维护菜单，对被引用或内置的超级标签禁用删除并说明原因。
 * @param state 当前笔记和子级目录。
 * @param tag 当前行。
 * @param controller 删除事务控制器。
 * @param onEdit 打开当前定义。
 */
@Composable
private fun SupertagRowMenu(
    state: TagloomEditorState,
    tag: TagDefinition,
    controller: WorkspaceSupertagController,
    onEdit: (TagDefinition) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val root = tag.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    val referenced = state.documents.any {
        state.referencesSupertag(
            it.note,
            tag.id
        )
    } || state.tagDefinitions.any { tag.id in it.parentTagIds }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = !controller.busy,
            modifier = Modifier.testTag("supertag-menu-${tag.id.value}")
        ) {
            Icon(
                painterResource(Res.drawable.ic_more),
                stringResource(Res.string.supertag_manage),
                Modifier.size(22.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (root) Res.string.editor_action_view else Res.string.editor_action_edit)) },
                onClick = { open = false; onEdit(tag) })
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.supertag_delete)) }, enabled = !root && !referenced,
                onClick = { open = false; controller.clearError(); confirm = true })
            if (referenced || root) Text(
                stringResource(if (root) Res.string.supertag_builtin else Res.string.supertag_delete_blocked),
                Modifier.widthIn(max = 250.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!controller.busy) confirm = false },
        title = { Text(stringResource(Res.string.supertag_delete)) },
        text = {
            Text(
                stringResource(
                    if (controller.failed) Res.string.supertag_delete_failed else Res.string.supertag_delete_confirm,
                    tag.name
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = !controller.busy,
                onClick = { scope.launch { if (controller.delete(tag)) confirm = false } }) {
                Text(stringResource(Res.string.supertag_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(enabled = !controller.busy, onClick = { confirm = false }) {
                Text(
                    stringResource(
                        Res.string.workspace_action_cancel
                    )
                )
            }
        },
    )
}
