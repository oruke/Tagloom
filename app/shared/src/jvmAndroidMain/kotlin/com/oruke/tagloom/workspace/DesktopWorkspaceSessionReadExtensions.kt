package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.coroutines.sync.withLock

/**
 * 重新读取当前全部未删除 Note。
 *
 * @return 按 Note ID 稳定排序的文档。
 */
suspend fun DesktopWorkspaceSession.loadNotes(): List<DesktopStoredNote> = sessionMutex.withLock {
    requireOpen()
    loadActiveNotes()
}

/**
 * 读取当前已确认笔记与目录，不重复初始化或迁移。
 *
 * @return 与当前 manifest 一致的打开状态。
 */
suspend fun DesktopWorkspaceSession.reloadState(): DesktopWorkspaceOpenState = sessionMutex.withLock {
    requireOpen()
    openState()
}

/**
 * 替换一篇已确认 Note 的派生索引投影。
 *
 * 该入口只供 JVM 能力代理使用；权威快照仍必须先由事务服务确认，普通业务写入不会直接调用它。
 *
 * @param document 待写入索引的完整 Note 投影。
 */
internal suspend fun DesktopWorkspaceSession.replaceIndex(document: NoteIndexDocument) = sessionMutex.withLock {
    requireOpen()
    projection.index.replace(document)
}

/**
 * 从派生索引移除一篇 Note。
 *
 * @param noteId 待移除的权威 Note ID。
 */
internal suspend fun DesktopWorkspaceSession.removeIndex(noteId: ObjectId) = sessionMutex.withLock {
    requireOpen()
    projection.index.remove(noteId)
}

/**
 * 从已确认快照投影重建派生索引。
 *
 * @param documents 当前确认点的 Note 投影集合。
 * @param checkpoint 重建成功后记录的权威边界。
 * @param onProgress 每完成一篇文档后的进度回调。
 */
internal suspend fun DesktopWorkspaceSession.rebuildIndex(
    documents: List<NoteIndexDocument>,
    checkpoint: SearchIndexCheckpoint,
    onProgress: (SearchIndexRebuildProgress) -> Unit,
) = sessionMutex.withLock {
    requireOpen()
    projection.index.rebuild(documents, checkpoint, onProgress)
}

/**
 * 读取当前索引覆盖的权威 checkpoint。
 *
 * @return 索引尚未完整重建时为空。
 */
internal suspend fun DesktopWorkspaceSession.readIndexCheckpoint(): SearchIndexCheckpoint? = sessionMutex.withLock {
    requireOpen()
    projection.index.readCheckpoint()
}

/**
 * 清除派生索引数据但保留其数据库结构。
 */
internal suspend fun DesktopWorkspaceSession.clearIndex() = sessionMutex.withLock {
    requireOpen()
    projection.index.clear()
}
