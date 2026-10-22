package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 按链接类型查询为纵向列表，支持空值、精确分组和包含后代的过滤。
 * @param state 当前解锁快照及未保存草稿。
 * @param controller 集合查询与链接应用服务。
 * @param onOpenNote 打开内部笔记并关闭集合。
 */
@Composable
internal fun LinkCollectionPane(
    state: TagloomEditorState,
    controller: WorkspaceLinkController,
    onOpenNote: (ObjectId) -> Unit
) {
    val query = controller.collection
    val groupName = stringResource(Res.string.link_group)
    val group =
        state.propertyDefinitions.firstOrNull { it.id == LinkNoteSchemas.GROUP_ID } ?: LinkNoteSchemas.properties(
            groupName
        ).last()
    var picker by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    val ids = query.groupId?.let {
        if (query.includeChildren) PropertyOptionTree.descendants(
            group.options,
            it
        ) else setOf(it)
    }.orEmpty()
    val notes = state.documents.filter { document ->
        if (LinkNoteSchemas.TYPE_ID !in document.note.directTagIds) return@filter false
        val value = (document.note.propertyValues[LinkNoteSchemas.GROUP_ID]?.value as? JsonPrimitive)?.content
        val matchesGroup = !query.filtered || (if (query.groupId == null) value == null else value in ids)
        val searchable = listOf(
            document.note.title,
            LinkNotePolicy.address(document.note)?.value.orEmpty(),
            LinkNotePolicy.description(document.note)
        ).joinToString("\n")
        matchesGroup && (query.query.isBlank() || searchable.contains(query.query.trim(), true))
    }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp).testTag("link-library")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = query::close) { Text(stringResource(Res.string.editor_sidebar_all_notes)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { controller.begin() }) { Text(stringResource(Res.string.link_add)) }
        }
        Text(
            stringResource(Res.string.link_library),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 18.dp)
        )
        OutlinedTextField(
            query.query,
            { query.query = it },
            Modifier.fillMaxWidth().testTag("link-library-search"),
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.link_search)) },
            leadingIcon = { Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(18.dp)) })
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { picker = true }, modifier = Modifier.testTag("link-library-group")) {
                    Text(
                        if (!query.filtered) stringResource(Res.string.link_all_groups) else PropertyOptionTree.label(
                            group.options,
                            query.groupId
                        ).ifBlank { stringResource(Res.string.link_ungrouped) })
                    TagloomChevronDown(Modifier.padding(start = 6.dp).size(12.dp))
                }
                if (picker) HierarchicalOptionPicker(
                    state, group, query.groupId,
                    { query.groupId = it; query.filtered = true; picker = false }, { picker = false })
            }
            if (query.filtered) TextButton(onClick = {
                query.filtered = false
            }) { Text(stringResource(Res.string.link_all_groups)) }
            TextButton(onClick = { manage = true }) { Text(stringResource(Res.string.option_manage)) }
        }
        if (query.filtered && query.groupId != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(query.includeChildren, { query.includeChildren = it }, Modifier.testTag("link-include-children"))
            Text(stringResource(Res.string.link_include_descendants), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(notes, key = { it.id.value }) { document ->
                val address = LinkNotePolicy.address(document.note)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable { onOpenNote(document.id) }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .testTag("link-library-note-${document.id.value}"),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (address != null) Text(
                            address.host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(document.note.title, style = MaterialTheme.typography.titleSmall)
                        val description = LinkNotePolicy.description(document.note)
                        if (description.isNotBlank()) Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val groupId =
                            (document.note.propertyValues[LinkNoteSchemas.GROUP_ID]?.value as? JsonPrimitive)?.content
                        Text(
                            PropertyOptionTree.label(group.options, groupId)
                                .ifBlank { stringResource(Res.string.link_ungrouped) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (address != null) IconButton(onClick = { runCatching { uri.openUri(address.value) } }) {
                        Icon(
                            painterResource(Res.drawable.ic_open_external),
                            stringResource(Res.string.link_open_web),
                            Modifier.size(18.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (notes.isEmpty()) item {
                Text(
                    stringResource(Res.string.link_no_results),
                    Modifier.padding(vertical = 30.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (manage) PropertyOptionManagementDialog(state, group, onDismiss = { manage = false })
}
