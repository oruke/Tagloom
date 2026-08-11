package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
import com.oruke.tagloom.editor.addBaseTableColumn
import com.oruke.tagloom.editor.addBaseTableRow
import com.oruke.tagloom.editor.changeBaseTableColumnType
import com.oruke.tagloom.editor.deleteBaseTableColumn
import com.oruke.tagloom.editor.deleteBaseTableRow
import com.oruke.tagloom.editor.renameBaseTableColumn
import com.oruke.tagloom.editor.updateBaseTableBooleanCell
import com.oruke.tagloom.editor.updateBaseTableCellText
import com.oruke.tagloom.editor.updateBaseTableTitle
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_base_table_add_column
import tagloom.app.shared.generated.resources.editor_base_table_add_row
import tagloom.app.shared.generated.resources.editor_base_table_delete_column
import tagloom.app.shared.generated.resources.editor_base_table_kind
import tagloom.app.shared.generated.resources.editor_base_table_read_only
import tagloom.app.shared.generated.resources.editor_base_table_scope
import tagloom.app.shared.generated.resources.editor_base_table_title_placeholder
import tagloom.app.shared.generated.resources.editor_base_table_type_boolean
import tagloom.app.shared.generated.resources.editor_base_table_type_date
import tagloom.app.shared.generated.resources.editor_base_table_type_datetime
import tagloom.app.shared.generated.resources.editor_base_table_type_number
import tagloom.app.shared.generated.resources.editor_base_table_type_text
import tagloom.app.shared.generated.resources.editor_block_command_base_table_label

/**
 * 渲染保存在单个 Note Block 内的可编辑 BaseTable。
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
    val activeColumns = content.columns.filterNot(BaseTableColumn::deleted)
    val activeRows = content.rows.filterNot(BaseTableRow::deleted)
    val knownSchema = content.schemaVersion == BaseTableBlockContent.CURRENT_SCHEMA_VERSION
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.editor_base_table_kind),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            InlineTableTextField(
                value = content.title.plainText,
                onValueChange = { value -> state.updateBaseTableTitle(block.id, value) },
                placeholder = stringResource(Res.string.editor_base_table_title_placeholder),
                enabled = knownSchema,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(Res.string.editor_base_table_scope),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!knownSchema) {
            TableNotice(stringResource(Res.string.editor_base_table_read_only))
        }
        val editError = state.structuredEditError
        if (editError != null) {
            TableNotice(editError)
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Box(Modifier.width(ROW_ACTION_WIDTH).padding(top = 9.dp), contentAlignment = Alignment.Center) {
                    Text("#", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                activeColumns.forEach { column ->
                    BaseTableColumnHeader(state, block, column, knownSchema)
                }
            }
            activeRows.forEachIndexed { index, row ->
                Row {
                    Box(
                        Modifier.width(ROW_ACTION_WIDTH).heightIn(min = CELL_MIN_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(
                            onClick = { state.deleteBaseTableRow(block.id, row.rowId) },
                            enabled = knownSchema,
                        ) {
                            Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
        if (knownSchema) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { state.addBaseTableRow(block.id) }) {
                    Text("+ ${stringResource(Res.string.editor_base_table_add_row)}")
                }
                TextButton(onClick = { state.addBaseTableColumn(block.id) }) {
                    Text("+ ${stringResource(Res.string.editor_base_table_add_column)}")
                }
            }
        }
    }
}

/**
 * 渲染可重命名、换类型和删除的列头。
 *
 * @param state 编辑器状态。
 * @param block 表格 Block。
 * @param column 当前列。
 * @param enabled 当前 schema 是否可写。
 */
@Composable
private fun BaseTableColumnHeader(
    state: TagloomEditorState,
    block: Block,
    column: BaseTableColumn,
    enabled: Boolean,
) {
    var menuOpen by remember(column.columnId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width((column.width ?: DEFAULT_COLUMN_WIDTH).dp)
            .heightIn(min = CELL_MIN_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineTableTextField(
            value = column.name.plainText,
            onValueChange = { value -> state.renameBaseTableColumn(block.id, column.columnId, value) },
            placeholder = stringResource(Res.string.editor_block_command_base_table_label),
            enabled = enabled && column.type in BaseTableColumnTypes.EDITABLE,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
        Box {
            TextButton(onClick = { menuOpen = true }, enabled = enabled) {
                Text("···", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                BaseTableColumnTypes.EDITABLE.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(baseTableTypeLabel(type)) },
                        onClick = {
                            state.changeBaseTableColumnType(block.id, column.columnId, type)
                            menuOpen = false
                        },
                        leadingIcon = { if (column.type == type) Text("✓") },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.editor_base_table_delete_column)) },
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
 * 按列类型渲染一个单元格输入。
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
            .heightIn(min = CELL_MIN_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
            else -> InlineTableTextField(
                value = cell.editableText(),
                onValueChange = { value ->
                    state.updateBaseTableCellText(block.id, row.rowId, column.columnId, value)
                },
                placeholder = baseTableTypeLabel(column.type),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 渲染无外框的紧凑表格文本输入。
 *
 * @param value 当前文本。
 * @param onValueChange 文本变化回调。
 * @param placeholder 空值提示。
 * @param enabled 是否允许输入。
 * @param modifier 外部布局修饰符。
 * @param fontWeight 可选字重。
 */
@Composable
private fun InlineTableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = fontWeight,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
                inner()
            }
        },
    )
}

/**
 * 渲染结构化表格的安全提示。
 *
 * @param message 不包含正文数据的提示。
 */
@Composable
private fun TableNotice(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
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

/** 行号与删除入口的固定宽度。 */
private val ROW_ACTION_WIDTH = 48.dp

/** 表头与单元格最小高度。 */
private val CELL_MIN_HEIGHT = 42.dp
