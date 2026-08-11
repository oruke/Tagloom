package com.oruke.tagloom.core.model

/**
 * 对 BaseTable 内容执行无副作用的行、列和单元格编辑。
 *
 * 调用方仍需把返回的完整 Note 交给带 expectedRevision 的 Note 应用服务确认；本对象不产生独立写入路径。
 */
object BaseTableEditor {
    /**
     * 创建一个可立即编辑的两列两行正文表格。
     *
     * @param rowIdFactory 生成局部稳定 RowId。
     * @param columnIdFactory 生成局部稳定 ColumnId。
     * @return 使用当前 schema 的初始 BaseTable 内容。
     */
    fun createDefault(
        rowIdFactory: () -> BaseTableRowId,
        columnIdFactory: () -> BaseTableColumnId,
    ): BaseTableBlockContent {
        val firstColumn = BaseTableColumn(
            columnId = columnIdFactory(),
            orderKey = orderKey(0),
            name = richText("名称"),
            type = BaseTableColumnTypes.TEXT,
            width = 180,
        )
        val secondColumn = BaseTableColumn(
            columnId = columnIdFactory(),
            orderKey = orderKey(1),
            name = richText("内容"),
            type = BaseTableColumnTypes.TEXT,
            width = 260,
        )
        return verified(
            BaseTableBlockContent(
                columns = listOf(firstColumn, secondColumn),
                rows = List(2) { index ->
                    BaseTableRow(rowIdFactory(), orderKey(index))
                },
            ),
        )
    }

    /**
     * 更新表格标题。
     *
     * @param content 当前表格。
     * @param title 新标题 RichText。
     * @return 规范化表格。
     */
    fun setTitle(
        content: BaseTableBlockContent,
        title: RichText,
    ): BaseTableBlockContent = verified(content.copy(title = title))

