package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证编辑器输入阶段会把 Markdown 和颜色写入结构化 RichText/Block 状态。
 */
class InlineMarkdownEditingTest {
    /**
     * 验证四种闭合行内语法会移除分隔符并生成对应 Mark。
     */
    @Test
    fun convertsClosedInlineMarkdownTokens() {
        val cases = listOf(
            Triple("**粗体**", "粗体", InlineMarkTypes.BOLD),
            Triple("*斜体*", "斜体", InlineMarkTypes.ITALIC),
            Triple("~~删除~~", "删除", InlineMarkTypes.STRIKETHROUGH),
            Triple("`code`", "code", InlineMarkTypes.CODE),
        )

        cases.forEach { (source, expectedText, expectedMark) ->
            val result = assertNotNull(
                InlineMarkdownShortcutReducer.apply(RichText(listOf(InlineRun(source))), source.length),
            )
            assertEquals(expectedText, result.richText.plainText)
            assertEquals(expectedMark, result.richText.runs.single().marks.single().type)
            assertEquals(expectedText.length, result.cursorOffset)
        }
    }

    /**
     * 验证转义分隔符和未闭合输入不会被提前改写。
     */
    @Test
    fun leavesEscapedOrOpenTokensUntouched() {
        val escaped = "\\*普通*"
        val open = "**仍在输入"
        assertNull(InlineMarkdownShortcutReducer.apply(RichText(listOf(InlineRun(escaped))), escaped.length))
        assertNull(InlineMarkdownShortcutReducer.apply(RichText(listOf(InlineRun(open))), open.length))
    }

    /**
     * 验证文本差异能精确识别选区替换和多行粘贴内容。
     */
    @Test
    fun computesMinimalTextEditDelta() {
        assertEquals(
            TextEditDelta(replacedStart = 6, replacedEnd = 10, insertedText = "新\n段落"),
            TextEditDelta.between("alpha beta omega", "alpha 新\n段落 omega"),
        )
    }

    /**
     * 验证多行 Markdown 粘贴会成为多个 Block，并保留粘贴点前后正文。
     */
    @Test
    fun pastesMarkdownAsStructuredBlocksInsideEditor() {
        var counter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        state.createDocument()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(blockId, "before after")

        val cursor = assertNotNull(
            state.pasteMarkdownAtBlock(blockId, 6, 6, "## 标题\n\n- 条目"),
        )
        val blocks = state.selectedDocument!!.note.blocks.filterNot { block -> block.deleted }

        assertEquals(listOf(BlockTypes.PARAGRAPH, BlockTypes.HEADING, BlockTypes.BULLETED_LIST), blocks.map { it.type })
        assertEquals("before", blocks[0].editableRichText()?.plainText)
        assertEquals("标题", blocks[1].editableRichText()?.plainText)
        assertEquals("条目 after", blocks[2].editableRichText()?.plainText)
        assertEquals(blocks[2].id, cursor.blockId)
    }

    /**
     * 验证选择新语义色会替换同类型旧 token，而不是叠加冲突 Mark。
     */
    @Test
    fun replacesParameterizedColorMark() {
        var counter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        state.createDocument()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(blockId, "color")
        state.updateSelection(blockId, 0, 5)

        state.setSelectionMark(InlineMarkTypes.TEXT_COLOR, mapOf(InlineMarkAttributes.COLOR_TOKEN to "red"))
        state.setSelectionMark(InlineMarkTypes.TEXT_COLOR, mapOf(InlineMarkAttributes.COLOR_TOKEN to "blue"))

        val marks = state.selectedDocument!!.note.blocks.single().editableRichText()!!.runs.single().marks
        assertEquals(1, marks.count { mark -> mark.type == InlineMarkTypes.TEXT_COLOR })
        assertTrue(marks.single().attributes[InlineMarkAttributes.COLOR_TOKEN] == "blue")
    }
}
