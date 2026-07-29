package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.TodoBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证连续文档编辑状态机，而不是只验证孤立表单字段更新。
 */
class BlockDocumentEditingTest {
    /**
     * 验证 Enter 在光标处拆分，并让标题的后续 Block 回到正文。
     */
    @Test
    fun splitsHeadingIntoFollowingParagraph() {
        val state = testState()
        state.createDocument()
        val firstId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(firstId, "# ")
        assertNotNull(state.applyMarkdownShortcut(firstId, "# "))
        state.updateBlockText(firstId, "标题正文")

        val next = assertNotNull(state.splitBlock(firstId, 2, 2))
        val active = state.orderedActiveBlocks(state.selectedDocument!!.note)

        assertEquals(BlockTypes.HEADING, active[0].type)
        assertEquals(1, (active[0].content as HeadingBlockContent).level)
        assertEquals("标题", active[0].editableRichText()!!.plainText)
        assertEquals(BlockTypes.PARAGRAPH, active[1].type)
        assertEquals("正文", active[1].editableRichText()!!.plainText)
        assertEquals(active[1].id, next.blockId)
        assertEquals(0, next.offset)
    }

    /**
     * 验证跨拆分边界的行内样式和加注锚点不会静默丢失，重新合并后仍可规范化。
     */
    @Test
    fun preservesMarksAndAnnotationsAcrossSplitAndMerge() {
        val state = testState()
        state.createDocument()
        val firstId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(firstId, "alpha beta gamma")
        state.updateSelection(firstId, 6, 10)
        state.toggleSelectionMark(InlineMarkTypes.CODE)
        state.annotationDraft = "术语解释"
        state.addAnnotationToSelection()

        val second = assertNotNull(state.splitBlock(firstId, 8, 8))
        val splitBlocks = state.orderedActiveBlocks(state.selectedDocument!!.note)
        assertTrue(splitBlocks[0].editableRichText()!!.runs.last().marks.any { mark -> mark.type == InlineMarkTypes.CODE })
        assertTrue(splitBlocks[1].editableRichText()!!.runs.first().annotationIds.isNotEmpty())

        val merged = assertNotNull(state.mergeBlockWithPrevious(second.blockId))
        val active = state.orderedActiveBlocks(state.selectedDocument!!.note)
        val richText = active.single().editableRichText()!!
        assertEquals(firstId, merged.blockId)
        assertEquals("alpha beta gamma", richText.plainText)
        val beta = richText.runs.first { run -> run.text == "beta" }
        assertTrue(beta.marks.any { mark -> mark.type == InlineMarkTypes.CODE })
        assertEquals(1, beta.annotationIds.size)
    }

    /**
     * 验证空 Todo 再按 Enter 会退回正文，而不是制造第二个空 Todo。
     */
    @Test
    fun emptyTodoFallsBackToParagraph() {
        val state = testState()
        state.createDocument()
        val blockId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(blockId, "[] ")
        assertNotNull(state.applyMarkdownShortcut(blockId, "[] "))
        assertTrue(state.selectedDocument!!.note.blocks.single().content is TodoBlockContent)

        val cursor = assertNotNull(state.splitBlock(blockId, 0, 0))
        val active = state.orderedActiveBlocks(state.selectedDocument!!.note)

        assertEquals(1, active.size)
        assertEquals(BlockTypes.PARAGRAPH, active.single().type)
        assertEquals(blockId, cursor.blockId)
    }

    /**
     * 验证连续有序列表显示稳定序号，并在其他类型后重新从 1 开始。
     */
    @Test
    fun numbersConsecutiveListItems() {
        val state = testState()
        state.createDocument()
        val firstId = state.selectedDocument!!.note.blocks.single().id
        state.turnBlockInto(firstId, EditorBlockType.NUMBERED_LIST)
        val second = assertNotNull(state.insertBlockAfter(firstId, EditorBlockType.NUMBERED_LIST))
        val paragraph = assertNotNull(state.insertBlockAfter(second.blockId, EditorBlockType.TEXT))
        val third = assertNotNull(state.insertBlockAfter(paragraph.blockId, EditorBlockType.NUMBERED_LIST))

        assertEquals(1, state.numberedListOrdinal(firstId))
        assertEquals(2, state.numberedListOrdinal(second.blockId))
        assertEquals(1, state.numberedListOrdinal(third.blockId))
    }

    /**
     * 验证编辑会设置未保存状态，而确认相同快照后恢复为已保存并保留光标。
     */
    @Test
    fun tracksDirtyStateWithoutClearingSelectionOnSave() {
        val state = testState()
        state.createDocument()
        val document = state.selectedDocument!!
        val blockId = document.note.blocks.single().id
        state.updateBlockText(blockId, "持续写作")
        state.updateSelection(blockId, 4, 4)
        assertTrue(state.selectedDocument!!.dirty)

        state.acceptPersistedDocument(
            previousId = document.id,
            persistedDocument = state.selectedDocument!!.copy(persisted = true, dirty = false),
        )

        assertFalse(state.selectedDocument!!.dirty)
        assertEquals(EditorSelection(blockId, 4, 4), state.selection)
    }

    /**
     * 构造使用确定性 ID 和时间的编辑状态。
     *
     * @return 空编辑器状态。
     */
    private fun testState(): TagloomEditorState {
        var counter = 0
        return TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-13T00:00:00Z" },
        )
    }
}