    /**
     * 在活动列末尾添加一个局部列。
     *
     * @param content 当前表格。
     * @param columnId 新稳定列 ID。
     * @param name 新列名称。
     * @param type 首版列类型。
     * @return 包含新列的表格。
     */
    fun addColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
        name: RichText,
        type: String = BaseTableColumnTypes.TEXT,
    ): BaseTableBlockContent {
        require(type in BaseTableColumnTypes.EDITABLE) { "只能创建当前客户端支持的 BaseTable 列类型" }
        require(content.columns.none { column -> column.columnId == columnId }) { "BaseTable ColumnId 已存在" }
        val activeCount = content.columns.count { column -> !column.deleted }
        return verified(
            content.copy(
                columns = content.columns + BaseTableColumn(
                    columnId = columnId,
                    orderKey = nextOrderKey(content.columns.map(BaseTableColumn::orderKey), activeCount),
                    name = name,
                    type = type,
                    width = if (type == BaseTableColumnTypes.BOOLEAN) 112 else 180,
                ),
            ),
        )
    }

    /**
     * 更新活动列标题。
     *
     * @param content 当前表格。
     * @param columnId 目标列。
     * @param name 新标题。
     * @return 更新后的表格。
     */
    fun renameColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
        name: RichText,
    ): BaseTableBlockContent = updateColumn(content, columnId) { column -> column.copy(name = name) }

    /**
     * 更新活动列展示宽度。
     *
     * @param content 当前表格。
     * @param columnId 目标列。
     * @param width 受协议范围约束的新宽度。
     * @return 更新后的表格。
     */
    fun resizeColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
        width: Int,
    ): BaseTableBlockContent = updateColumn(content, columnId) { column -> column.copy(width = width) }

    /**
     * 更改列类型，并只在全部非空单元格均可无损转换时确认。
     *
     * 拒绝转换比清空内容更安全；UI 应提示用户先修正不兼容单元格。
     *
     * @param content 当前表格。
     * @param columnId 目标列。
     * @param type 新首版列类型。
     * @return 已完成无损转换的表格。
     */
    fun changeColumnType(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
        type: String,
    ): BaseTableBlockContent {
        require(type in BaseTableColumnTypes.EDITABLE) { "目标 BaseTable 列类型不受支持" }
        val current = content.columns.singleOrNull { column -> column.columnId == columnId && !column.deleted }
            ?: throw IllegalArgumentException("待修改 BaseTable 列不存在")
        if (current.type == type) {
            return content
        }
        val convertedRows = content.rows.map { row ->
            val cell = row.cells[columnId] ?: return@map row
            val converted = convertCell(cell, type)
                ?: throw IllegalArgumentException("当前列存在不能无损转换的单元格")
            row.copy(cells = row.cells + (columnId to converted))
        }
        return updateColumn(content.copy(rows = convertedRows), columnId) { column -> column.copy(type = type) }
    }

    /**
     * 将列标记为内部 tombstone，并保留各行对应单元格用于恢复与冲突解释。
     *
     * @param content 当前表格。
     * @param columnId 目标列。
     * @return 保留历史数据的表格。
     */
    fun deleteColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
    ): BaseTableBlockContent {
        require(content.columns.count { column -> !column.deleted } > 1) { "正文表格至少保留一列" }
        return updateColumn(content, columnId) { column -> column.copy(deleted = true) }
    }

    /**
     * 在活动行末尾添加空行。
     *
     * @param content 当前表格。
     * @param rowId 新稳定行 ID。
     * @return 包含新行的表格。
     */
    fun addRow(
        content: BaseTableBlockContent,
        rowId: BaseTableRowId,
    ): BaseTableBlockContent {
        require(content.rows.none { row -> row.rowId == rowId }) { "BaseTable RowId 已存在" }
        val activeCount = content.rows.count { row -> !row.deleted }
        return verified(
            content.copy(
                rows = content.rows + BaseTableRow(
                    rowId = rowId,
                    orderKey = nextOrderKey(content.rows.map(BaseTableRow::orderKey), activeCount),
                ),
            ),
        )
    }

    /**
     * 将行标记为内部 tombstone，保留单元格以支持恢复。
     *
     * @param content 当前表格。
     * @param rowId 目标行。
     * @return 更新后的表格。
     */
    fun deleteRow(
        content: BaseTableBlockContent,
        rowId: BaseTableRowId,
    ): BaseTableBlockContent = updateRow(content, rowId) { row -> row.copy(deleted = true) }

    /**
     * 设置或清除一个活动行列交点的单元格。
     *
     * @param content 当前表格。
     * @param rowId 目标行。
     * @param columnId 目标列。
     * @param cell 新单元格；为空表示清除稀疏值。
     * @return 类型匹配且规范化的表格。
     */
    fun setCell(
        content: BaseTableBlockContent,
        rowId: BaseTableRowId,
        columnId: BaseTableColumnId,
        cell: BaseTableCell?,
    ): BaseTableBlockContent {
        val column = content.columns.singleOrNull { item -> item.columnId == columnId && !item.deleted }
            ?: throw IllegalArgumentException("待编辑 BaseTable 列不存在")
        require(content.rows.any { row -> row.rowId == rowId && !row.deleted }) { "待编辑 BaseTable 行不存在" }
        if (cell != null) {
            val probe = BaseTableBlockContent(
                columns = listOf(column.copy(orderKey = orderKey(0))),
                rows = listOf(BaseTableRow(rowId, orderKey(0), mapOf(columnId to cell))),
            )
            require(BaseTableValidator.validate(probe).isEmpty()) { "单元格值与列类型不一致" }
        }
        return updateRow(content, rowId) { row ->
            row.copy(cells = if (cell == null) row.cells - columnId else row.cells + (columnId to cell))
        }
    }

    /**
     * 更新一个活动列并验证完整表格。
     *
     * @param content 当前表格。
     * @param columnId 目标列。
     * @param transform 列转换。
     * @return 更新后的表格。
     */
    private fun updateColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
        transform: (BaseTableColumn) -> BaseTableColumn,
    ): BaseTableBlockContent {
        var found = false
        val columns = content.columns.map { column ->
            if (column.columnId == columnId && !column.deleted) {
                found = true
                transform(column)
            } else {
                column
            }
        }
        require(found) { "待编辑 BaseTable 列不存在" }
        return verified(content.copy(columns = columns))
    }

    /**
     * 更新一个活动行并验证完整表格。
     *
     * @param content 当前表格。
     * @param rowId 目标行。
     * @param transform 行转换。
     * @return 更新后的表格。
     */
    private fun updateRow(
        content: BaseTableBlockContent,
        rowId: BaseTableRowId,
        transform: (BaseTableRow) -> BaseTableRow,
    ): BaseTableBlockContent {
        var found = false
        val rows = content.rows.map { row ->
            if (row.rowId == rowId && !row.deleted) {
                found = true
                transform(row)
            } else {
                row
            }
        }
        require(found) { "待编辑 BaseTable 行不存在" }
        return verified(content.copy(rows = rows))
    }

    /**
     * 尝试将已有单元格无损转换为目标列类型。
     *
     * @param cell 原始单元格。
     * @param targetType 目标列类型。
     * @return 可验证的新单元格；无法无损转换时为空。
     */
    private fun convertCell(
        cell: BaseTableCell,
        targetType: String,
    ): BaseTableCell? {
        val text = when (cell) {
            is BaseTableTextCell -> cell.richText.plainText
            is BaseTableScalarCell -> cell.value
            is BaseTableBooleanCell -> cell.value.toString()
            is UnknownBaseTableCell -> return null
        }
        return when (targetType) {
            BaseTableColumnTypes.TEXT -> BaseTableTextCell(richText(text))
            BaseTableColumnTypes.BOOLEAN -> when (text.lowercase()) {
                "true" -> BaseTableBooleanCell(true)
                "false" -> BaseTableBooleanCell(false)
                else -> null
            }
            BaseTableColumnTypes.NUMBER,
            BaseTableColumnTypes.DATE,
            BaseTableColumnTypes.DATETIME,
            -> BaseTableScalarCell(targetType, text).takeIf { candidate ->
                val probe = BaseTableBlockContent(
                    columns = listOf(BaseTableColumn(BaseTableColumnId("probe-column"), orderKey(0), RichText(), targetType)),
                    rows = listOf(
                        BaseTableRow(
                            BaseTableRowId("probe-row"),
                            orderKey(0),
                            mapOf(BaseTableColumnId("probe-column") to candidate),
                        ),
                    ),
                )
                BaseTableValidator.validate(probe).isEmpty()
            }
            else -> null
        }
    }

    /**
     * 规范化并拒绝任何会破坏局部 schema 的编辑结果。
     *
     * @param content 待确认内容。
     * @return 规范化内容。
     */
    private fun verified(content: BaseTableBlockContent): BaseTableBlockContent {
        val normalized = BaseTableNormalizer.normalize(content)
        val issues = BaseTableValidator.validate(normalized)
        require(issues.isEmpty()) { issues.joinToString(separator = ";") { issue -> issue.code } }
        return normalized
    }

    /**
     * 将纯文本构造为可规范化 RichText。
     *
     * @param value 文本。
     * @return 空文本对应空 RichText。
     */
    private fun richText(value: String): RichText = if (value.isEmpty()) RichText() else RichText(listOf(InlineRun(value)))

    /**
     * 生成本地行列初始排序键。
     *
     * @param index 从零开始的序号。
     * @return 固定宽度词典序键。
     */
    private fun orderKey(index: Int): String = "a${index.toString().padStart(6, '0')}"

    /**
     * 生成不会与当前 tombstone 或活动项冲突的新排序键。
     *
     * @param existing 当前全部排序键。
     * @param preferredIndex 首选序号。
     * @return 不重复排序键。
     */
    private fun nextOrderKey(
        existing: Collection<String>,
        preferredIndex: Int,
    ): String {
        var index = preferredIndex
        while (orderKey(index) in existing) {
            index += 1
        }
        return orderKey(index)
    }
}
