package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableScalarCell
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 BaseTable 按稳定行列身份执行单元格级三方合并。 */
class BaseTableThreeWayMergeTest {
    /** 测试名称列。 */
    private val nameColumnId = BaseTableColumnId("column-name")

    /** 测试数值列。 */
    private val scoreColumnId = BaseTableColumnId("column-score")

    /** 测试稳定行。 */
    private val rowId = BaseTableRowId("row-main")

    /** 验证不同单元格的并发编辑会组合为同一个权威表格。 */
    @Test
    fun mergesEditsFromDifferentCells() {
        val base = table(name = "Alpha", score = "1")
        val local = table(name = "Local", score = "1")
        val remote = table(name = "Alpha", score = "2")

        val result = BaseTableThreeWayMerge.merge(base, local, remote)
        val row = (result.value as BaseTableBlockContent).rows.single()

        assertFalse(result.conflicted)
        assertEquals("Local", (row.cells.getValue(nameColumnId) as BaseTableTextCell).richText.plainText)
        assertEquals("2", (row.cells.getValue(scoreColumnId) as BaseTableScalarCell).value)
    }

    /** 验证同一文本单元格的非重叠 RichText 编辑仍可自动组合。 */
    @Test
    fun mergesNonOverlappingTextWithinOneCell() {
        val base = table(name = "hello world", score = "1")
        val local = table(name = "hello brave world", score = "1")
        val remote = table(name = "hello world!", score = "1")

        val result = BaseTableThreeWayMerge.merge(base, local, remote)
        val cell = (result.value as BaseTableBlockContent).rows.single().cells.getValue(nameColumnId)

        assertFalse(result.conflicted)
        assertEquals("hello brave world!", (cell as BaseTableTextCell).richText.plainText)
    }

    /** 验证同一标量单元格的重叠修改会要求外层保留完整冲突证据。 */
    @Test
    fun marksOverlappingScalarCellAsConflict() {
        val base = table(name = "Alpha", score = "1")
        val local = table(name = "Alpha", score = "2")
        val remote = table(name = "Alpha", score = "3")

        val result = BaseTableThreeWayMerge.merge(base, local, remote)

        assertTrue(result.conflicted)
        val cell = (result.value as BaseTableBlockContent).rows.single().cells.getValue(scoreColumnId)
        assertEquals("2", (cell as BaseTableScalarCell).value)
    }

    /** 验证行 tombstone 与另一侧单元格更新可并存，供历史恢复读取更新后的内容。 */
    @Test
    fun retainsUpdatedCellsInsideConcurrentRowTombstone() {
        val base = table(name = "Alpha", score = "1")
        val local = base.copy(rows = listOf(base.rows.single().copy(deleted = true)))
        val remote = table(name = "Remote", score = "1")

        val result = BaseTableThreeWayMerge.merge(base, local, remote)
        val row = (result.value as BaseTableBlockContent).rows.single()

        assertFalse(result.conflicted)
        assertTrue(row.deleted)
        assertEquals("Remote", (row.cells.getValue(nameColumnId) as BaseTableTextCell).richText.plainText)
    }

    /** 验证列 tombstone 与另一侧改名可并存，避免恢复时丢失列元数据。 */
    @Test
    fun retainsRenamedMetadataInsideConcurrentColumnTombstone() {
        val base = table(name = "Alpha", score = "1")
        val local = base.copy(
            columns = base.columns.map { column ->
                if (column.columnId == nameColumnId) column.copy(deleted = true) else column
            },
        )
        val remote = base.copy(
            columns = base.columns.map { column ->
                if (column.columnId == nameColumnId) column.copy(name = text("Task")) else column
            },
        )

        val result = BaseTableThreeWayMerge.merge(base, local, remote)
        val column = (result.value as BaseTableBlockContent).columns.first { it.columnId == nameColumnId }

        assertFalse(result.conflicted)
        assertTrue(column.deleted)
        assertEquals("Task", column.name.plainText)
    }

    /**
     * 构造包含文本与数值列的一行 BaseTable。
     *
     * @param name 名称单元格文本。
     * @param score 分数单元格规范数值文本。
     * @return 合法 BaseTable 内容。
     */
    private fun table(
        name: String,
        score: String,
    ): BaseTableBlockContent = BaseTableBlockContent(
        columns = listOf(
            BaseTableColumn(nameColumnId, "a", text("Name"), BaseTableColumnTypes.TEXT),
            BaseTableColumn(scoreColumnId, "b", text("Score"), BaseTableColumnTypes.NUMBER),
        ),
        rows = listOf(
            BaseTableRow(
                rowId = rowId,
                orderKey = "a",
                cells = mapOf(
                    nameColumnId to BaseTableTextCell(text(name)),
                    scoreColumnId to BaseTableScalarCell(BaseTableColumnTypes.NUMBER, score),
                ),
            ),
        ),
    )

    /**
     * 构造无样式测试 RichText。
     *
     * @param value 纯文本。
     * @return 单 Run RichText。
     */
    private fun text(value: String): RichText = RichText(listOf(InlineRun(value)))
}
