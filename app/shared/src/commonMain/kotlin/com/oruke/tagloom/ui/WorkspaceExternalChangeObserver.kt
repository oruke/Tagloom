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
        if (phase != WorkspaceUiPhase.OPEN) return@collect
        val sessionMarker = workspaceSessionMarker
        try {
            val confirmed = gateway.reload() ?: return@collect
            // I/O 期间可能锁定并重新打开，不能让旧快照重新带回明文或覆盖新会话的文档。
            if (phase != WorkspaceUiPhase.OPEN || workspaceSessionMarker !== sessionMarker) return@collect
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
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            // 已结束会话的失败由生命周期处理；当前会话刷新失败必须给出脱敏的本地化提示。
            if (phase == WorkspaceUiPhase.OPEN && workspaceSessionMarker === sessionMarker) {
                errorMessage = errorMessage ?: WorkspaceUiMessage.OperationFailed
            }
        }
    }
}
