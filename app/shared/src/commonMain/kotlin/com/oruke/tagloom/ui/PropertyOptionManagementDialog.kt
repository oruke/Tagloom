package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 共享层级选项目录的列表维护，改名移动保留稳定身份，失败保留表单。
 * @param state 已解锁目录与笔记草稿。
 * @param definition 当前属性定义。
 * @param startCreating 是否直接进入新建表单。
 * @param onCreated 新选项确认后的可选选择动作。
 * @param onDismiss 关闭维护页面。
 */
@Composable
internal fun PropertyOptionManagementDialog(
    state: TagloomEditorState,
    definition: PropertyDefinition,
    startCreating: Boolean = false,
    onCreated: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val catalog = LocalPropertyOptionCatalog.current ?: remember(state) { PropertyOptionCatalogController(null, state) }
    val current = state.propertyDefinitions.firstOrNull { it.id == definition.id } ?: definition
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(startCreating) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    var parentMenu by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val selected = current.options.firstOrNull { it.id == selectedId }
    val excluded = selectedId?.let { PropertyOptionTree.descendants(current.options, it) }.orEmpty()
    val hasChildren = selectedId != null && current.options.any { it.parentOptionId == selectedId }
    val used = selectedId != null && selectedId in catalog.used(definition.id)
    AlertDialog(
        onDismissRequest = { if (!catalog.busy) onDismiss() },
        modifier = Modifier.widthIn(max = 560.dp).testTag("option-management"),
        title = {
            Text(
                stringResource(
                    if (editing) {
                        if (selectedId == null) Res.string.option_new else Res.string.option_edit
                    } else Res.string.option_manage
                )
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (editing) 12.dp else 0.dp)
            ) {
                if (!editing) {
                    TextButton(onClick = {
                        selectedId = null; name = ""; parentId = null; invalid = false; editing = true
                    }) { Text(stringResource(Res.string.option_new)) }
                    current.options.sortedBy { PropertyOptionTree.label(current.options, it.id) }.forEach { option ->
                        val path = PropertyOptionTree.path(current.options, option.id)
                        Surface(
                            Modifier.fillMaxWidth().clickable {
                                selectedId = option.id; name = option.label; parentId = option.parentOptionId; invalid =
                                false; editing = true
                            }.testTag("option-edit-${option.id}"),
                            color = Color.Transparent,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                Modifier.heightIn(min = 48.dp).padding(
                                    start = ((path.size - 1).coerceAtMost(8) * 18 + 8).dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                    end = 8.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                    if (path.size > 1) Text(
                                        path.dropLast(1).joinToString(" / ") { it.label },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TagloomChevronDown(Modifier.size(12.dp).rotate(-90f))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else {
                    OutlinedTextField(
                        name,
                        { name = it; invalid = false },
                        Modifier.fillMaxWidth().testTag("option-name"),
                        label = { Text(stringResource(Res.string.option_name)) },
                        singleLine = true,
                        enabled = !catalog.busy
                    )
                    Text(stringResource(Res.string.option_parent), style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(
                            onClick = { parentMenu = true },
                            enabled = !catalog.busy,
                            modifier = Modifier.testTag("option-parent")
                        ) {
                            Text(
                                PropertyOptionTree.label(current.options, parentId)
                                    .ifBlank { stringResource(Res.string.option_root) })
                            TagloomChevronDown(Modifier.padding(start = 8.dp).size(12.dp))
                        }
                        TagloomDropdownMenu(parentMenu, { parentMenu = false }) {
                            TagloomMenuItem(
                                stringResource(Res.string.option_root),
                                onClick = { parentId = null; parentMenu = false })
                            current.options.filterNot { it.id in excluded }.forEach { option ->
                                TagloomMenuItem(
                                    PropertyOptionTree.label(current.options, option.id),
                                    onClick = { parentId = option.id; parentMenu = false })
                            }
                        }
                    }
                    if (selectedId != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (catalog.save(current.copy(options = current.options.filterNot { it.id == selectedId }))) {
                                        editing = false; selectedId = null
                                    }
                                }
                            },
                            enabled = !catalog.busy && !hasChildren && !used,
                            modifier = Modifier.testTag("option-delete")
                        ) { Text(stringResource(Res.string.option_delete)) }
                        if (hasChildren || used) {
                            Text(
                                stringResource(if (hasChildren) Res.string.option_delete_children else Res.string.option_delete_used),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (used && definition.id == com.oruke.tagloom.core.workspace.LinkNoteSchemas.GROUP_ID) LocalWorkspaceLinks.current?.let { links ->
                                TextButton(onClick = { links.collection.open(selectedId); onDismiss() }) {
                                    Text(
                                        stringResource(Res.string.option_open_notes)
                                    )
                                }
                            }
                        }
                    }
                }
                if (invalid || catalog.failed) Text(
                    stringResource(Res.string.option_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            if (editing) TextButton(
                enabled = name.isNotBlank() && !catalog.busy,
                modifier = Modifier.testTag("option-save"),
                onClick = {
                    scope.launch {
                        val id = selectedId ?: state.createEditorId("option")
                        val replacement = PropertyOption(id, name.trim(), selected?.colorToken, parentId)
                        val options = current.options.map { if (it.id == id) replacement else it }
                            .let { if (selectedId == null) it + replacement else it }
                        val next = runCatching { current.copy(options = options) }.getOrNull()
                        invalid = next == null
                        if (next != null && catalog.save(next)) {
                            if (selectedId == null && startCreating) onCreated(id) else {
                                editing = false; selectedId = null
                            }
                        }
                    }
                }) { Text(stringResource(Res.string.option_save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (editing && !startCreating) {
                    editing = false; invalid = false
                } else onDismiss()
            }, enabled = !catalog.busy) {
                Text(stringResource(if (editing) Res.string.link_cancel else Res.string.option_done))
            }
        },
    )
}
