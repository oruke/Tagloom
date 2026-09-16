package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableBooleanCell
import com.oruke.tagloom.core.model.BaseTableCell
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableScalarCell
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.UnknownBaseTableCell
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.editor.addBaseTableColumn
import com.oruke.tagloom.editor.addBaseTableRow
import com.oruke.tagloom.editor.changeBaseTableColumnType
import com.oruke.tagloom.editor.clearBaseTableColumn
import com.oruke.tagloom.editor.clearBaseTableRow
import com.oruke.tagloom.editor.deleteBaseTableColumn
import com.oruke.tagloom.editor.deleteBaseTableRow
import com.oruke.tagloom.editor.duplicateBaseTableColumn
import com.oruke.tagloom.editor.duplicateBaseTableRow
import com.oruke.tagloom.editor.insertBaseTableColumn
import com.oruke.tagloom.editor.insertBaseTableRow
import com.oruke.tagloom.editor.renameBaseTableColumn
import com.oruke.tagloom.editor.updateBaseTableBooleanCell
import com.oruke.tagloom.editor.updateBaseTableCellText
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_base_table_add_column
import tagloom.app.shared.generated.resources.editor_base_table_add_row
import tagloom.app.shared.generated.resources.editor_base_table_read_only
import tagloom.app.shared.generated.resources.editor_base_table_type_boolean
import tagloom.app.shared.generated.resources.editor_base_table_type_date
import tagloom.app.shared.generated.resources.editor_base_table_type_datetime
import tagloom.app.shared.generated.resources.editor_base_table_type_number
import tagloom.app.shared.generated.resources.editor_base_table_type_text

/**
 * 渲染 Stitch 定稿的无标题正文表格。
 *
 * @param state 编辑器状态，所有变更仍通过当前 Note 快照保存。
 * @param block BaseTable 所属 Block。
 * @param content 当前权威表格内容。
 */
@Composable
internal fun BaseTableBlockEditor(
    state: TagloomEditorState,
    block: Block,
    content: BaseTableBlockContent,
) {
    val activeColumns = content.columns.filterNot(BaseTableColumn::deleted).sortedBy(BaseTableColumn::orderKey)
    val activeRows = content.rows.filterNot(BaseTableRow::deleted).sortedBy(BaseTableRow::orderKey)
    val knownSchema = content.schemaVersion == BaseTableBlockContent.CURRENT_SCHEMA_VERSION
    val tableChromeWidth = ROW_GUTTER_WIDTH +
        activeColumns.sumOf { column -> column.width ?: DEFAULT_COLUMN_WIDTH }.dp + 28.dp
    Column(Modifier.fillMaxWidth()) {
        if (!knownSchema) {
            TableNotice(stringResource(Res.string.editor_base_table_read_only))
        }
        state.structuredEditError?.let { message -> TableNotice(message) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < EMBEDDED_BLOCK_COMPACT_BREAKPOINT
            val horizontalScrollState = rememberScrollState()
            BoundedBlockViewport(
                compact = compact,
                horizontalScrollState = horizontalScrollState,
            ) {
                Column(Modifier.horizontalScroll(horizontalScrollState)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column {
                            Box(Modifier.width(ROW_GUTTER_WIDTH).height(TABLE_ROW_HEIGHT))
                            activeRows.forEachIndexed { index, row ->
                                BaseTableRowMenu(
                                    state = state,
                                    block = block,
                                    row = row,
                                    ordinal = index + 1,
                                    enabled = knownSchema,
                                    canDelete = activeRows.size > 1,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                activeColumns.forEach { column ->
                                    BaseTableColumnHeader(
                                        state = state,
                                        block = block,
                                        column = column,
                                        enabled = knownSchema,
                                        canDelete = activeColumns.size > 1,
                                    )
                                }
                            }
                            activeRows.forEach { row ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    activeColumns.forEach { column ->
                                        BaseTableCellEditor(
                                            state = state,
                                            block = block,
                                            row = row,
                                            column = column,
                                            cell = row.cells[column.columnId],
                                            enabled = knownSchema && column.type in BaseTableColumnTypes.EDITABLE,
                                        )
                                    }
                                }
                            }
                        }
                        TableIconAction(
                            label = "+",
                            description = stringResource(Res.string.editor_base_table_add_column),
                            onClick = { state.addBaseTableColumn(block.id) },
                            enabled = knownSchema,
                        )
                    }
                    Row(modifier = Modifier.width(tableChromeWidth), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(ROW_GUTTER_WIDTH))
                        Text(
                            text = "+ ${stringResource(Res.string.editor_base_table_add_row)}",
                            modifier = Modifier.padding(top = 7.dp, bottom = 4.dp).clickable(enabled = knownSchema) {
                                state.addBaseTableRow(block.id)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.weight(1f))
                        BaseTableResizeMenu(state, block, activeRows.size, activeColumns.size, knownSchema)
                    }
                }
            }
        }
    }
}

