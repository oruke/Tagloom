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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.resolveNoteAiAccessPolicy
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_picker_attach_selection
import tagloom.app.shared.generated.resources.agent_picker_blocks_dialog_title
import tagloom.app.shared.generated.resources.agent_picker_blocks_select_all
import tagloom.app.shared.generated.resources.agent_picker_search_placeholder
import tagloom.app.shared.generated.resources.agent_picker_tab_notes
import tagloom.app.shared.generated.resources.agent_picker_tab_queries
import tagloom.app.shared.generated.resources.agent_picker_tab_types
import tagloom.app.shared.generated.resources.editor_sidebar_untitled_note
import tagloom.app.shared.generated.resources.ic_search
import tagloom.app.shared.generated.resources.workspace_action_cancel
import tagloom.app.shared.generated.resources.workspace_action_done

/**
 * 渲染作曲框 `@` 多类型上下文挂载选择器弹层。
 *
 * 支持挂载：
 * 1. 单篇笔记（`ALLOW` 与 `REFERENCE_ONLY` 可选，`LOCKED` 置灰不可选并带锁形徽章）；
 * 2. 当前编辑器选中段落（`BLOCKS`）；
 * 3. 笔记类型闭包范围（`TAG_SCOPE`，严格不带 `#` 前缀）；
 * 4. 已保存查询范围（`SAVED_QUERY_SCOPE`）。
 */
@Composable
internal fun AgentContextPickerDropdown(
    expanded: Boolean,
    state: TagloomEditorState,
    savedQueries: List<WorkspaceSavedQuery>,
    onDismiss: () -> Unit,
    onAttachNote: (ObjectId, String, List<BlockId>) -> Unit,
    onAttachTagScope: (TagId, String) -> Unit,
    onAttachQueryScope: (ObjectId, String) -> Unit,
) {
    var searchText by remember(expanded) { mutableStateOf("") }
    var activeTab by remember(expanded) { mutableIntStateOf(0) }
    val untitled = stringResource(Res.string.editor_sidebar_untitled_note)

    TagloomDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 380.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = 360.dp)
                .padding(vertical = 4.dp)
                .testTag("agent-context-picker-popup"),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).testTag("agent-picker-search"),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f)) {
                                if (searchText.isBlank()) {
                                    Text(
                                        stringResource(Res.string.agent_picker_search_placeholder),
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

            val selectedDoc = state.selectedDocument
            val selectedBlockIds = state.selection.ids.toList()
            if (selectedDoc != null && selectedBlockIds.isNotEmpty()) {
                val selPolicy = state.resolveNoteAiAccessPolicy(selectedDoc.note).effectivePolicy
                if (selPolicy != AiAccessPolicy.LOCKED) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .clickable {
                                onAttachNote(selectedDoc.id, selectedDoc.note.title.ifBlank { untitled }, selectedBlockIds)
                                onDismiss()
                            },
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(Res.string.agent_picker_attach_selection, selectedBlockIds.size),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    0 to Res.string.agent_picker_tab_notes,
                    1 to Res.string.agent_picker_tab_types,
                    2 to Res.string.agent_picker_tab_queries,
                ).forEach { (idx, labelRes) ->
                    val selected = activeTab == idx
                    Surface(
                        modifier = Modifier.clickable { activeTab = idx }.testTag("agent-picker-tab-$idx"),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            stringResource(labelRes),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val kw = searchText.trim()
            Column(Modifier.fillMaxWidth()) {
                when (activeTab) {
                    0 -> {
                        val notes = state.documents.filter { doc ->
                            !SystemDatabaseDefinitions.isProtectedSystemPage(doc.id) &&
                                (kw.isEmpty() || doc.note.title.contains(kw, ignoreCase = true))
                        }.take(50)
                        notes.forEach { doc ->
                            val policy = state.resolveNoteAiAccessPolicy(doc.note).effectivePolicy
                            val locked = policy == AiAccessPolicy.LOCKED
                            val title = doc.note.title.ifBlank { untitled }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (locked) 0.52f else 1f)
                                    .clickable(enabled = !locked) {
                                        onAttachNote(doc.id, title, emptyList())
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("agent-picker-note-${doc.id.value}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                AiAccessPolicyBadge(policy = policy, showAllow = false)
                            }
                        }
                    }
                    1 -> {
                        val types = state.tagDefinitions.filter { tag ->
                            tag.id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID &&
                                (kw.isEmpty() || tag.name.contains(kw, ignoreCase = true))
                        }
                        types.forEach { tag ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAttachTagScope(tag.id, tag.name)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("agent-picker-type-${tag.id.value}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                AiAccessPolicyBadge(policy = tag.aiAccessPolicy, showAllow = false)
                            }
                        }
                    }
                    else -> {
                        val queries = savedQueries.filter { kw.isEmpty() || it.query.name.contains(kw, ignoreCase = true) }
                        queries.forEach { sq ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAttachQueryScope(sq.id, sq.query.name)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("agent-picker-query-${sq.id.value}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(sq.query.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染已挂载单篇笔记引用 chip 的二次段落级勾选对话框（保存稳定 `excludedBlockIds`）。
 */
@Composable
internal fun AgentNoteBlockSelectorDialog(
    ref: AgentContextRef,
    state: TagloomEditorState,
    onConfirm: (AgentContextRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val doc = state.documents.firstOrNull { it.id == ref.noteId } ?: run {
        onDismiss()
        return
    }
    val activeBlocks = remember(doc.note.blocks) {
        doc.note.blocks.filter { !it.deleted && !it.extractPlainText().isNullOrBlank() }
    }
    var excluded by remember(ref.id, ref.excludedBlockIds) { mutableStateOf(ref.excludedBlockIds.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_picker_blocks_dialog_title, ref.label)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { excluded = emptySet() }) {
                    Text(stringResource(Res.string.agent_picker_blocks_select_all))
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(activeBlocks, key = { it.id.value }) { block ->
                        val checked = block.id !in excluded
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    excluded = if (checked) excluded + block.id else excluded - block.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { want ->
                                    excluded = if (want) excluded - block.id else excluded + block.id
                                },
                            )
                            Text(
                                block.extractPlainText().orEmpty().trim(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(ref.copy(excludedBlockIds = excluded.toList()))
                onDismiss()
            }) { Text(stringResource(Res.string.workspace_action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.workspace_action_cancel)) }
        },
    )
}
