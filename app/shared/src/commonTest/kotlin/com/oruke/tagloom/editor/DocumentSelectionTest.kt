package com.oruke.tagloom.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.ui.DocumentSelectionController
import kotlin.test.*

/** 验证跨块命令的完整数据变换，包括端点、Mark、加注和未选子树。 */
class DocumentSelectionTest {
    /** 反向选区产生正确原文，统一加粗与加注后替换会保留两端与未选子块。 */
    @Test
    fun preservesEdgesAnnotationsAndUnselectedChildren() {
        val state = editor(
            listOf(
                paragraph("a", "alpha beta", "0"), paragraph("b", "middle", "1"),
                paragraph("c", "gamma delta", "2"), paragraph("child", "preserved", "0", BlockId("c"))
            )
        )
        state.selectDocumentRange(EditorCursorTarget(BlockId("c"), 5), EditorCursorTarget(BlockId("a"), 6))
        assertEquals("beta\nmiddle\ngamma", state.selectedPlainText())
        state.toggleSelectionMark(InlineMarkTypes.BOLD)
        assertTrue(state.selectionHasMark(InlineMarkTypes.BOLD))
        state.annotationDraft = "Synthetic annotation"
        state.addAnnotationToSelection()
        val annotated = state.selectedDocument!!.note
        assertEquals(1, annotated.annotations.size)
        assertTrue(NoteValidator.validate(annotated).isEmpty())
        state.replaceDocumentSelection("replacement")
        val result = state.selectedDocument!!.note
        assertEquals("alpha replacement delta", result.blocks.first().selectionText())
        assertEquals(setOf(BlockId("b"), BlockId("c")), result.blocks.filter(Block::deleted).map(Block::id).toSet())
        assertNull(result.blocks.last().parentId)
        assertEquals("preserved", result.blocks.last().selectionText())
        assertEquals(1, result.annotations.size)
        assertTrue(NoteValidator.validate(result).isEmpty(), NoteValidator.validate(result).toString())
    }

    /** 容器和页签边界不允许跨入；代码可复制，文本格式只写入富文本块。 */
    @Test
    fun respectsStructuredBoundariesAndCodeSemantics() {
        val state = editor(
            listOf(
                paragraph("a", "first", "0"),
                Block(
                    BlockId("code"),
                    BlockTypes.CODE,
                    orderKey = "1",
                    content = CodeBlockContent("kotlin", "val x = 1")
                ),
                paragraph("b", "last", "2"),
                Block(BlockId("divider"), BlockTypes.DIVIDER, orderKey = "3", content = DividerBlockContent),
                paragraph("after", "outside", "4")
            )
        )
        state.selectDocumentRange(EditorCursorTarget(BlockId("a"), 2), EditorCursorTarget(BlockId("b"), 3))
        assertEquals("rst\nval x = 1\nlas", state.selectedPlainText())
        state.toggleSelectionMark(InlineMarkTypes.ITALIC)
        assertTrue(state.selectionHasMark(InlineMarkTypes.ITALIC))
        assertEquals("val x = 1", state.selectedDocument!!.note.blocks[1].selectionText())
        val selection = state.selection
        state.selectDocumentRange(EditorCursorTarget(BlockId("a"), 0), EditorCursorTarget(BlockId("after"), 5))
        assertEquals(selection, state.selection)
        val tabs = Block(BlockId("tabs"), BlockTypes.TABS, orderKey = "0", content = TabsBlockContent)
        val tab =
            Block(BlockId("tab"), BlockTypes.TAB, tabs.id, "0", TabBlockContent(RichText(listOf(InlineRun("Page")))))
        state.updateSelectedNote {
            it.copy(
                blocks = listOf(
                    tabs, tab,
                    paragraph("inside", "tab body", "0", tab.id), paragraph("outside", "root body", "1")
                )
            )
        }
        assertEquals(listOf(BlockId("inside")), state.selectionFlow(BlockId("inside")).map(Block::id))
        assertEquals(listOf(BlockId("outside")), state.selectionFlow(BlockId("outside")).map(Block::id))
        val attachment = Block(
            BlockId("attachment"), BlockTypes.IMAGE, orderKey = "1",
            content = AttachmentBlockContent(
                BlobId("sample-blob"),
                "sample.png",
                caption = RichText(listOf(InlineRun("caption")))
            )
        )
        state.updateSelectedNote {
            it.copy(
                blocks = listOf(
                    paragraph("a", "before", "0"),
                    attachment,
                    paragraph("b", "after", "2")
                )
            )
        }
        assertEquals(listOf(BlockId("attachment")), state.selectionFlow(attachment.id).map(Block::id))
        assertEquals(listOf(BlockId("a")), state.selectionFlow(BlockId("a")).map(Block::id))
    }

    /** 中文组合输入只删除跨块范围一次，保留组合偏移并让后续原生提交继续替换组合文本。 */
    @Test
    fun preservesImeCompositionDuringCrossBlockReplacement() {
        val state = editor(listOf(paragraph("a", "left selected", "0"), paragraph("b", "selected right", "1")))
        state.selectDocumentRange(EditorCursorTarget(BlockId("b"), 8), EditorCursorTarget(BlockId("a"), 5))
        val controller = DocumentSelectionController(state) { error("The first block must retain focus") }
        val previous = TextFieldValue("left selected", TextRange(5, 13))
        val composed = TextFieldValue("left ni", TextRange(7), TextRange(5, 7))
        val result = assertNotNull(controller.acceptInput(BlockId("a"), previous, composed))
        assertEquals("left ni right", result.text)
        assertEquals(TextRange(5, 7), result.composition)
        assertFalse(state.selection.isCrossBlock)
        state.updateBlockText(BlockId("a"), "left 你 right")
        assertEquals("left 你 right", state.selectedDocument!!.note.blocks.first().selectionText())
        assertTrue(NoteValidator.validate(state.selectedDocument!!.note).isEmpty())
    }

    /**
     * 建立使用真实编辑命令的领域会话。
     * @param blocks 初始合成块集合。
     * @return 已选中笔记的编辑器。
     */
    private fun editor(blocks: List<Block>): TagloomEditorState {
        var nextId = 0
        return TagloomEditorState({ "$it-${nextId++}" }, { "2026-01-01T00:00:00Z" }).apply {
            createDocument()
            updateSelectedNote { it.copy(blocks = blocks) }
        }
    }

    /**
     * 构造合成文本块。
     * @param id 稳定测试身份。
     * @param text 合成正文。
     * @param order 同级顺序。
     * @param parent 可选父块。
     * @return 标准段落。
     */
    private fun paragraph(id: String, text: String, order: String, parent: BlockId? = null): Block = Block(
        BlockId(id), BlockTypes.PARAGRAPH, parent, order, RichTextBlockContent(RichText(listOf(InlineRun(text))))
    )
}
