package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableBooleanCell
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableEditor
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableScalarCell
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextEditor

/**
 * 更新正文表格标题。
 *
 * @param blockId BaseTable Block ID。
 * @param title 新纯文本标题。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.updateBaseTableTitle(
    blockId: BlockId,
    title: String,
): Boolean = editBaseTable(blockId) { content ->
    BaseTableEditor.setTitle(content, RichTextDocumentReducer.fromPlainText(title))
}

/**
 * 在正文表格末尾添加空行。
 *
 * @param blockId BaseTable Block ID。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.addBaseTableRow(blockId: BlockId): Boolean = editBaseTable(blockId) { content ->
    BaseTableEditor.addRow(content, BaseTableRowId(createEditorId("table-row")))
}

/**
 * 将正文表格行标记为内部 tombstone。
 *
 * @param blockId BaseTable Block ID。
 * @param rowId 目标稳定行 ID。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.deleteBaseTableRow(
    blockId: BlockId,
    rowId: BaseTableRowId,
): Boolean = editBaseTable(blockId) { content -> BaseTableEditor.deleteRow(content, rowId) }

/**
 * 在正文表格末尾添加文本列。
 *
 * @param blockId BaseTable Block ID。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.addBaseTableColumn(blockId: BlockId): Boolean = editBaseTable(blockId) { content ->
    val ordinal = content.columns.count { column -> !column.deleted } + 1
    BaseTableEditor.addColumn(
        content = content,
        columnId = BaseTableColumnId(createEditorId("table-column")),
        name = RichTextDocumentReducer.fromPlainText("列 $ordinal"),
    )
}

/**
 * 更新正文表格列标题。
 *
 * @param blockId BaseTable Block ID。
 * @param columnId 目标稳定列 ID。
 * @param name 新纯文本标题。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.renameBaseTableColumn(
    blockId: BlockId,
    columnId: BaseTableColumnId,
    name: String,
): Boolean = editBaseTable(blockId) { content ->
    BaseTableEditor.renameColumn(content, columnId, RichTextDocumentReducer.fromPlainText(name))
}

/**
 * 更改正文表格列类型；存在不兼容数据时拒绝并留下提示。
 *
 * @param blockId BaseTable Block ID。
 * @param columnId 目标稳定列 ID。
 * @param type 新列类型。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.changeBaseTableColumnType(
    blockId: BlockId,
    columnId: BaseTableColumnId,
    type: String,
): Boolean = editBaseTable(blockId) { content -> BaseTableEditor.changeColumnType(content, columnId, type) }

/**
 * 将正文表格列标记为内部 tombstone，并保留其单元格历史。
 *
 * @param blockId BaseTable Block ID。
 * @param columnId 目标稳定列 ID。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.deleteBaseTableColumn(
    blockId: BlockId,
    columnId: BaseTableColumnId,
): Boolean = editBaseTable(blockId) { content -> BaseTableEditor.deleteColumn(content, columnId) }

/**
 * 以当前列类型解释纯文本并更新正文表格单元格。
 *
 * number/date/datetime 必须符合协议规范；空文本会清除稀疏单元格。布尔列由专用切换入口编辑。
 *
 * @param blockId BaseTable Block ID。
 * @param rowId 目标稳定行 ID。
 * @param columnId 目标稳定列 ID。
 * @param value 用户输入文本。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.updateBaseTableCellText(
    blockId: BlockId,
    rowId: BaseTableRowId,
    columnId: BaseTableColumnId,
    value: String,
): Boolean = editBaseTable(blockId) { content ->
    val column = content.columns.single { item -> item.columnId == columnId && !item.deleted }
    val current = content.rows.single { row -> row.rowId == rowId }.cells[columnId]
    val cell = when {
        value.isEmpty() -> null
        column.type == BaseTableColumnTypes.TEXT -> {
            val richText = (current as? BaseTableTextCell)?.richText ?: RichText()
            BaseTableTextCell(RichTextEditor.replacePlainTextPreservingStyles(richText, value))
        }
        column.type == BaseTableColumnTypes.NUMBER ||
            column.type == BaseTableColumnTypes.DATE ||
            column.type == BaseTableColumnTypes.DATETIME -> BaseTableScalarCell(column.type, value)
        else -> throw IllegalArgumentException("当前列不能使用文本输入")
    }
    BaseTableEditor.setCell(content, rowId, columnId, cell)
}

/**
 * 设置正文表格布尔单元格。
 *
 * @param blockId BaseTable Block ID。
 * @param rowId 目标稳定行 ID。
 * @param columnId 目标稳定列 ID。
 * @param value 新布尔值。
 * @return 编辑是否成功。
 */
fun TagloomEditorState.updateBaseTableBooleanCell(
    blockId: BlockId,
    rowId: BaseTableRowId,
    columnId: BaseTableColumnId,
    value: Boolean,
): Boolean = editBaseTable(blockId) { content ->
    BaseTableEditor.setCell(content, rowId, columnId, BaseTableBooleanCell(value))
}

/**
 * 清除结构化编辑提示。
 */
fun TagloomEditorState.clearStructuredEditError() {
    structuredEditError = null
}

/**
 * 在当前 Note 中应用一次完整 BaseTable 内容变换，并统一收敛安全错误提示。
 *
 * @param blockId BaseTable Block ID。
 * @param transform 纯内容变换。
 * @return 目标存在且变换通过校验时为 true。
 */
private fun TagloomEditorState.editBaseTable(
    blockId: BlockId,
    transform: (BaseTableBlockContent) -> BaseTableBlockContent,
): Boolean {
    var edited = false
    var failure: Throwable? = null
    updateBlock(blockId) { block ->
        val content = block.content as? BaseTableBlockContent ?: return@updateBlock block
        val updated = runCatching { transform(content) }
            .onFailure { error -> failure = error }
            .getOrNull()
            ?: return@updateBlock block
        edited = updated != content
        block.copy(content = updated)
    }
    structuredEditError = failure?.let { "无法完成表格编辑：请检查列类型和现有单元格" }
    return edited
}
