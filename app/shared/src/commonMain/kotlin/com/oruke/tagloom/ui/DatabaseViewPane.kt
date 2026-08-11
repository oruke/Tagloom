package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.DatasetProjectionColumn
import com.oruke.tagloom.core.model.DatasetProjectionRow
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.FilterExprKinds
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.ViewPresentationKinds
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_fields
import tagloom.app.shared.generated.resources.database_filter_apply
import tagloom.app.shared.generated.resources.database_filter_placeholder
import tagloom.app.shared.generated.resources.database_group_empty
import tagloom.app.shared.generated.resources.database_include_descendants
import tagloom.app.shared.generated.resources.database_kanban_requires_select
import tagloom.app.shared.generated.resources.database_new_row
import tagloom.app.shared.generated.resources.database_operation_failed
import tagloom.app.shared.generated.resources.database_row_count
import tagloom.app.shared.generated.resources.database_save_name
import tagloom.app.shared.generated.resources.database_unknown_view
import tagloom.app.shared.generated.resources.database_view_gallery
import tagloom.app.shared.generated.resources.database_view_kanban
import tagloom.app.shared.generated.resources.database_view_table

/**
 * 渲染独立 Database/View 对象的配置与 Note 派生投影。
 *
 * @param controller Database 目录和写入控制器。
 * @param databaseId 当前 Database Block 引用的稳定 ID。
 * @param onOpenNote 从投影行打开原始 Note。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun DatabaseViewPane(
    controller: WorkspaceDatabaseController,
    databaseId: DatabaseId,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val database = controller.database(databaseId) ?: return
    val ownViews = controller.viewsFor(databaseId)
    var selectedViewId by remember(database.id) {
        mutableStateOf(database.defaultViewId)
    }
    val view = ownViews.firstOrNull { item -> item.id == selectedViewId } ?: ownViews.firstOrNull() ?: return
    val projection = controller.projection(databaseId, view.id) ?: return
    val scope = rememberCoroutineScope()
    var nameDraft by remember(database.id, database.name) { mutableStateOf(database.name) }
    var filterDraft by remember(view.id, view.query.filter) {
        mutableStateOf(view.query.filter.text.orEmpty().takeIf { view.query.filter.kind == FilterExprKinds.FULL_TEXT }.orEmpty())
    }
    var fieldMenuOpen by remember(view.id) { mutableStateOf(false) }
    val sourceName = controller.tagDefinitions().firstOrNull { tag -> tag.id == database.sourceTypeId }?.name
        ?: database.sourceTypeId.value

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▦", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { value -> nameDraft = value },
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            controller.updateDatabase(database.id) { current -> current.copy(name = nameDraft.trim()) }
                        }
                    },
                    enabled = nameDraft.isNotBlank() && nameDraft != database.name && !controller.busy,
                ) { Text(stringResource(Res.string.database_save_name)) }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DatabaseSourceSelector(
                    controller = controller,
                    database = database,
                    sourceName = sourceName,
                    enabled = !controller.busy,
                )
                Text(
                    stringResource(Res.string.database_row_count, projection.rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(Res.string.database_include_descendants), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = database.includeDescendants,
                    onCheckedChange = { checked ->
                        scope.launch {
                            controller.updateDatabase(database.id) { current -> current.copy(includeDescendants = checked) }
                        }
                    },
                    enabled = !controller.busy,
                )
            }
            DatabaseViewTabs(
                controller = controller,
                databaseId = database.id,
                selectedViewId = view.id,
                onSelectView = { viewId -> selectedViewId = viewId },
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    ViewPresentationKinds.TABLE to Res.string.database_view_table,
                    ViewPresentationKinds.KANBAN to Res.string.database_view_kanban,
                    ViewPresentationKinds.GALLERY to Res.string.database_view_gallery,
                ).forEach { (kind, label) ->
                    val canUse = kind != ViewPresentationKinds.KANBAN ||
                        controller.propertyDefinitions(database.id).any { property -> property.type == PropertyTypes.SELECT }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                controller.updateView(view.id) { current ->
                                    val group = if (kind == ViewPresentationKinds.KANBAN) {
                                        controller.propertyDefinitions(database.id)
                                            .firstOrNull { property -> property.type == PropertyTypes.SELECT }
                                            ?.id
                                    } else {
                                        current.presentation.groupByPropertyId
                                    }
                                    current.copy(presentation = current.presentation.copy(kind = kind, groupByPropertyId = group))
                                }
                            }
                        },
                        enabled = canUse && !controller.busy,
                    ) {
                        Text(
                            stringResource(label),
                            color = if (view.presentation.kind == kind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = filterDraft,
                        onValueChange = { value -> filterDraft = value },
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (filterDraft.isEmpty()) Text(
                                    stringResource(Res.string.database_filter_placeholder),
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                inner()
                            }
                        },
                    )
                }
                TextButton(onClick = { scope.launch { controller.setFullTextFilter(view.id, filterDraft) } }) {
                    Text(stringResource(Res.string.database_filter_apply))
                }
                Box {
                    TextButton(onClick = { fieldMenuOpen = true }) { Text(stringResource(Res.string.database_fields)) }
                    DropdownMenu(expanded = fieldMenuOpen, onDismissRequest = { fieldMenuOpen = false }) {
                        controller.propertyDefinitions(database.id).forEach { property ->
                            val field = FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id)
                            val visible = view.presentation.fields.any { configured -> configured.field == field && configured.visible }
                            DropdownMenuItem(
                                text = { Text("${if (visible) "✓ " else ""}${property.name}") },
                                onClick = {
                                    scope.launch {
                                        controller.updateView(view.id) { current -> current.toggleField(field) }
                                    }
                                    fieldMenuOpen = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { scope.launch { controller.createRow(database.id) } }, enabled = !controller.busy) {
                    Text(stringResource(Res.string.database_new_row))
                }
            }
            if (controller.operationFailed) {
                Text(
                    stringResource(Res.string.database_operation_failed),
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (view.presentation.kind) {
                ViewPresentationKinds.TABLE -> DatabaseTable(controller, projection, onOpenNote, Modifier.fillMaxWidth())
                ViewPresentationKinds.KANBAN -> DatabaseKanban(controller, projection, onOpenNote, Modifier.fillMaxWidth())
                ViewPresentationKinds.GALLERY -> DatabaseGallery(projection, onOpenNote, Modifier.fillMaxWidth())
                else -> Text(stringResource(Res.string.database_unknown_view))
            }
        }
    }
}

/**
 * 渲染行等于 Note、列等于字段的可写 Table 投影。
 *
 * @param controller Database 控制器。
 * @param projection 当前投影。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
private fun DatabaseTable(
    controller: WorkspaceDatabaseController,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Box(Modifier.width(44.dp).heightIn(min = DATABASE_CELL_HEIGHT))
            projection.columns.forEach { column -> DatabaseHeaderCell(column) }
        }
        projection.rows.forEach { row ->
            Row {
                TextButton(onClick = { onOpenNote(row.noteId) }, modifier = Modifier.width(44.dp)) { Text("↗") }
                projection.columns.forEach { column ->
                    DatabaseCell(
                        controller = controller,
                        databaseId = projection.database.id,
                        row = row,
                        column = column,
                        onCommit = { value -> scope.launch { controller.updateCell(row.noteId, column.field, value) } },
                    )
                }
            }
        }
    }
}

/**
 * 渲染按单选属性分组的有限 Kanban 投影。
 *
 * @param controller Database 控制器。
 * @param projection 当前投影。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
private fun DatabaseKanban(
    controller: WorkspaceDatabaseController,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupId = projection.view.presentation.groupByPropertyId
    val property = controller.propertyDefinitions(projection.database.id).firstOrNull { item -> item.id == groupId }
    if (property == null) {
        Text(stringResource(Res.string.database_kanban_requires_select), modifier = modifier)
        return
    }
    val field = FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        (property.options.map { option -> option.id to option.label } + ("" to stringResource(Res.string.database_group_empty)))
            .forEach { (optionId, label) ->
                val rows = projection.rows.filter { row -> row.values[field].contentText() == optionId }
                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$label  ${rows.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    rows.forEach { row ->
                        Surface(
                            onClick = { onOpenNote(row.noteId) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(row.title, modifier = Modifier.fillMaxWidth().padding(10.dp), maxLines = 2)
                        }
                    }
                }
            }
    }
}

/**
 * 渲染有限字段的 Gallery 卡片投影。
 *
 * @param projection 当前投影。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
private fun DatabaseGallery(
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        projection.rows.forEach { row ->
            Surface(
                onClick = { onOpenNote(row.noteId) },
                modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.values.entries.take(3).forEach { (field, value) ->
                        Text("${field.propertyId?.value ?: field.systemField}: ${value.contentText()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * 渲染一个 Table 列头。
 *
 * @param column 当前投影列。
 */
