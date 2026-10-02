package com.oruke.tagloom.workspace

import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path

/**
 * 返回已认证的本地确认版本，供自动上传去重；不读取派生索引。
 * @return manifest 当前版本。
 */
internal suspend fun DesktopWorkspaceSession.serverRecoveryRevision(): Long = sessionMutex.withLock {
    requireWritable()
    unlocked.payload.revision
}

/**
 * 创建完整密文副本并发布远端确认点，成功后才记录服务器模式及本地已上传版本。
 * @param session 工作区唯一权威会话。
 * @param root 当前本地副本目录。
 * @param access 当前设备的短生命周期连接材料。
 * @param client 本次网络传输入口。
 * @param store 受设备保护的本机配置。
 */
internal suspend fun publishServerRecoveryCopy(
    session: DesktopWorkspaceSession,
    root: Path,
    access: ServerRecoveryAccess,
    client: ServerRecoveryClient,
    store: ServerRecoveryAccessStore,
) {
    // 先记录版本再生成备份：期间新增写入最多导致下次重复上传，绝不会误标为已经上传。
    val revision = session.serverRecoveryRevision()
    val archive = Files.createTempFile("tagloom-server-backup-", ".zip")
    try {
        session.createBackup(archive)
        access.snapshot = client.upload(access.workspaceId, access.actorId, access.token, access.snapshot?.revision ?: 0,
            access.recoveryId, access.recoveryTokenSha256, archive)
        access.uploadedManifestRevision = revision
        access.mode = WorkspaceStorageMode.SERVER
        store.save(root, access)
    } finally { Files.deleteIfExists(archive) }
}
