package com.oruke.tagloom.ui

import com.oruke.tagloom.editor.EditorDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * 外部确认写入后刷新干净笔记，并保留正在编辑的草稿及其旧 revision。
 *
 * 保留旧 revision 是为了让并发草稿保存明确报告冲突，不能把草稿偷偷换成新 revision 后覆盖外部修改。
 * 刷新始终由 UI 协程执行，不在本机 API 后台线程修改 Compose 状态。
 */
internal suspend fun WorkspaceUiController.observeExternalChanges() {
    gateway.externalChanges.collect {
        refreshConfirmedWorkspace()
    }
}

/**
 * 从统一网关刷新同步或外部事务确认的内容及目录，保持脏草稿与审阅时的 revision。
 *
 * 刷新只发生在原解锁会话，类型与数据库目录一并更新，不能让新笔记引用旧 schema。
 */
internal suspend fun WorkspaceUiController.refreshConfirmedWorkspace() {
    if (phase != WorkspaceUiPhase.OPEN) return
    val sessionMarker = workspaceSessionMarker
    try {
        val confirmed = gateway.reload() ?: return
        // I/O 期间可能锁定并重新打开，不能让旧快照重新带回明文或覆盖新会话的文档。
        if (phase != WorkspaceUiPhase.OPEN || workspaceSessionMarker !== sessionMarker) return
        val loaded = confirmed.notes.associateBy { it.id }
        val documents = editorState.documents
        // 阶段 1：只替换无未提交编辑的文档，删除同样不丢弃用户的脏草稿。
        documents.toList().filterNot { it.dirty }.forEach { current ->
            val persisted = loaded[current.id]
            if (persisted == null) editorState.removeDocument(current.id)
            else if (current.revision != persisted.revision) {
                val index = documents.indexOfFirst { it.id == current.id }
                if (index >= 0) documents[index] = EditorDocument(
                    persisted.id, persisted.note, persisted.revision, persisted.createdAt, persisted.updatedAt, persisted = true,
                )
            }
        }
        // 阶段 2：追加外部新建文档，不抢走当前编辑器焦点。
        confirmed.notes.filter { note -> documents.none { it.id == note.id } }.forEach { note ->
            documents += EditorDocument(note.id, note.note, note.revision, note.createdAt, note.updatedAt, persisted = true)
        }
        // 阶段 3：已确认的目录与笔记来自同一 manifest，不重建整个编辑器以免清除草稿。
        val tags = confirmed.tagDefinitions.map { it.definition }
        if (editorState.tagDefinitions.toList() != tags) {
            editorState.tagDefinitions.clear()
            editorState.tagDefinitions += tags
        }
        val properties = confirmed.propertyDefinitions.map { it.definition }
        if (editorState.propertyDefinitions.toList() != properties) {
            editorState.propertyDefinitions.clear()
            editorState.propertyDefinitions += properties
        }
        savedQueries = confirmed.savedQueries
        databaseController.load(confirmed.databaseDefinitions, confirmed.viewDefinitions)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) {
        // 已结束会话的失败由生命周期处理；当前会话刷新失败必须给出脱敏的本地化提示。
        if (phase == WorkspaceUiPhase.OPEN && workspaceSessionMarker === sessionMarker) {
            errorMessage = errorMessage ?: WorkspaceUiMessage.OperationFailed
        }
    }
}
