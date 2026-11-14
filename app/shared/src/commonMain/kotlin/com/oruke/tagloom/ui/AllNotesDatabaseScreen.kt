package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.DatasetProjectionRow
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.ViewPresentationKinds
import com.oruke.tagloom.core.query.DatabaseProjectionService
import com.oruke.tagloom.core.query.ProjectionNote
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_sidebar_new_note
import tagloom.app.shared.generated.resources.agent_toggle_companion
import tagloom.app.shared.generated.resources.all_notes_db_all_types
import tagloom.app.shared.generated.resources.all_notes_db_col_ai_policy
import tagloom.app.shared.generated.resources.all_notes_db_col_type
import tagloom.app.shared.generated.resources.all_notes_db_col_updated
import tagloom.app.shared.generated.resources.all_notes_db_empty
import tagloom.app.shared.generated.resources.all_notes_db_search_placeholder
import tagloom.app.shared.generated.resources.all_notes_db_sort_title
import tagloom.app.shared.generated.resources.all_notes_db_sort_updated
import tagloom.app.shared.generated.resources.all_notes_db_system_badge
import tagloom.app.shared.generated.resources.all_notes_db_title
import tagloom.app.shared.generated.resources.all_notes_db_view_gallery
import tagloom.app.shared.generated.resources.all_notes_db_view_kanban
import tagloom.app.shared.generated.resources.all_notes_db_view_table
import tagloom.app.shared.generated.resources.database_system_title
import tagloom.app.shared.generated.resources.editor_sidebar_untitled_note
import tagloom.app.shared.generated.resources.ic_database_grid
import tagloom.app.shared.generated.resources.ic_search
import tagloom.app.shared.generated.resources.ic_sparkle

/**
 * 渲染系统默认且受防误删保护的「全部笔记」根类型 Database 页面。
 *
 * 通过 [SystemDatabaseDefinitions] 与 [DatabaseProjectionService] 全量查询工作区根类型 `Note`
 * （`includeDescendants = true`）并自动排除系统容器自身，原生支持 Table / Kanban / Gallery 多视图切换、
 * 全文检索、笔记类型闭包筛选、排序切换及 `AiAccessPolicy` 徽章展示。
 */
