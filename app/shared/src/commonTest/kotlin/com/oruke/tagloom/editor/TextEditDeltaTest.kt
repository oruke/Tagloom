package com.oruke.tagloom.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.NoteValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证真实文本框选区驱动的粘贴差异与共享编辑器表格转换，防止公共前后缀截断输入。 */
class TextEditDeltaTest {
    /** 验证全选已有表头后粘贴完整表格，会以完整表头替换旧选区并生成合法表格。 */
    @Test
    fun selectedHeaderReplacementKeepsEntireMarkdownTable() {
        val existing = "| A |"
        val markdown = "| A |\n| --- |\n| x |"
        val delta = TextEditDelta.between(
            TextFieldValue(existing, selection = TextRange(existing.length, 0)),
            TextFieldValue(markdown, selection = TextRange(markdown.length)),
        )
        assertEquals(TextEditDelta(0, existing.length, markdown), delta)

        var sequence = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${sequence++}" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        state.createDocument()
        val blockId = assertNotNull(state.selectedDocument).note.blocks.single().id
        state.updateBlockText(blockId, existing)
        assertNotNull(state.pasteMarkdownAtBlock(blockId, delta.replacedStart, delta.replacedEnd, delta.insertedText))

        val note = assertNotNull(state.selectedDocument).note
        val table = assertIs<BaseTableBlockContent>(note.blocks.first { !it.deleted }.content)
        assertEquals("A", table.columns.single().name.plainText)
        assertEquals("x", assertIs<BaseTableTextCell>(table.rows.single().cells.values.single()).richText.plainText)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 验证部分选区替换中的相同首尾文字属于粘贴内容，不会被公共前后缀剥离。 */
    @Test
    fun selectedReplacementRetainsMatchingPrefixAndSuffix() {
        val prefix = "before "
        val selected = "repeated"
        val suffix = " after"
        val pasted = "repeated\nnew row\nrepeated"
        val delta = TextEditDelta.between(
            TextFieldValue(prefix + selected + suffix, TextRange(prefix.length, prefix.length + selected.length)),
            TextFieldValue(prefix + pasted + suffix, TextRange(prefix.length + pasted.length)),
        )

        assertEquals(TextEditDelta(prefix.length, prefix.length + selected.length, pasted), delta)
    }

    /** 验证折叠光标处粘贴与周围重复字符相同的文本时，仍以光标位置识别完整插入。 */
    @Test
    fun collapsedSelectionRetainsRepeatedInsertionText() {
        val delta = TextEditDelta.between(
            TextFieldValue("aaaa", TextRange(2)),
            TextFieldValue("aaa\naaa", TextRange(5)),
        )

        assertEquals(TextEditDelta(2, 2, "a\na"), delta)
    }

    /** 验证仅收起多行选区而未变更文本时，不会把旧选区内容误当成一次粘贴。 */
    @Test
    fun selectionOnlyChangeDoesNotInsertExistingMultilineText() {
        val text = "| A |\n| --- |\n| x |"
        val delta = TextEditDelta.between(
            TextFieldValue(text, TextRange(0, text.length)),
            TextFieldValue(text, TextRange(text.length)),
        )

        assertEquals("", delta.insertedText)
        assertEquals(delta.replacedStart, delta.replacedEnd)
    }

    /** 验证删除和非标准外部文本同步继续使用原最小差异路径，不要求伪造粘贴选区。 */
    @Test
    fun fallsBackForDeletionAndUnrelatedSelectionEvents() {
        assertEquals(
            TextEditDelta(1, 2, ""),
            TextEditDelta.between(TextFieldValue("abc", TextRange(2)), TextFieldValue("ac", TextRange(1))),
        )
        assertEquals(
            TextEditDelta(6, 10, "新\n段落"),
            TextEditDelta.between(
                TextFieldValue("alpha beta omega", TextRange(0)),
                TextFieldValue("alpha 新\n段落 omega", TextRange(0)),
            ),
        )
        assertEquals(
            TextEditDelta(6, 10, "新\n段落"),
            TextEditDelta.between(
                TextFieldValue("alpha beta omega", TextRange(6, 10)),
                TextFieldValue("alpha 新\n段落 omega", TextRange(6, 10)),
            ),
        )
    }
}