@Composable
private fun DatabaseHeaderCell(column: DatasetProjectionColumn) {
    Box(
        Modifier.width(DATABASE_COLUMN_WIDTH).heightIn(min = DATABASE_CELL_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(column.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 渲染一个可写或只读 Database 单元格。
 *
 * @param controller Database 控制器。
 * @param databaseId 当前投影所属 Database ID。
 * @param row 当前 Note 行。
 * @param column 当前字段列。
 * @param onCommit 写回 Note 的值。
 */
@Composable
private fun DatabaseCell(
    controller: WorkspaceDatabaseController,
    databaseId: DatabaseId,
    row: DatasetProjectionRow,
    column: DatasetProjectionColumn,
    onCommit: (JsonElement?) -> Unit,
) {
    val property = column.field.propertyId?.let { id ->
        controller.propertyDefinitions(databaseId).firstOrNull { item -> item.id == id }
    }
    val value = row.values[column.field]
    Box(
        Modifier.width(DATABASE_COLUMN_WIDTH).heightIn(min = DATABASE_CELL_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            !column.writable || property?.type in setOf(PropertyTypes.FORMULA, PropertyTypes.RELATION, PropertyTypes.FILE) ->
                Text(value.contentText(), style = MaterialTheme.typography.bodySmall)
            property?.type == PropertyTypes.BOOLEAN -> Checkbox(
                checked = (value as? JsonPrimitive)?.booleanOrNull ?: false,
                onCheckedChange = { checked -> onCommit(JsonPrimitive(checked)) },
            )
            property?.type == PropertyTypes.SELECT -> DatabaseSelectCell(property, value, onCommit)
            property?.type == PropertyTypes.MULTI_SELECT -> DatabaseMultiSelectCell(property, value, onCommit)
            else -> DatabaseTextCell(value.contentText(), property?.type, onCommit)
        }
    }
}

/**
 * 切换 View 中一个字段的可见性，缺失字段会追加到末尾。
 *
 * @param field 目标稳定字段。
 * @return 更新后的 View。
 */
private fun com.oruke.tagloom.core.model.ViewDefinition.toggleField(field: FieldRef): com.oruke.tagloom.core.model.ViewDefinition {
    val existing = presentation.fields.firstOrNull { item -> item.field == field }
    val fields = if (existing == null) {
        presentation.fields + com.oruke.tagloom.core.model.ViewField(
            field,
            "a${presentation.fields.size.toString().padStart(6, '0')}",
        )
    } else {
        presentation.fields.map { item -> if (item.field == field) item.copy(visible = !item.visible) else item }
    }
    return copy(presentation = presentation.copy(fields = fields))
}

/**
 * 返回投影展示使用的无 Locale JSON 文本。
 *
 * @return 空值、标量或数组的稳定展示文本。
 */
private fun JsonElement?.contentText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString(", ") { item -> item.contentText() }
    else -> toString()
}

/** Table 默认列宽。 */
private val DATABASE_COLUMN_WIDTH = 190.dp

/** Table 单元格最小高度。 */
private val DATABASE_CELL_HEIGHT = 42.dp
