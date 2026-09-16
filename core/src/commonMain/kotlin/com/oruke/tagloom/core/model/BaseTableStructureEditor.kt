package com.oruke.tagloom.core.model

/**
 * 负责 BaseTable 的行列结构编辑与确定性重排。
 *
 * 行列删除仍生成 tombstone；插入、复制、清空和批量调尺寸都返回完整规范化内容，调用方不得绕过
 * Note 快照事务直接持久化局部结果。
 */
object BaseTableStructureEditor {
    /**
     * 按指定行列数创建一个可编辑正文表格。
     *
     * @param rowCount 初始活动行数。
     * @param columnCount 初始活动列数。
     * @param rowIdFactory 稳定行 ID 工厂。
     * @param columnIdFactory 稳定列 ID 工厂。
     * @param columnName 按从 1 开始的序号生成新列名，既有列名原样保留。
     * @return 使用当前 schema 的规范表格。
     */
    fun create(
        rowCount: Int,
        columnCount: Int,
        rowIdFactory: () -> BaseTableRowId,
        columnIdFactory: () -> BaseTableColumnId,
        columnName: (Int) -> String = Int::toString,
    ): BaseTableBlockContent {
        require(rowCount in SUPPORTED_SIZE) { "BaseTable 行数超出支持范围" }
        require(columnCount in SUPPORTED_SIZE) { "BaseTable 列数超出支持范围" }
        val columns = List(columnCount) { index ->
            BaseTableColumn(
                columnId = columnIdFactory(),
                orderKey = orderKey(index),
                name = richText(columnName(index + 1)),
                type = BaseTableColumnTypes.TEXT,
                width = if (index == 1) 260 else 180,
            )
        }
        val rows = List(rowCount) { index -> BaseTableRow(rowIdFactory(), orderKey(index)) }
        return verified(BaseTableBlockContent(columns = columns, rows = rows))
    }

    /**
     * 在指定行之前或之后插入空行。
     *
     * @param content 当前表格。
     * @param anchorRowId 插入锚点行。
     * @param rowId 新稳定行 ID。
     * @param after 是否插入到锚点之后。
     * @return 已重排行顺序的规范表格。
     */
    fun insertRow(
        content: BaseTableBlockContent,
        anchorRowId: BaseTableRowId,
        rowId: BaseTableRowId,
        after: Boolean,
    ): BaseTableBlockContent {
        require(content.rows.none { row -> row.rowId == rowId }) { "BaseTable RowId 已存在" }
        val active = activeRows(content).toMutableList()
        val anchorIndex = active.indexOfFirst { row -> row.rowId == anchorRowId }
        require(anchorIndex >= 0) { "插入锚点行不存在" }
        active.add(anchorIndex + if (after) 1 else 0, BaseTableRow(rowId, orderKey(active.size)))
        return verified(content.copy(rows = mergeRows(content, active)))
    }

    /**
     * 复制指定行及其当前单元格并插入到原行之后。
     *
     * @param content 当前表格。
     * @param sourceRowId 来源行。
     * @param rowId 新稳定行 ID。
     * @return 包含复制行的规范表格。
     */
    fun duplicateRow(
        content: BaseTableBlockContent,
        sourceRowId: BaseTableRowId,
        rowId: BaseTableRowId,
    ): BaseTableBlockContent {
        val source = activeRows(content).singleOrNull { row -> row.rowId == sourceRowId }
            ?: throw IllegalArgumentException("待复制 BaseTable 行不存在")
        val inserted = insertRow(content, sourceRowId, rowId, after = true)
        return verified(
            inserted.copy(
                rows = inserted.rows.map { row ->
                    if (row.rowId == rowId) row.copy(cells = source.cells) else row
                },
            ),
        )
    }

    /**
     * 清除活动行的全部单元格，但保留行身份与位置。
     *
     * @param content 当前表格。
     * @param rowId 目标活动行。
     * @return 清空后的规范表格。
     */
    fun clearRow(
        content: BaseTableBlockContent,
        rowId: BaseTableRowId,
    ): BaseTableBlockContent {
        require(activeRows(content).any { row -> row.rowId == rowId }) { "待清空 BaseTable 行不存在" }
        return verified(
            content.copy(
                rows = content.rows.map { row ->
                    if (row.rowId == rowId && !row.deleted) row.copy(cells = emptyMap()) else row
                },
            ),
        )
    }

