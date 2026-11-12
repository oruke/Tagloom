package com.oruke.tagloom.workspace

import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.filesystem.jvm.createEncryptedTemporaryArchive
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path

/**
 * 先拉取并事务合并远端确认点，再用同一确认点的 CAS 发布本地并集。
 *
 * 传输仍使用完整密文归档，客户端通过领域 Change DAG 合并；读取最新版本号本身从不授予覆盖许可。
 * @param session 本机唯一权威会话。
 * @param root 与设备保护配置绑定的工作区目录。
 * @param access 当前设备短生命周期访问材料。
 * @param client 已认证传输客户端。
 * @param store 不随工作区同步的设备保护配置。
 * @param onWorkspaceChanged 本地确认后发出的无正文刷新通知。
 */
internal suspend fun synchronizeServerCopy(
    session: DesktopWorkspaceSession,
    root: Path,
    access: ServerRecoveryAccess,
    client: ServerRecoveryClient,
    store: ServerRecoveryAccessStore,
    onWorkspaceChanged: () -> Unit,
) {
    repeat(MAX_SYNCHRONIZATION_ATTEMPTS) {
        currentCoroutineContext().ensureActive()
        session.requireOpen()
        val latest = client.latestSnapshot(access.workspaceId, access.actorId, access.token)
        val observed = access.snapshot
        if (observed != null && latest != null) {
            check(latest.revision >= observed.revision &&
                (latest.revision != observed.revision || latest.sha256 == observed.sha256)) { "sync_integrity_failed" }
        }
        if (latest != null && latest.sha256 != access.snapshot?.sha256) {
            val archive = createEncryptedTemporaryArchive("tagloom-sync-download-")
            try {
                client.download(latest, access.actorId, access.token, archive)
                val changed = try {
                    DesktopReplicaTransaction(session).merge(archive)
                } catch (_: com.oruke.tagloom.core.crypto.CryptoException) {
                    throw ServerRecoveryException("sync_integrity_failed")
                } catch (_: IllegalArgumentException) {
                    throw ServerRecoveryException("sync_integrity_failed")
                }
                if (changed) onWorkspaceChanged()
            } finally { Files.deleteIfExists(archive) }
        }
        // 相同归档内容也可能被重新确认；内容已认证，但 CAS 必须使用最新描述的版本。
        if (latest != null) {
            access.snapshot = latest
            store.save(root, access)
        }
        // 恢复或旧版本登记已观察过远端确认点，服务端不得倒退到未发布状态。
        check(latest != null || access.snapshot == null) { "sync_integrity_failed" }
        val revision = session.serverRecoveryRevision()
        if (latest != null && access.uploadedManifestRevision == revision) {
            access.mode = WorkspaceStorageMode.SERVER
            store.save(root, access)
            return
        }
        try {
            currentCoroutineContext().ensureActive()
            publishServerRecoveryCopy(session, root, access, client, store)
            return
        } catch (error: ServerRecoveryException) {
            if (error.code != "recovery_revision_changed") throw error
            // 另一设备在下载后抢先发布：保留已确认合并，重新下载并再次合并，禁止换新 CAS 盲写。
        }
    }
    throw ServerRecoveryException("sync_retry_required")
}

/** 有界重试并发提交，持续争用交给自动循环退避，不阻塞本地编辑。 */
private const val MAX_SYNCHRONIZATION_ATTEMPTS = 3
