package com.oruke.tagloom.workspace

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