/**
 * 渲染列标题、类型和结构菜单。
 *
 * @param state 编辑器状态。
 * @param block 表格 Block。
 * @param column 当前列。
 * @param enabled 当前 schema 是否可写。
 * @param canDelete 是否仍有其他活动列。
 */
@Composable
private fun BaseTableColumnHeader(
    state: TagloomEditorState,
    block: Block,
    column: BaseTableColumn,
    enabled: Boolean,
    canDelete: Boolean,
) {
    val interactionSource = remember(column.columnId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember(column.columnId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width((column.width ?: DEFAULT_COLUMN_WIDTH).dp)
            .height(TABLE_ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .hoverable(interactionSource)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaseTableTextField(
            value = column.name.plainText,
            onValueChange = { value -> state.renameBaseTableColumn(block.id, column.columnId, value) },
            placeholder = baseTableTypeLabel(column.type),
            enabled = enabled && column.type in BaseTableColumnTypes.EDITABLE,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
            searchTarget = NoteSearchTarget(NoteSearchField.COLUMN, block.id, column.columnId),
        )
        Box {
            if (hovered || menuOpen) {
                Box(
                    modifier = Modifier.width(28.dp).height(28.dp)
                        .clickable(enabled = enabled) { menuOpen = true }
                        .semantics { contentDescription = "列菜单" },
                    contentAlignment = Alignment.Center,
                ) {
                    TagloomChevronDown(Modifier.size(12.dp))
                }
            }
            TagloomDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.widthIn(min = 160.dp),
            ) {
                TagloomMenuSectionHeader("列类型")
                BaseTableColumnTypes.EDITABLE.forEach { type ->
                    TagloomMenuItem(
                        text = baseTableTypeLabel(type),
                        selected = column.type == type,
                        leadingIcon = if (column.type == type) {
                            { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            state.changeBaseTableColumnType(block.id, column.columnId, type)
                            menuOpen = false
                        },
                    )
                }
                TagloomMenuDivider()
                TagloomMenuSectionHeader("列操作")
                TagloomMenuItem(
                    text = "在左侧插入",
                    onClick = {
                        state.insertBaseTableColumn(block.id, column.columnId, after = false)
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = "在右侧插入",
                    onClick = {
                        state.insertBaseTableColumn(block.id, column.columnId, after = true)
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = "复制列",
                    onClick = {
                        state.duplicateBaseTableColumn(block.id, column.columnId)
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = "清空内容",
                    destructive = true,
                    onClick = {
                        state.clearBaseTableColumn(block.id, column.columnId)
                        menuOpen = false
                    },
                )
                TagloomMenuDivider()
                TagloomMenuItem(
                    text = "删除列",
                    destructive = true,
                    enabled = canDelete,
                    onClick = {
                        state.deleteBaseTableColumn(block.id, column.columnId)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * 渲染 24dp 行号 gutter 与行结构菜单。
 *
 * @param state 编辑器状态。
 * @param block 表格 Block。
 * @param row 当前行。
 * @param ordinal 展示序号。
 * @param enabled 当前 schema 是否可写。
 * @param canDelete 是否仍有其他活动行。
 */
@Composable
private fun BaseTableRowMenu(
    state: TagloomEditorState,
    block: Block,
    row: BaseTableRow,
    ordinal: Int,
    enabled: Boolean,
    canDelete: Boolean,
) {
    val interactionSource = remember(row.rowId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember(row.rowId) { mutableStateOf(false) }
    Box(
        modifier = Modifier.width(ROW_GUTTER_WIDTH).height(TABLE_ROW_HEIGHT).hoverable(interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hovered || menuOpen) "⋮" else ordinal.toString(),
            modifier = Modifier.clickable(enabled = enabled) { menuOpen = true },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TagloomDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.widthIn(min = 140.dp),
        ) {
            TagloomMenuSectionHeader("行操作")
            TagloomMenuItem(
                text = "在上方插入",
                onClick = {
                    state.insertBaseTableRow(block.id, row.rowId, after = false)
                    menuOpen = false
                },
            )
            TagloomMenuItem(
                text = "在下方插入",
                onClick = {
                    state.insertBaseTableRow(block.id, row.rowId, after = true)
                    menuOpen = false
                },
            )
            TagloomMenuItem(
                text = "复制行",
                onClick = {
                    state.duplicateBaseTableRow(block.id, row.rowId)
                    menuOpen = false
                },
            )
            TagloomMenuItem(
                text = "清空内容",
                destructive = true,
                onClick = {
                    state.clearBaseTableRow(block.id, row.rowId)
                    menuOpen = false
                },
            )
            TagloomMenuDivider()
            TagloomMenuItem(
                text = "删除行",
                destructive = true,
                enabled = canDelete,
                onClick = {
                    state.deleteBaseTableRow(block.id, row.rowId)
                    menuOpen = false
                },
            )
        }
    }
}

/**
 * 按列类型渲染一个 36dp 高的单元格输入。
 *
 * @param state 编辑器状态。
 * @param block 表格 Block。
 * @param row 当前行。
 * @param column 当前列。
 * @param cell 当前稀疏单元格。
 * @param enabled 当前值是否可安全编辑。
 */
@Composable
private fun BaseTableCellEditor(
    state: TagloomEditorState,
    block: Block,
    row: BaseTableRow,
    column: BaseTableColumn,
    cell: BaseTableCell?,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .width((column.width ?: DEFAULT_COLUMN_WIDTH).dp)
            .height(TABLE_ROW_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            column.type == BaseTableColumnTypes.BOOLEAN -> Checkbox(
                checked = (cell as? BaseTableBooleanCell)?.value ?: false,
                onCheckedChange = { value -> state.updateBaseTableBooleanCell(block.id, row.rowId, column.columnId, value) },
                enabled = enabled,
            )
            cell is UnknownBaseTableCell -> Text(
                stringResource(Res.string.editor_base_table_read_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            column.type in setOf(BaseTableColumnTypes.DATE, BaseTableColumnTypes.DATETIME) -> TemporalValueField(
                value = cell.editableText(),
                dateOnly = column.type == BaseTableColumnTypes.DATE,
                editable = enabled,
                onCommit = { state.updateBaseTableCellText(block.id, row.rowId, column.columnId, it) },
            )
            else -> BaseTableTextField(
                value = cell.editableText(),
                onValueChange = { value -> state.updateBaseTableCellText(block.id, row.rowId, column.columnId, value) },
                placeholder = "",
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                searchTarget = NoteSearchTarget(NoteSearchField.CELL, block.id, column.columnId, row.rowId),
            )
        }
    }
}

/**
 * 渲染表格紧凑图标动作。
 *
 * @param label 可见符号。
 * @param description 无障碍说明文本。
 * @param onClick 点击动作。
 * @param enabled 是否可点击。
 */
@Composable
internal fun TableIconAction(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier.width(28.dp).height(28.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/**
 * 渲染结构化表格的安全提示。
 *
 * @param message 不包含正文数据的提示。
 */
@Composable
private fun TableNotice(message: String) {
    Text(
        message,
        modifier = Modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 返回当前 Locale 下的 BaseTable 列类型名称。
 *
 * @param type 协议列类型。
 * @return 已知类型名称；未知值原样显示。
 */
@Composable
private fun baseTableTypeLabel(type: String): String = when (type) {
    BaseTableColumnTypes.TEXT -> stringResource(Res.string.editor_base_table_type_text)
    BaseTableColumnTypes.NUMBER -> stringResource(Res.string.editor_base_table_type_number)
    BaseTableColumnTypes.BOOLEAN -> stringResource(Res.string.editor_base_table_type_boolean)
    BaseTableColumnTypes.DATE -> stringResource(Res.string.editor_base_table_type_date)
    BaseTableColumnTypes.DATETIME -> stringResource(Res.string.editor_base_table_type_datetime)
    else -> type
}

/**
 * 把已知可编辑单元格转换为输入文本。
 *
 * @return 文本或空串；布尔和未知单元格由专用 UI 处理。
 */
private fun BaseTableCell?.editableText(): String = when (this) {
    is BaseTableTextCell -> richText.plainText
    is BaseTableScalarCell -> value
    else -> ""
}

/** 默认正文表格列宽。 */
private const val DEFAULT_COLUMN_WIDTH: Int = 180

/** Stitch 规范中的行号和行菜单 gutter。 */
private val ROW_GUTTER_WIDTH = 24.dp

/** Stitch 规范中的表头与单元格高度。 */
private val TABLE_ROW_HEIGHT = 34.dp