    /**
     * 在指定列之前或之后插入文本列。
     *
     * @param content 当前表格。
     * @param anchorColumnId 插入锚点列。
     * @param columnId 新稳定列 ID。
     * @param name 新列标题。
     * @param after 是否插入到锚点之后。
     * @return 已重排列顺序的规范表格。
     */
    fun insertColumn(
        content: BaseTableBlockContent,
        anchorColumnId: BaseTableColumnId,
        columnId: BaseTableColumnId,
        name: RichText,
        after: Boolean,
    ): BaseTableBlockContent {
        require(content.columns.none { column -> column.columnId == columnId }) { "BaseTable ColumnId 已存在" }
        val active = activeColumns(content).toMutableList()
        val anchorIndex = active.indexOfFirst { column -> column.columnId == anchorColumnId }
        require(anchorIndex >= 0) { "插入锚点列不存在" }
        active.add(
            anchorIndex + if (after) 1 else 0,
            BaseTableColumn(columnId, orderKey(active.size), name, BaseTableColumnTypes.TEXT, width = 180),
        )
        return verified(content.copy(columns = mergeColumns(content, active)))
    }

    /**
     * 复制指定列定义和全部活动行中的对应单元格。
     *
     * @param content 当前表格。
     * @param sourceColumnId 来源列。
     * @param columnId 新稳定列 ID。
     * @param name 新列标题。
     * @return 包含复制列的规范表格。
     */
    fun duplicateColumn(
        content: BaseTableBlockContent,
        sourceColumnId: BaseTableColumnId,
        columnId: BaseTableColumnId,
        name: RichText,
    ): BaseTableBlockContent {
        val source = activeColumns(content).singleOrNull { column -> column.columnId == sourceColumnId }
            ?: throw IllegalArgumentException("待复制 BaseTable 列不存在")
        val active = activeColumns(content).toMutableList()
        val sourceIndex = active.indexOf(source)
        active.add(sourceIndex + 1, source.copy(columnId = columnId, name = name, orderKey = orderKey(active.size)))
        return verified(
            content.copy(
                columns = mergeColumns(content, active),
                rows = content.rows.map { row ->
                    val sourceCell = row.cells[sourceColumnId]
                    if (row.deleted || sourceCell == null) row else row.copy(cells = row.cells + (columnId to sourceCell))
                },
            ),
        )
    }

    /**
     * 清除活动列在全部活动行中的值，但保留列定义。
     *
     * @param content 当前表格。
     * @param columnId 目标活动列。
     * @return 清空后的规范表格。
     */
    fun clearColumn(
        content: BaseTableBlockContent,
        columnId: BaseTableColumnId,
    ): BaseTableBlockContent {
        require(activeColumns(content).any { column -> column.columnId == columnId }) { "待清空 BaseTable 列不存在" }
        return verified(
            content.copy(
                rows = content.rows.map { row ->
                    if (row.deleted) row else row.copy(cells = row.cells - columnId)
                },
            ),
        )
    }

    /**
     * 一次性调整活动行列数量，缩小时把末尾行列标记为 tombstone。
     *
     * @param content 当前表格。
     * @param rowCount 目标活动行数。
     * @param columnCount 目标活动列数。
     * @param rowIdFactory 新增行 ID 工厂。
     * @param columnIdFactory 新增列 ID 工厂。
     * @param columnName 按从 1 开始的序号生成新列名，既有列名原样保留。
     * @return 调整后的规范表格。
     */
    fun resize(
        content: BaseTableBlockContent,
        rowCount: Int,
        columnCount: Int,
        rowIdFactory: () -> BaseTableRowId,
        columnIdFactory: () -> BaseTableColumnId,
        columnName: (Int) -> String = Int::toString,
    ): BaseTableBlockContent {
        require(rowCount in SUPPORTED_SIZE) { "BaseTable 行数超出支持范围" }
        require(columnCount in SUPPORTED_SIZE) { "BaseTable 列数超出支持范围" }
        val currentRows = activeRows(content)
        val keptRows = currentRows.take(rowCount)
        val newRows = keptRows + List((rowCount - keptRows.size).coerceAtLeast(0)) { index ->
            BaseTableRow(rowIdFactory(), orderKey(keptRows.size + index))
        }
        val removedRowIds = currentRows.drop(rowCount).map(BaseTableRow::rowId).toSet()

        val currentColumns = activeColumns(content)
        val keptColumns = currentColumns.take(columnCount)
        val newColumns = keptColumns + List((columnCount - keptColumns.size).coerceAtLeast(0)) { index ->
            val ordinal = keptColumns.size + index
            BaseTableColumn(
                columnIdFactory(),
                orderKey(ordinal),
                richText(columnName(ordinal + 1)),
                BaseTableColumnTypes.TEXT,
                width = 180,
            )
        }
        val removedColumnIds = currentColumns.drop(columnCount).map(BaseTableColumn::columnId).toSet()
        val rows = content.rows.map { row ->
            if (row.rowId in removedRowIds) row.copy(deleted = true) else row
        }
        val columns = content.columns.map { column ->
            if (column.columnId in removedColumnIds) column.copy(deleted = true) else column
        }
        return verified(
            content.copy(
                rows = mergeRows(content.copy(rows = rows), newRows),
                columns = mergeColumns(content.copy(columns = columns), newColumns),
            ),
        )
    }

