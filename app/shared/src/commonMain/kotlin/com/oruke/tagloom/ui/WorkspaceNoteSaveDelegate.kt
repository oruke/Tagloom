package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.materializeSelectedSnapshotDefaults
import com.oruke.tagloom.platform.WorkspaceGateway

/**
 * 编排当前 Note 的手动保存与自动保存版本去重。
 *
 * Delegate 只持有解锁会话内的编辑状态和平台写入端口；事务、加密与文件发布仍由
 * [WorkspaceGateway] 后方的统一应用服务负责。
 */
internal class WorkspaceNoteSaveDelegate(
    /** 当前平台真实工作区能力。 */
    private val gateway: WorkspaceGateway,
    /** 仅在解锁会话内持有 Note 明文的编辑器状态。 */
    private val editorState: TagloomEditorState,
) {
    /** 记录当前会话最近一次实际提交的草稿版本。 */
    private val autoSaveCoordinator = WorkspaceAutoSaveCoordinator()

    /**
     * 判断当前草稿是否应发起一次自动保存。
     *
     * @param busy 工作区是否正执行其他平台操作。
     * @return 当前存在未提交的新草稿版本且工作区空闲时为 true。
     */
    fun canAutoSave(busy: Boolean): Boolean =
        autoSaveCoordinator.canAttempt(editorState.selectedDocument, busy)

    /**
     * 保存当前文档，并保留保存期间继续发生的内存编辑。
     *
     * 每次真正调用平台写入前都会记录草稿版本。失败后同一版本不会被自动保存循环重复
     * 提交，但用户手动保存仍可再次调用本函数。
     *
     * @return 存在选中文档并完成平台保存时为 true；未选中文档时为 false。
     */
    suspend fun saveSelected(): Boolean {
        editorState.materializeSelectedSnapshotDefaults()
        val submitted = editorState.selectedDocument ?: return false
        saveDocument(submitted)
        return true
    }

    /** 保存全部待提交草稿，供完整服务器副本创建前形成已确认状态；任何失败都会阻止上传。 */
    suspend fun savePending() {
        editorState.materializeSelectedSnapshotDefaults()
        editorState.documents.filter { it.dirty }.toList().forEach { saveDocument(it) }
    }

    /**
     * 提交一个草稿并保留操作期间发生的内存编辑。
     * @param submitted 本次提交的不可变草稿版本。
     */
    private suspend fun saveDocument(submitted: EditorDocument) {
        autoSaveCoordinator.recordAttempt(submitted)
        val saved = gateway.saveNote(
            objectId = submitted.id.takeIf { submitted.persisted },
            note = submitted.note,
            expectedRevision = submitted.revision,
        )
        val current = editorState.documents.firstOrNull { document -> document.id == submitted.id }
        val hasConcurrentChanges = current?.note?.let { note -> note != submitted.note } == true
        val visibleNote = current?.note?.takeIf { hasConcurrentChanges } ?: saved.note
        editorState.acceptPersistedDocument(
            previousId = submitted.id,
            persistedDocument = EditorDocument(
                id = saved.id,
                note = visibleNote,
                revision = saved.revision,
                createdAt = current?.createdAt?.takeIf { hasConcurrentChanges } ?: saved.createdAt,
                updatedAt = current?.updatedAt?.takeIf { hasConcurrentChanges } ?: saved.updatedAt,
                persisted = true,
                dirty = hasConcurrentChanges,
                draftVersion = current?.draftVersion ?: submitted.draftVersion,
            ),
        )
    }

    /**
     * 清除上一解锁会话的自动保存记录。
     */
    fun resetAutoSaveAttempt() {
        autoSaveCoordinator.reset()
    }
}

/**
 * 使用编辑器草稿版本阻止失败后的无变化自动保存循环。
 *
 * 手动保存不受本协调器限制；草稿再次变化后版本递增，自动保存即可重新尝试。
 */
internal class WorkspaceAutoSaveCoordinator {
    /** 最近一次已经交给平台保存的文档与草稿版本。 */
    private var lastAttempt: WorkspaceAutoSaveAttempt? = null

    /**
     * 判断当前文档版本是否尚未自动尝试。
     *
     * @param document 当前选中文档。
     * @param busy 工作区是否正执行其他平台操作。
     * @return 可发起新的自动保存时为 true。
     */
    fun canAttempt(
        document: EditorDocument?,
        busy: Boolean,
    ): Boolean {
        if (document == null || !document.dirty || busy) {
            return false
        }
        return lastAttempt != WorkspaceAutoSaveAttempt(document.id, document.draftVersion)
    }

    /**
     * 记录一次即将执行的平台保存。
     *
     * @param document 本次提交的不可变编辑器文档快照。
     */
    fun recordAttempt(document: EditorDocument) {
        lastAttempt = WorkspaceAutoSaveAttempt(document.id, document.draftVersion)
    }

    /**
     * 清除上一解锁会话的版本记录。
     */
    fun reset() {
        lastAttempt = null
    }
}

/**
 * 标识一次自动保存判重所需的最小非敏感版本信息。
 */
private data class WorkspaceAutoSaveAttempt(
    /** 文档稳定不透明 ID。 */
    val documentId: ObjectId,
    /** 当前解锁会话内的草稿版本。 */
    val draftVersion: Long,
)
