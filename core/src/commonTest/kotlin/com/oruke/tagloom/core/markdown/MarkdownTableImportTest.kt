package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 验证 Markdown 表格导入的行内语义、边界、身份及不能无损转换时的原文保留。 */
class MarkdownTableImportTest {
    /** 验证带或不带边缘管道的表格共享解析，列名与文本单元格保留 RichText。 */
    @Test
    fun importsRichTextAndEscapedPipesIntoTextColumns() {
        val result = import(
            """
                | **列名** | 行内代码 | 链接 |
                :-: | ---: | :---
                **粗体** | `a\|b` | [文档](https://example.invalid/docs)
                转义\|管道 |  | 普通文本
            """.trimIndent(),
        )
        val table = assertIs<BaseTableBlockContent>(result.note.blocks.single().content)

        assertEquals(listOf("列名", "行内代码", "链接"), table.columns.map { it.name.plainText })
        assertTrue(table.columns.all { it.type == BaseTableColumnTypes.TEXT })
        assertEquals(InlineMarkTypes.BOLD, table.columns[0].name.runs.single().marks.single().type)
        assertEquals(InlineMarkTypes.BOLD, cell(table, 0, 0).runs.single().marks.single().type)
        assertEquals("a|b", cell(table, 0, 1).plainText)
        assertEquals(InlineMarkTypes.CODE, cell(table, 0, 1).runs.single().marks.single().type)
        assertEquals("https://example.invalid/docs", cell(table, 0, 2).runs.single().marks.single().attributes["href"])
        assertEquals("转义|管道", cell(table, 1, 0).plainText)
        assertEquals("", cell(table, 1, 1).plainText)
        assertTrue(result.warnings.single().contains("对齐"))
        assertTrue(NoteValidator.validate(result.note).isEmpty())
    }

    /** 验证表头和数据格中的扩展加注管道不拆列，定义不会因只扫描普通 Block 而丢失。 */
    @Test
    fun preservesAnnotationsInColumnNamesAndCells() {
        val result = import(
            """
                | {{header-note|表头}} | 说明 |
                | --- | --- |
                | {{cell-note|**术语**}} | 值 |

                [^header-note]: 表头说明
                [^cell-note]: 单元格说明
            """.trimIndent(),
        )
        val table = assertIs<BaseTableBlockContent>(result.note.blocks.single().content)

        assertEquals(2, table.columns.size)
        assertEquals(listOf(AnnotationId("header-note")), table.columns[0].name.runs.single().annotationIds)
        assertEquals(listOf(AnnotationId("cell-note")), cell(table, 0, 0).runs.single().annotationIds)
        assertEquals("表头说明", result.note.annotations.getValue(AnnotationId("header-note")).body)
        assertEquals("单元格说明", result.note.annotations.getValue(AnnotationId("cell-note")).body)
        assertTrue(result.warnings.isEmpty())
        assertTrue(NoteValidator.validate(result.note).isEmpty())
    }

    /** 验证空表头、空单元格、短行和没有数据行的合法表格都能进入稀疏模型。 */
    @Test
    fun preservesEmptyCellsAndPadsShortRows() {
        val result = import("| | B | C |\n| - | - | - |\n| | value |\nplain\n\n| only |\n| --- |")
        val table = assertIs<BaseTableBlockContent>(result.note.blocks[0].content)
        val emptyTable = assertIs<BaseTableBlockContent>(result.note.blocks[1].content)

        assertEquals("", table.columns[0].name.plainText)
        assertEquals(listOf("", "value", ""), table.columns.indices.map { cell(table, 0, it).plainText })
        assertEquals(listOf("plain", "", ""), table.columns.indices.map { cell(table, 1, it).plainText })
        assertFalse(table.rows[0].cells.containsKey(table.columns[0].columnId))
        assertEquals(1, emptyTable.columns.size)
        assertTrue(emptyTable.rows.isEmpty())
        assertTrue(NoteValidator.validate(result.note).isEmpty())
    }

    /** 验证分隔行缺失、不合法或列数不一致时不会凭管道字符生成表格。 */
    @Test
    fun requiresMatchingHeaderAndDelimiterRows() {
        val samples = listOf(
            "A | B\nvalue | value",
            "| A | B |\n| --- |\n| value |",
            "| A | B |\n| --- | invalid |\n| value | value |",
            "heading\n---",
            "    A | B\n    --- | ---\n    value | value",
        )

        samples.forEach { markdown ->
            assertTrue(import(markdown).note.blocks.none { it.content is BaseTableBlockContent })
        }
    }

    /** 验证宽行使整张候选表格保留原始文本，前后段落和全部输入列均不丢失。 */
    @Test
    fun preservesWholeTableVerbatimWhenAnyRowIsTooWide() {
        val markdown = "| A | B |\n| --- | --- |\n| one | two |\n| x | y | extra |"
        val result = import("before\n\n$markdown\n\nafter")

        assertEquals(3, result.note.blocks.size)
        assertEquals("before", assertIs<RichTextBlockContent>(result.note.blocks[0].content).richText.plainText)
        assertEquals(markdown, assertIs<RichTextBlockContent>(result.note.blocks[1].content).richText.plainText)
        assertEquals("after", assertIs<RichTextBlockContent>(result.note.blocks[2].content).richText.plainText)
        assertTrue(result.note.blocks.none { it.content is BaseTableBlockContent })
        assertTrue(result.warnings.single().contains("保留整段"))
    }

