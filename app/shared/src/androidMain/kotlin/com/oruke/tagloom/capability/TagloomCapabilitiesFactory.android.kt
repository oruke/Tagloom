package com.oruke.tagloom.capability

import com.oruke.tagloom.filesystem.createWorkspaceFileSystem
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.workspace.DesktopWorkspaceGateway
import com.oruke.tagloom.workspace.JvmWorkspaceIndexEngine
import com.oruke.tagloom.workspace.WorkspaceDocumentAccess
import com.oruke.tagloom.workspace.AndroidWorkspaceDocuments

/**
 * 装配 Android 私有文件、SQLCipher 索引和 Ktor 同步能力。
 *
 * @return 共享 UI 可直接使用的能力容器。
 */
actual fun createTagloomCapabilities(): TagloomCapabilities =
    createAndroidTagloomCapabilities(AndroidWorkspaceDocuments(AndroidTagloomEnvironment.context()))

/**
 * 用已绑定宿主生命周期的文档入口创建 Android 能力容器。
 * @param documents 系统文档交互或测试提供的等价受控入口。
 * @return 每个宿主会话唯一的工作区协调器和三项能力。
 */
fun createAndroidTagloomCapabilities(documents: WorkspaceDocumentAccess): TagloomCapabilities {
    val workspace = DesktopWorkspaceGateway(documents = documents)
    val index = JvmWorkspaceIndexEngine(workspace)
    return TagloomCapabilities(
        workspace = workspace,
        fileSystem = createWorkspaceFileSystem(),
        indexEngine = index,
        searchEngine = index,
        syncEngine = createSyncEngine(),
    )
}
