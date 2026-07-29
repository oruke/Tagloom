package com.oruke.tagloom.editor

import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.NoteValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 Compose 编辑命令会落到结构化 Note/RichText，而不是只改变临时界面文本。
 */
class TagloomEditorStateTest {
    /**
     * 验证单词级 code 与独立加注能通过编辑状态进入 Markdown 和 Note 校验。
     */
    @Test
    fun appliesSelectionCommandsToStructuredNote() {
        var counter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-13T00:00:00Z" },
        )
        state.createDocument()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(blockId, "alpha beta")
        state.updateSelection(blockId, 6, 10)
        state.toggleSelectionMark(InlineMarkTypes.CODE)
        state.annotationDraft = "beta 的解释"
        state.addAnnotationToSelection()

        val note = state.selectedDocument!!.note
        val richText = requireNotNull(note.blocks.single().editableRichText())
        val selectedRun = richText.runs.first { run -> run.text == "beta" }
        assertTrue(selectedRun.marks.any { mark -> mark.type == InlineMarkTypes.CODE })
        assertEquals(1, selectedRun.annotationIds.size)
        assertEquals("beta 的解释", note.annotations.getValue(selectedRun.annotationIds.single()).body)
        assertTrue(NoteValidator.validate(note).isEmpty())
        assertTrue("{{" in requireNotNull(state.exportSelected(MarkdownExportMode.TAGLOOM_EXTENDED)).markdown)
    }
}