    /** 验证段落、表格、标题与引用各自保留边界，不把后续结构块吞为数据行。 */
    @Test
    fun respectsSurroundingParagraphsAndOtherBlocks() {
        val result = import(
            "before\nA | B\n--- | ---\nx | y\n> quote\n\nafter\n\n# Heading | value\n--- | ---",
        )

        assertEquals(
            listOf(BlockTypes.PARAGRAPH, BlockTypes.BASE_TABLE, BlockTypes.QUOTE, BlockTypes.PARAGRAPH,
                BlockTypes.HEADING, BlockTypes.PARAGRAPH),
            result.note.blocks.map { it.type },
        )
        assertEquals(1, assertIs<BaseTableBlockContent>(result.note.blocks[1].content).rows.size)
        assertTrue(NoteValidator.validate(result.note).isEmpty())
    }

    /** 验证反引号和波浪线围栏中的表格示例与脚注定义都作为代码原文保留。 */
    @Test
    fun neverConvertsTablesInsideCodeFences() {
        val body = "| A | B |\n| --- | --- |\n| x | y |\n[^sample]: code example"
        val markdown = "````markdown\n$body\n```\n````\n\n  ~~~md\n$body\n  ~~~"
        val result = import(markdown)

        assertEquals(2, result.note.blocks.size)
        assertEquals("$body\n```", assertIs<CodeBlockContent>(result.note.blocks[0].content).text)
        assertEquals(body, assertIs<CodeBlockContent>(result.note.blocks[1].content).text)
        assertTrue(result.note.annotations.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    /** 验证奇偶反斜杠决定分列，代码中的未转义管道同样不能绕过表格宽度检查。 */
    @Test
    fun usesGfmPipeEscapingInsideInlineCode() {
        val result = import("A | B\n--- | ---\npath\\\\|value")
        val table = assertIs<BaseTableBlockContent>(result.note.blocks.single().content)

        assertEquals("path\\", cell(table, 0, 0).plainText)
        assertEquals("value", cell(table, 0, 1).plainText)
        val unescaped = "| A |\n| --- |\n| `a|b` |"
        assertEquals(unescaped, assertIs<RichTextBlockContent>(import(unescaped).note.blocks.single().content).richText.plainText)
    }

    /** 验证工厂能重放确定性身份，生产默认工厂对重复导入产生互不相交的行列身份。 */
    @Test
    fun assignsOpaqueIdentitiesOnceAndKeepsImportsDisjoint() {
        val markdown = "A | B\n--- | ---\nx | y"
        val first = import(markdown)
        val repeat = import(markdown)
        assertEquals(first.note, repeat.note)

        val defaults = MarkdownImportOptions("示例", importedAt = IMPORTED_AT, blockIdFactory = { BlockId("block-$it") })
        val generated = List(2) { MarkdownCodec.import(markdown, defaults) }
        val identities = generated.flatMap { result ->
            val table = assertIs<BaseTableBlockContent>(result.note.blocks.single().content)
            table.columns.map { it.columnId.value } + table.rows.map { it.rowId.value }
        }
        assertEquals(6, identities.toSet().size)
        assertTrue(identities.none { it in listOf("A", "B", "x", "y", "0", "1") })
    }

    /**
     * 通过正式 Markdown 公共入口导入测试文档，注入不依赖输入内容的确定性身份。
     *
     * @param markdown 脱敏 Markdown 场景。
     * @return 完整导入结果。
     */
    private fun import(markdown: String): MarkdownImportResult {
        val ids = (0..100).map { "opaque-${it.toString(16).padStart(8, '0')}" }.iterator()
        return MarkdownCodec.import(
            markdown,
            MarkdownImportOptions(
                defaultTitle = "示例",
                firstHeadingAsTitle = false,
                importedAt = IMPORTED_AT,
                blockIdFactory = { BlockId("block-$it") },
                tableIdFactory = ids::next,
            ),
        )
    }

    /**
     * 按可见行列读取文本单元格，模型省略的空单元格按空 RichText 解释。
     *
     * @param table 已解析的正文表格。
     * @param row 从零开始的数据行索引。
     * @param column 从零开始的列索引。
     * @return 单元格的完整 RichText。
     */
    private fun cell(table: BaseTableBlockContent, row: Int, column: Int): RichText =
        (table.rows[row].cells[table.columns[column].columnId] as? BaseTableTextCell)?.richText ?: RichText()

    companion object {
        /** 仅用于测试加注的固定 UTC 时间，不包含真实业务数据。 */
        private const val IMPORTED_AT: String = "2026-07-13T00:00:00Z"
    }
}