@Composable
internal fun AllNotesDatabaseScreen(
    state: TagloomEditorState,
    onOpenNote: (ObjectId) -> Unit,
    onCreateNote: () -> Unit,
    onToggleCompanion: (() -> Unit)? = null,
    companionActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dbTitle = stringResource(Res.string.all_notes_db_title)
    var viewKind by rememberSaveable { mutableStateOf(ViewPresentationKinds.TABLE) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTagIdText by rememberSaveable { mutableStateOf("") }
    var sortByUpdatedDesc by rememberSaveable { mutableStateOf(true) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    val filterTagId = selectedTagIdText.takeIf { it.isNotBlank() }?.let(::TagId)
    val projection = remember(
        state.documents.toList(),
        state.tagDefinitions.toList(),
        state.propertyDefinitions.toList(),
        viewKind,
        searchQuery,
        filterTagId,
        sortByUpdatedDesc,
        dbTitle,
    ) {
        val db = SystemDatabaseDefinitions.createAllNotesDatabase(name = dbTitle)
        val view = SystemDatabaseDefinitions.createAllNotesView(
            presentationKind = viewKind,
            searchQuery = searchQuery,
            filterTagId = filterTagId,
            sortByUpdatedDescending = sortByUpdatedDesc,
            availableProperties = state.propertyDefinitions,
        )
        val notes = state.documents.map { doc ->
            ProjectionNote(doc.id, doc.note, doc.revision, doc.createdAt, doc.updatedAt)
        }
        DatabaseProjectionService(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).project(
            database = db,
            view = view,
            notes = notes,
            tags = state.tagDefinitions,
            properties = state.propertyDefinitions,
        )
    }

    Surface(modifier = modifier.fillMaxSize().testTag("all-notes-database-screen"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(Res.drawable.ic_database_grid),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(dbTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        projection.rows.size.toString(),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(Res.string.all_notes_db_system_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clickable(onClick = onCreateNote).testTag("all-notes-create-button"),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(Res.string.agent_sidebar_new_note),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (onToggleCompanion != null) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onToggleCompanion,
                        modifier = Modifier.size(34.dp).testTag("all-notes-companion-toggle"),
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_sparkle),
                            contentDescription = stringResource(Res.string.agent_toggle_companion),
                            tint = if (companionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        ViewPresentationKinds.TABLE to Res.string.all_notes_db_view_table,
                        ViewPresentationKinds.KANBAN to Res.string.all_notes_db_view_kanban,
                        ViewPresentationKinds.GALLERY to Res.string.all_notes_db_view_gallery,
                    ).forEach { (kind, labelRes) ->
                        val active = viewKind == kind
                        Surface(
                            modifier = Modifier.clickable { viewKind = kind }.testTag("all-notes-view-$kind"),
                            color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                stringResource(labelRes),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        modifier = Modifier.width(220.dp).padding(horizontal = 10.dp, vertical = 6.dp).testTag("all-notes-search-input"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.weight(1f)) {
                                    if (searchQuery.isBlank()) {
                                        Text(
                                            stringResource(Res.string.all_notes_db_search_placeholder),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    inner()
                                }
                            }
                        },
                    )
                }

                Box {
                    val activeTypeLabel = filterTagId?.let { id ->
                        state.tagDefinitions.firstOrNull { it.id == id }?.name
                    } ?: stringResource(Res.string.all_notes_db_all_types)
                    Surface(
                        modifier = Modifier.clickable { typeMenuOpen = true }.testTag("all-notes-type-filter"),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            "$activeTypeLabel ▾",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TagloomDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        TagloomMenuItem(
                            text = stringResource(Res.string.all_notes_db_all_types),
                            selected = filterTagId == null,
                            onClick = { selectedTagIdText = ""; typeMenuOpen = false },
                        )
                        TagloomMenuDivider()
                        state.tagDefinitions
                            .filterNot { it.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID }
                            .forEach { tag ->
                                TagloomMenuItem(
                                    text = tag.name,
                                    selected = filterTagId == tag.id,
                                    onClick = { selectedTagIdText = tag.id.value; typeMenuOpen = false },
                                )
                            }
                    }
                }

                Surface(
                    modifier = Modifier.clickable { sortByUpdatedDesc = !sortByUpdatedDesc }.testTag("all-notes-sort-toggle"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        stringResource(if (sortByUpdatedDesc) Res.string.all_notes_db_sort_updated else Res.string.all_notes_db_sort_title),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            if (projection.rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.all_notes_db_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                when (viewKind) {
                    ViewPresentationKinds.KANBAN -> AllNotesKanbanContent(state, projection, onOpenNote, Modifier.weight(1f))
                    ViewPresentationKinds.GALLERY -> AllNotesGalleryContent(state, projection, onOpenNote, Modifier.weight(1f))
                    else -> AllNotesTableContent(state, projection, onOpenNote, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AllNotesTableContent(
    state: TagloomEditorState,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateTime = currentDateTimeSettings()
    val untitled = stringResource(Res.string.editor_sidebar_untitled_note)
    val typeField = FieldRef(FieldRefKinds.OBJECT_TYPE)
    val aiField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.AI_ACCESS_POLICY)
    val updatedField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.UPDATED_AT)
    val tagNames = remember(state.tagDefinitions.toList()) { state.tagDefinitions.associate { it.id.value to it.name } }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.database_system_title), Modifier.weight(1.8f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.all_notes_db_col_type), Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.all_notes_db_col_ai_policy), Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.all_notes_db_col_updated), Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(projection.rows, key = { it.noteId.value }) { row ->
                val policy = parseRowAiPolicy(row, aiField)
                val rawType = row.values[typeField].datasetContentText()
                val typeDisplay = rawType.split(", ").mapNotNull { id -> tagNames[id.trim()] }.joinToString(" · ").ifBlank { "Note" }
                val updatedDisplay = dateTime.format(row.values[updatedField].datasetContentText())
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenNote(row.noteId) }.testTag("all-notes-row-${row.noteId.value}"),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.title.ifBlank { untitled },
                                modifier = Modifier.weight(1.8f).padding(end = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                typeDisplay,
                                modifier = Modifier.weight(0.9f).padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(Modifier.width(88.dp)) {
                                AiAccessPolicyBadge(policy = policy, showAllow = true)
                            }
                            Text(
                                updatedDisplay,
                                modifier = Modifier.width(150.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AllNotesKanbanContent(
    state: TagloomEditorState,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val untitled = stringResource(Res.string.editor_sidebar_untitled_note)
    val aiField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.AI_ACCESS_POLICY)
    val typeField = FieldRef(FieldRefKinds.OBJECT_TYPE)
    val tagNames = remember(state.tagDefinitions.toList()) { state.tagDefinitions.associate { it.id.value to it.name } }
    val grouped = remember(projection.rows, tagNames) {
        projection.rows.groupBy { row ->
            val firstId = row.values[typeField].datasetContentText().split(",").firstOrNull()?.trim().orEmpty()
            tagNames[firstId] ?: "Note"
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        grouped.forEach { (groupLabel, rows) ->
            Column(Modifier.width(248.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$groupLabel · ${rows.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.noteId.value }) { row ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenNote(row.noteId) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.title.ifBlank { untitled }, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                AiAccessPolicyBadge(policy = parseRowAiPolicy(row, aiField), showAllow = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllNotesGalleryContent(
    state: TagloomEditorState,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val untitled = stringResource(Res.string.editor_sidebar_untitled_note)
    val dateTime = currentDateTimeSettings()
    val aiField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.AI_ACCESS_POLICY)
    val typeField = FieldRef(FieldRefKinds.OBJECT_TYPE)
    val updatedField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.UPDATED_AT)
    val tagNames = remember(state.tagDefinitions.toList()) { state.tagDefinitions.associate { it.id.value to it.name } }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                projection.rows.forEach { row ->
                    val rawType = row.values[typeField].datasetContentText()
                    val typeDisplay = rawType.split(", ").mapNotNull { id -> tagNames[id.trim()] }.joinToString(" · ").ifBlank { "Note" }
                    Surface(
                        modifier = Modifier.widthIn(min = 210.dp, max = 260.dp).clickable { onOpenNote(row.noteId) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.title.ifBlank { untitled }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(typeDisplay, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AiAccessPolicyBadge(policy = parseRowAiPolicy(row, aiField), showAllow = true)
                            }
                            Text(
                                dateTime.format(row.values[updatedField].datasetContentText()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseRowAiPolicy(row: DatasetProjectionRow, field: FieldRef): AiAccessPolicy {
    val text = row.values[field].datasetContentText()
    return AiAccessPolicy.entries.firstOrNull { it.name == text } ?: AiAccessPolicy.ALLOW
}