    /**
     * 判断缩小表格是否会隐藏仍有值的行或列。
     *
     * @param content 当前表格。
     * @param rowCount 目标活动行数。
     * @param columnCount 目标活动列数。
     * @return 目标范围外存在非空单元格时为 true。
     */
    fun wouldDiscardVisibleData(
        content: BaseTableBlockContent,
        rowCount: Int,
        columnCount: Int,
    ): Boolean {
        val rows = activeRows(content)
        val columns = activeColumns(content)
        val removedRowIds = rows.drop(rowCount).map(BaseTableRow::rowId).toSet()
        val removedColumnIds = columns.drop(columnCount).map(BaseTableColumn::columnId).toSet()
        return rows.any { row ->
            !row.deleted && row.cells.isNotEmpty() && (
                row.rowId in removedRowIds || row.cells.keys.any { columnId -> columnId in removedColumnIds }
                )
        }
    }

    /**
     * 合并 tombstone 与重排后的活动行。
     *
     * @param content 当前表格。
     * @param active 新活动行顺序。
     * @return 可交给规范化器的完整行集合。
     */
    private fun mergeRows(
        content: BaseTableBlockContent,
        active: List<BaseTableRow>,
    ): List<BaseTableRow> = content.rows.filter(BaseTableRow::deleted) + active.mapIndexed { index, row ->
        row.copy(orderKey = orderKey(index), deleted = false)
    }

    /**
     * 合并 tombstone 与重排后的活动列。
     *
     * @param content 当前表格。
     * @param active 新活动列顺序。
     * @return 可交给规范化器的完整列集合。
     */
    private fun mergeColumns(
        content: BaseTableBlockContent,
        active: List<BaseTableColumn>,
    ): List<BaseTableColumn> = content.columns.filter(BaseTableColumn::deleted) + active.mapIndexed { index, column ->
        column.copy(orderKey = orderKey(index), deleted = false)
    }

    /**
     * 返回稳定排序的活动行。
     *
     * @param content 当前表格。
     * @return 活动行列表。
     */
    private fun activeRows(content: BaseTableBlockContent): List<BaseTableRow> = content.rows
        .filterNot(BaseTableRow::deleted)
        .sortedWith(compareBy(BaseTableRow::orderKey, { row -> row.rowId.value }))

    /**
     * 返回稳定排序的活动列。
     *
     * @param content 当前表格。
     * @return 活动列列表。
     */
    private fun activeColumns(content: BaseTableBlockContent): List<BaseTableColumn> = content.columns
        .filterNot(BaseTableColumn::deleted)
        .sortedWith(compareBy(BaseTableColumn::orderKey, { column -> column.columnId.value }))

    /**
     * 规范化并验证结构编辑结果。
     *
     * @param content 待确认内容。
     * @return 规范表格。
     */
    private fun verified(content: BaseTableBlockContent): BaseTableBlockContent {
        val normalized = BaseTableNormalizer.normalize(content)
        val issues = BaseTableValidator.validate(normalized)
        require(issues.isEmpty()) { issues.joinToString(separator = ";") { issue -> issue.code } }
        return normalized
    }

    /**
     * 构造规范 RichText 标题。
     *
     * @param value 标题文本。
     * @return 单段 RichText。
     */
    private fun richText(value: String): RichText = RichText(listOf(InlineRun(value)))

    /**
     * 生成固定宽度局部排序键。
     *
     * @param index 从零开始的序号。
     * @return 可词典序排序的键。
     */
    private fun orderKey(index: Int): String = "a${index.toString().padStart(6, '0')}"

    /** 单次交互支持的最小和最大活动行列数。 */
    private val SUPPORTED_SIZE: IntRange = 1..20
}
