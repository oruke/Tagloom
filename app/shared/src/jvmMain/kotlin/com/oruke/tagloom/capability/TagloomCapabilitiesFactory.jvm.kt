package com.oruke.tagloom.capability

import com.oruke.tagloom.filesystem.createWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.workspace.DesktopWorkspaceAccessStore
import com.oruke.tagloom.workspace.DesktopWorkspaceGateway
import com.oruke.tagloom.workspace.DesktopWorkspaceLocalAccessService
import com.oruke.tagloom.workspace.DesktopWorkspaceManager
import com.oruke.tagloom.workspace.JvmWorkspaceIndexEngine

/**
 * 装配 JVM 工作区所需的三项能力和共享工作区入口。
 *
 * 文件系统、索引/搜索和同步实现分别来自各自 KMP 能力模块；工作区网关只负责把这些
 * 能力编排到共享 UI。同步传输需要工作区解锁后的设备密钥、Token 和服务端 URI，因此
 * 默认返回未配置但可替换的 JVM 同步会话，不猜测本机凭据或网络地址。
 *
 * @return 已装配的 JVM 能力容器。
 */
actual fun createTagloomCapabilities(): TagloomCapabilities {
    val cryptoProvider = LibsodiumCryptoProvider()
    val workspaceGateway = DesktopWorkspaceGateway(
        manager = DesktopWorkspaceManager(cryptoProvider = cryptoProvider),
        localAccessService = DesktopWorkspaceLocalAccessService(
            accessStore = DesktopWorkspaceAccessStore(cryptoProvider = cryptoProvider),
        ),
    )
    val indexAndSearch = JvmWorkspaceIndexEngine(workspaceGateway)
    return TagloomCapabilities(
        workspace = workspaceGateway,
        fileSystem = createWorkspaceFileSystem(),
        indexEngine = indexAndSearch,
        searchEngine = indexAndSearch,
        syncEngine = createSyncEngine(),
    )
}
