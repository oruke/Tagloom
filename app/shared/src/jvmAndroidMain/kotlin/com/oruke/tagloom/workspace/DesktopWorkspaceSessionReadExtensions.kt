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

/**
 * 读取当前已确认笔记与目录，不重复初始化或迁移。
 * @return 与当前 manifest 一致的打开状态。
 */
suspend fun DesktopWorkspaceSession.reloadState(): DesktopWorkspaceOpenState = sessionMutex.withLock {
    requireOpen()
    openState()
}
