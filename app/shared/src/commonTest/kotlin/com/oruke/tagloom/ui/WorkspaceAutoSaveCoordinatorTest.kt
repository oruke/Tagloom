package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.editor.EditorDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证自动保存只抑制未变化的已尝试草稿版本。
 */
class WorkspaceAutoSaveCoordinatorTest {
    /**
     * 验证同一失败版本不会持续重试，而再次编辑后的新版本可以重新保存。
     */
    @Test
    fun retriesOnlyAfterDraftChanges() {
        val coordinator = WorkspaceAutoSaveCoordinator()
        val failedDraft = EditorDocument(
            id = ObjectId("note-test"),
            note = Note(title = "草稿", directTagIds = emptyList()),
            dirty = true,
            draftVersion = 3L,
        )

        assertTrue(coordinator.canAttempt(failedDraft, busy = false))
        coordinator.recordAttempt(failedDraft)
        assertFalse(coordinator.canAttempt(failedDraft, busy = false))

        val editedDraft = failedDraft.copy(
            note = failedDraft.note.copy(title = "再次编辑"),
            draftVersion = 4L,
        )
        assertTrue(coordinator.canAttempt(editedDraft, busy = false))
    }

    /**
     * 验证保存中的工作区和无修改文档不会触发自动保存。
     */
    @Test
    fun skipsBusyOrCleanDocument() {
        val coordinator = WorkspaceAutoSaveCoordinator()
        val dirty = EditorDocument(
            id = ObjectId("note-test"),
            note = Note(title = "草稿", directTagIds = emptyList()),
            dirty = true,
        )

        assertFalse(coordinator.canAttempt(dirty, busy = true))
        assertFalse(coordinator.canAttempt(dirty.copy(dirty = false), busy = false))
    }
}
