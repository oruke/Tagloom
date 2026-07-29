package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证侧栏新建和切换依赖的编辑器状态契约。
 */
class WorkspaceNavigationTest {
    /**
     * 验证切换笔记后活动文档同步变化，并清除上一文档的文本选区。
     */
    @Test
    fun selectsExistingDocumentAndClearsSelection() {
        val firstId = ObjectId("note-first")
        val secondId = ObjectId("note-second")
        val state = createNavigationState(firstId, secondId)
        state.updateSelection(state.selectedDocument!!.note.blocks.single().id, 0, 1)

        state.selectDocument(secondId)

        assertEquals(secondId, state.selectedDocumentId)
        assertEquals("第二篇笔记", state.selectedDocument?.note?.title)
        assertFalse(state.selection.isNotEmpty)
    }

    /**
     * 验证新建动作立即选中新文档，并创建可直接输入的空段落。
     */
    @Test
    fun createsAndSelectsEditableDocument() {
        val state = createNavigationState(ObjectId("note-first"), ObjectId("note-second"))
        val previousId = state.selectedDocumentId

        state.createDocument()

        val created = requireNotNull(state.selectedDocument)
        assertEquals(3, state.documents.size)
        assertNotEquals(previousId, created.id)
        assertEquals("未命名笔记", created.note.title)
        assertEquals(BlockTypes.PARAGRAPH, created.note.blocks.single().type)
        assertTrue(created.note.blocks.single().editableRichText()?.plainText.orEmpty().isEmpty())
        assertFalse(created.persisted)
        assertTrue(created.dirty)
    }

    /**
     * 构造拥有两篇已确认笔记的导航测试状态。
     *
     * @param firstId 第一篇笔记 ID。
     * @param secondId 第二篇笔记 ID。
     * @return 默认选中第一篇笔记的编辑器状态。
     */
    private fun createNavigationState(
        firstId: ObjectId,
        secondId: ObjectId,
    ): TagloomEditorState {
        var counter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        state.loadWorkspaceDocuments(
            loadedDocuments = listOf(
                EditorDocument(
                    firstId,
                    Note(
                        title = "第一篇笔记",
                        directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                        blocks = listOf(EditorBlockFactory.create(BlockTypes.PARAGRAPH, 0) { "first-$it" }),
                    ),
                ),
                EditorDocument(
                    secondId,
                    Note(
                        title = "第二篇笔记",
                        directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                        blocks = listOf(EditorBlockFactory.create(BlockTypes.PARAGRAPH, 0) { "second-$it" }),
                    ),
                ),
            ),
        )
        return state
    }
}
