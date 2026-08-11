package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证 BaseTable 的前向兼容、规范化和无损编辑边界。
 */
class BaseTableProtocolTest {
    /**
     * 验证未知列与未知单元格经过旧客户端解码和编码后不丢失原始字段。
     */
    @Test
    fun unknownCellRoundTripsWithoutDataLoss() {
        val columnId = BaseTableColumnId("column-rating")
        val raw = buildJsonObject {
            put("_cellType", "rating")
            put("stars", 5)
            put("source", "future-client")
        }
        val content = BaseTableBlockContent(
            columns = listOf(
                BaseTableColumn(columnId, "a000000", richText("评分"), "rating"),
            ),
            rows = listOf(
                BaseTableRow(
                    rowId = BaseTableRowId("row-one"),
                    orderKey = "a000000",
                    cells = mapOf(columnId to UnknownBaseTableCell(raw)),
                ),
            ),
        )

        val encoded = CanonicalJson.encodeToString(BaseTableBlockContent.serializer(), content)
        val decoded = CanonicalJson.decodeFromString(BaseTableBlockContent.serializer(), encoded)
        val unknown = assertIs<UnknownBaseTableCell>(decoded.rows.single().cells.getValue(columnId))

        assertEquals(raw, unknown.raw)
        assertEquals(encoded, CanonicalJson.encodeToString(BaseTableBlockContent.serializer(), decoded))
        assertTrue(BaseTableValidator.validate(decoded).isEmpty())
    }

    /**
     * 验证类型转换只接受无损值，并在失败时保留调用前内容。
     */
    @Test
    fun columnTypeConversionRejectsLossyValues() {
        val table = tableWithTextCell("12.50")
        val columnId = table.columns.first().columnId
        val rowId = table.rows.first().rowId

        val converted = BaseTableEditor.changeColumnType(table, columnId, BaseTableColumnTypes.NUMBER)
        val scalar = assertIs<BaseTableScalarCell>(converted.rows.first().cells.getValue(columnId))

        assertEquals("12.50", scalar.value)
        val invalid = BaseTableEditor.setCell(
            BaseTableEditor.changeColumnType(converted, columnId, BaseTableColumnTypes.TEXT),
            rowId,
            columnId,
            BaseTableTextCell(richText("十二")),
        )
        assertFailsWith<IllegalArgumentException> {
            BaseTableEditor.changeColumnType(invalid, columnId, BaseTableColumnTypes.NUMBER)
        }
        assertEquals("十二", assertIs<BaseTableTextCell>(invalid.rows.first().cells.getValue(columnId)).richText.plainText)
    }

    /**
     * 验证删除行列只生成 tombstone，并保留单元格用于历史恢复。
     */
    @Test
    fun deletionPreservesRowsColumnsAndCellsAsTombstones() {
        val source = tableWithTextCell("保留值")
        val firstColumnId = source.columns.first().columnId
        val secondColumnId = source.columns.last().columnId
        val rowId = source.rows.first().rowId

        val columnDeleted = BaseTableEditor.deleteColumn(source, secondColumnId)
        val rowDeleted = BaseTableEditor.deleteRow(columnDeleted, rowId)

        assertTrue(rowDeleted.columns.single { column -> column.columnId == secondColumnId }.deleted)
        assertTrue(rowDeleted.rows.single { row -> row.rowId == rowId }.deleted)
        assertEquals(
            "保留值",
            assertIs<BaseTableTextCell>(rowDeleted.rows.first().cells.getValue(firstColumnId)).richText.plainText,
        )
    }

    /**
     * 创建带一个非空文本单元格的默认表格。
     *
     * @param value 单元格文本。
     * @return 可用于编辑操作的有效表格。
     */
    private fun tableWithTextCell(value: String): BaseTableBlockContent {
        var rowIndex = 0
        var columnIndex = 0
        val table = BaseTableEditor.createDefault(
            rowIdFactory = { BaseTableRowId("row-${rowIndex++}") },
            columnIdFactory = { BaseTableColumnId("column-${columnIndex++}") },
        )
        val rowId = table.rows.first().rowId
        val columnId = table.columns.first().columnId
        return BaseTableEditor.setCell(table, rowId, columnId, BaseTableTextCell(richText(value)))
    }

    /**
     * 创建测试用单段 RichText。
     *
     * @param value 文本内容。
     * @return 规范 RichText。
     */
    private fun richText(value: String): RichText = RichText(listOf(InlineRun(value)))
}
