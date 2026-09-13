package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证 Markdown 表格从实际粘贴操作进入编辑草稿，保持文本、树结构和独立行列身份。 */
class MarkdownTablePasteTest {
    /** 连续粘贴生成可编辑表格，尾部光标有效，两个表格的行列身份互不重用。 */
    @Test
    fun pastesIndependentTablesAndKeepsTypingAfterThem() {
        val state = createState()
        val originalId = state.selectedDocument!!.note.blocks.single().id
        val firstCursor = assertNotNull(state.pasteMarkdownAtBlock(originalId, 0, 0, TABLE))
        val firstBlocks = state.selectedDocument!!.note.blocks
        assertEquals(listOf(BlockTypes.BASE_TABLE, BlockTypes.PARAGRAPH), firstBlocks.map(Block::type))
        assertEquals(firstBlocks.last().id, firstCursor.blockId)
        assertEquals(0, firstCursor.offset)

        val nextCursor = assertNotNull(state.pasteMarkdownAtBlock(firstCursor.blockId, 0, 0, TABLE))
        state.updateBlockText(nextCursor.blockId, "表后正文")
        val note = state.selectedDocument!!.note
        val tables = note.blocks.mapNotNull { it.content as? BaseTableBlockContent }
        assertEquals(2, tables.size)
        assertEquals(listOf("名称", "说明"), tables.first().columns.map { it.name.plainText })
        assertEquals("示例", (tables.first().rows.single().cells[tables.first().columns.first().columnId] as BaseTableTextCell).richText.plainText)
        assertTrue(tables[0].columns.map { it.columnId }.intersect(tables[1].columns.map { it.columnId }.toSet()).isEmpty())
        assertTrue(tables[0].rows.map { it.rowId }.intersect(tables[1].rows.map { it.rowId }.toSet()).isEmpty())
        assertEquals("表后正文", note.blocks.last().editableRichText()?.plainText)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 替换字符选区时保留前后正文及前缀原有的行内样式。 */
    @Test
    fun preservesSurroundingRichTextWhenReplacingSelection() {
        val state = createState()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(blockId, "beforeXafter")
        state.updateSelection(blockId, 0, 6)
        state.setSelectionMark(InlineMarkTypes.BOLD, emptyMap())

        val cursor = assertNotNull(state.pasteMarkdownAtBlock(blockId, 6, 7, TABLE))
        val note = state.selectedDocument!!.note
        assertEquals(listOf(BlockTypes.PARAGRAPH, BlockTypes.BASE_TABLE, BlockTypes.PARAGRAPH), note.blocks.map(Block::type))
        assertEquals("before", note.blocks.first().editableRichText()?.plainText)
        assertEquals(InlineMarkTypes.BOLD, note.blocks.first().editableRichText()?.runs?.single()?.marks?.single()?.type)
        assertEquals("after", note.blocks.last().editableRichText()?.plainText)
        assertEquals(note.blocks.last().id, cursor.blockId)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 有子节点的文本块保留为原父节点，粘贴表格不接管或丢弃这些子内容。 */
    @Test
    fun preservesNestedContentWhenPastingAtParentStart() {
        val state = createState()
        val parent = state.selectedDocument!!.note.blocks.single()
        val child = Block(
            id = BlockId("nested-placeholder"),
            type = BlockTypes.PARAGRAPH,
            parentId = parent.id,
            orderKey = "0000000000",
            content = RichTextBlockContent(RichText(listOf(InlineRun("已有子内容")))),
        )
        state.updateSelectedNote { it.copy(blocks = listOf(parent, child)) }

        assertNotNull(state.pasteMarkdownAtBlock(parent.id, 0, 0, TABLE))
        val note = state.selectedDocument!!.note
        assertEquals(BlockTypes.PARAGRAPH, note.blocks.single { it.id == parent.id }.type)
        assertEquals(child, note.blocks.single { it.id == child.id })
        assertTrue(note.blocks.single { it.type == BlockTypes.BASE_TABLE }.parentId == parent.parentId)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 围栏中的表格语法仍作为代码原文保留，不生成网格单元格。 */
    @Test
    fun leavesFencedTableExampleAsCode() {
        val state = createState()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        assertNotNull(state.pasteMarkdownAtBlock(blockId, 0, 0, "```markdown\n$TABLE\n```"))
        val content = state.selectedDocument!!.note.blocks.single().content as CodeBlockContent
        assertEquals(TABLE, content.text)
    }

    /**
     * 使用可预测且每次递增的测试 ID 工厂装配生产编辑状态。
     * @return 已创建空笔记的编辑器状态。
     */
    private fun createState(): TagloomEditorState {
        var nextId = 0
        return TagloomEditorState(
            idFactory = { prefix -> "$prefix-${nextId++}" },
            timestampProvider = { "2026-01-01T00:00:00Z" },
        ).apply { createDocument() }
    }

    private companion object {
        /** 仅包含脱敏内容、两列一行的标准管道表格。 */
        val TABLE: String = "| 名称 | 说明 |\n| --- | --- |\n| 示例 | **正文** |"
    }
}
