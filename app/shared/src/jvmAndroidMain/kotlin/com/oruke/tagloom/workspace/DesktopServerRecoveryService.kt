package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.RecoveryKeyCodec
import com.oruke.tagloom.core.crypto.ServerRecoveryCredentials
import com.oruke.tagloom.core.crypto.serverRecoveryCredentials
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.SyncDeviceRegistration
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.platform.*
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 复用本机加密备份与恢复流程，编排模式切换、自动上传和空设备恢复。 */
internal class DesktopServerRecoveryService(
    /** 工作区唯一会话持有者。 */
    private val manager: DesktopWorkspaceManager,
    /** 认证成功后建立当前设备 PIN 或系统保护入口。 */
    private val localAccess: DesktopWorkspaceLocalAccessService,
    /** 服务器模式工作区的应用私有目录，不要求用户选择缓存位置。 */
    private val directories: ServerWorkspaceDirectoryStore,
    /** 与工作区备份隔离的受保护连接配置。 */
    private val store: ServerRecoveryAccessStore,
    /** HTTPS 传输工厂；隔离测试可明确注入回环适配器。 */
    private val clientFactory: (String) -> ServerRecoveryClient,
    /** 同步确认后只发送无正文事件，由 UI 协程刷新并保留未保存草稿。 */
    private val onWorkspaceChanged: () -> Unit = {},
) : WorkspaceServerRecoveryGateway {
    /** 防止并行恢复、首次登记或上传造成凭据及副本版本竞争。 */
    private val mutex = Mutex()
    /** 当前传输的子任务；锁定时取消并等待清理完成。 */
    @Volatile private var activeTransfer: Job? = null

    /** 取消操作同时使所有已经排队的传输失效，防止它们在锁定后重新开始。 */
    private val transferGeneration = java.util.concurrent.atomic.AtomicLong()

    /** 原子协调任务登记与取消状态，锁内只操作内存，网络和等待始终在锁外。 */
    private val transferStateLock = Any()

    /** 锁定或切本地后暂停同一会话的自动传输，只允许显式启用服务器模式恢复。 */
    @Volatile private var blockedSession: DesktopWorkspaceSession? = null

    /** 与现有工作区格式相同的密码实现。 */
    private val crypto = LibsodiumCryptoProvider()

    /**
     * 提前验证地址并准备服务器工作区，确认后仍复用网关的两阶段创建事务。
     * @param serverUrl 用户指定的服务器地址。
     * @param pin 当前设备可选 PIN。
     * @return 一次性恢复密钥，只有用户确认后才创建文件。
     */
    override suspend fun prepareCreation(serverUrl: String, pin: String?): WorkspacePreparedCreation = withContext(Dispatchers.IO) {
        clientFactory(serverUrl).close()
        val root = directories.allocate()
        val prepared = localAccess.preparePendingCreation(root, pin) { secret -> manager.prepareCreation(root, secret) }
        WorkspacePreparedCreation(prepared.recoveryKeyText)
    }

    /** @return 当前解锁工作区的非敏感远端状态；尚未配置时为空。 */
    override suspend fun status(): WorkspaceServerRecoveryStatus? = withContext(Dispatchers.IO) {
        // 配置本身原子替换，读取无需等待整个网络传输；用户才能在上传期间切换到本地模式。
        val session = requireNotNull(manager.sessionOrNull())
        val access = store.read(requireNotNull(manager.rootOrNull()), session.workspaceId) ?: return@withContext null
        try { access.status(session) } finally { access.destroy() }
    }

    /**
     * 验证 Recovery Key、登记设备并上传本机确认点，密钥材料始终留在设备。
     * @param serverUrl 用户指定服务器地址。
     * @param bootstrapToken 首次登记访问码，不持久化。
     * @param recoveryKeyText 初次上传或轮换后的 Recovery Key。
     * @return 完整上传并确认后的状态。
     */
    override suspend fun publish(serverUrl: String, bootstrapToken: String, recoveryKeyText: String): WorkspaceServerRecoveryStatus =
        transfer(resume = true) {
            crypto.initialize()
            val session = requireNotNull(manager.sessionOrNull())
            val root = requireNotNull(manager.rootOrNull())
            val existing = store.read(root, session.workspaceId)
            // 先认证当前 Recovery Key，防止输入另一工作区的密钥却发布不可恢复的定位记录。
            val credentials = try {
                if (existing == null || !existing.registered || existing.keyId != session.unlocked.file.publicHeader.activeKeyId.value)
                    session.verifyRecoveryKey(recoveryKeyText) else null
            } catch (error: Throwable) { existing?.destroy(); throw error }
            val address = serverUrl.trim().trimEnd('/')
            // 未完成登记时允许纠正地址，但必须生成另一套设备材料，不能把旧服务器凭据发给新地址。
            val access = if (existing == null || !existing.registered && existing.serverUrl != address) {
                try { newAccess(session.workspaceId, address, requireNotNull(credentials), session.unlocked.file.publicHeader.activeKeyId.value) }
                finally { existing?.destroy() }
            } else existing
            try {
                require(access.serverUrl == serverUrl.trim().trimEnd('/')) { "server_recovery_address_changed" }
                clientFactory(access.serverUrl).use { client ->
                    if (!access.registered) {
                        // 先保存同一随机设备身份，再请求服务器，网络重试不能留下无法继续使用的首台设备。
                        store.save(root, access)
                        enrollServerRecoveryDevice(session, access, requireNotNull(credentials), bootstrapToken, client)
                        access.registered = true
                        store.save(root, access)
                    }
                    credentials?.let {
                        access.recoveryId = it.recoveryId
                        access.recoveryTokenSha256 = it.tokenSha256
                        access.keyId = session.unlocked.file.publicHeader.activeKeyId.value
                    }
                    synchronizeServerCopy(session, root, access, client, store, onWorkspaceChanged)
                    access.status(session)
                }
            } finally { access.destroy() }
        }

    /** @return 在服务器模式检查双方变更并同步；本地模式只返回本机状态，不创建网络客户端。 */
    override suspend fun updateIfChanged(): WorkspaceServerRecoveryStatus? {
        if (manager.sessionOrNull() == null) return null
        val current = status() ?: return null
        if (current.mode == WorkspaceStorageMode.LOCAL) return current
        return transfer {
            val session = manager.sessionOrNull() ?: return@transfer null
            val root = requireNotNull(manager.rootOrNull())
            val access = store.read(root, session.workspaceId) ?: return@transfer null
            try {
                val state = access.status(session)
                if (state.mode == WorkspaceStorageMode.SERVER) {
                    if (state.requiresRecoveryKey) throw ServerRecoveryException("recovery_key_required")
                    clientFactory(access.serverUrl).use { client -> synchronizeServerCopy(session, root, access, client, store, onWorkspaceChanged) }
                }
                access.status(session)
            } finally { access.destroy() }
        }
    }

    /** @return 停止传输并保存本地模式；连接材料及原服务器副本继续保留以支持切回。 */
    override suspend fun useLocalMode(): WorkspaceServerRecoveryStatus? {
        cancelTransfer()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val session = requireNotNull(manager.sessionOrNull())
                val root = requireNotNull(manager.rootOrNull())
                val access = store.read(root, session.workspaceId) ?: return@withLock null
                try {
                    access.mode = WorkspaceStorageMode.LOCAL
                    store.save(root, access)
                    access.status(session)
                } finally { access.destroy() }
            }
        }
    }

    /**
     * 凭 Recovery Key 找回原工作区，完整认证后才登记本机入口。
     * @param serverUrl 服务器 HTTPS 地址。
     * @param recoveryKeyText 离线保存的原 Recovery Key。
     * @param pin 新设备可选 PIN。
     * @return 已打开的原工作区，服务器模式自动启用。
     */
    override suspend fun restore(serverUrl: String, recoveryKeyText: String, pin: String?): WorkspaceOpenResult? =
        transfer(resume = true) {
            check(manager.sessionOrNull() == null)
            crypto.initialize()
            val credentials = recoveryCredentials(recoveryKeyText)
            val root = directories.allocate()
            // 恢复身份先随机生成；服务器返回的工作区身份仍需与本机 AEAD 认证结果交叉检查。
            val pending = newAccess(WorkspaceId(UUID.randomUUID().toString()), serverUrl.trim().trimEnd('/'), credentials, "")
            val archive = Files.createTempFile("tagloom-server-restore-", ".zip")
            var completed = false
            try {
                clientFactory(pending.serverUrl).use { client ->
                    val recovered = client.recover(credentials, pending.actorId, SyncDeviceRegistration(digest(pending.token), pending.signingPublicKeyHex))
                    check(recovered.workspaceId == recovered.snapshot.workspaceId && recovered.device.actorId == pending.actorId &&
                        recovered.device.signingPublicKeyHex == pending.signingPublicKeyHex && recovered.device.revokedAt == null)
                    client.download(recovered.snapshot, pending.actorId, pending.token, archive)
                    localAccess.establishLocalAccess(root, pin, authenticate = { secret ->
                        try {
                            val result = manager.restoreBackupWithRecovery(archive, root, recoveryKeyText, secret)
                            val session = requireNotNull(manager.sessionOrNull())
                            check(session.workspaceId == recovered.workspaceId && !result.readOnly && result.integrityIssues.isEmpty()) { "恢复副本认证失败" }
                            val access = ServerRecoveryAccess(workspaceId = recovered.workspaceId, serverUrl = pending.serverUrl,
                                actorId = pending.actorId, token = pending.token, protectedSigningSeed = pending.protectedSigningSeed,
                                signingPublicKeyHex = pending.signingPublicKeyHex, recoveryId = credentials.recoveryId,
                                recoveryTokenSha256 = credentials.tokenSha256, keyId = session.unlocked.file.publicHeader.activeKeyId.value,
                                snapshot = recovered.snapshot, registered = true, mode = WorkspaceStorageMode.SERVER,
                                uploadedManifestRevision = session.serverRecoveryRevision())
                            store.save(root, access)
                            result.toSharedWorkspaceOpenResult()
                        } catch (error: Throwable) { withContext(NonCancellable) { manager.lock() }; throw error }
                    }, onRegistrationFailure = manager::lock).also { completed = true }
                }
            } finally {
                pending.destroy()
                Files.deleteIfExists(archive)
                if (!completed) directories.discard(root)
            }
        }

    /** 取消正在进行的上传或恢复，并等待临时密文及密钥引用清理后再锁定。 */
    suspend fun cancelTransfer() {
        val running = synchronized(transferStateLock) {
            blockedSession = manager.sessionOrNull()
            transferGeneration.incrementAndGet()
            activeTransfer
        }
        running?.cancelAndJoin()
    }

    /**
     * 将传输限制在可取消的子任务内，避免锁定后仍保留会话或执行登记。
     * @param resume 显式启用服务器或恢复时允许解除同会话传输暂停。
     * @param block 本次上传或恢复的完整流程。
     * @return 此次流程的结果。
     */
    private suspend fun <T> transfer(resume: Boolean = false, block: suspend () -> T): T {
        val generation = transferGeneration.get()
        val startingSession = manager.sessionOrNull()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val transferJob = currentCoroutineContext().job
                synchronized(transferStateLock) {
                    if (generation != transferGeneration.get() || startingSession !== manager.sessionOrNull() ||
                        startingSession != null && blockedSession === startingSession && !resume) {
                        throw kotlinx.coroutines.CancellationException("sync_session_changed")
                    }
                    if (resume || startingSession !== blockedSession) blockedSession = null
                    activeTransfer = transferJob
                }
                try {
                    currentCoroutineContext().ensureActive()
                    block()
                } finally {
                    synchronized(transferStateLock) {
                        if (activeTransfer === transferJob) activeTransfer = null
                    }
                }
            }
        }
    }

    /**
     * 验证当前 manifest 的恢复槽，然后派生服务器认证材料。
     * @param text 用户输入的原 Recovery Key。
     * @return 与已认证密钥对应的用途隔离材料。
     */
    private suspend fun DesktopWorkspaceSession.verifyRecoveryKey(text: String): ServerRecoveryCredentials = sessionMutex.withLock {
        requireWritable()
        val key = try { RecoveryKeyCodec(crypto).decode(text.trim()) }
        catch (_: IllegalArgumentException) { throw ServerRecoveryException("recovery_key_invalid") }
        try {
            WorkspaceManifestService(crypto).unlockWithRecovery(WorkspaceManifestCodec.encode(unlocked.file), key).destroy()
            key.serverRecoveryCredentials(crypto)
        } finally { key.destroy() }
    }

    /**
     * 校验用户输入并派生恢复认证材料；异常只携带稳定密码学分类。
     * @param text 带校验码的 Recovery Key。
     * @return 用途隔离的派生材料。
     */
    private fun recoveryCredentials(text: String): ServerRecoveryCredentials {
        val key = try { RecoveryKeyCodec(crypto).decode(text.trim()) }
        catch (_: IllegalArgumentException) { throw ServerRecoveryException("recovery_key_invalid") }
        return try { key.serverRecoveryCredentials(crypto) } finally { key.destroy() }
    }

    /**
     * 生成新的随机设备身份及签名材料，绝不从标题、路径或 Recovery Key 派生设备私钥。
     * @param workspaceId 已知工作区身份，恢复阶段为尚未持久化的占位身份。
     * @param serverUrl 目标地址。
     * @param credentials 已经验证或带校验码的恢复派生材料。
     * @param keyId 当前工作区主密钥版本。
     * @return 短生命周期的连接材料。
     */
    private fun newAccess(workspaceId: WorkspaceId, serverUrl: String, credentials: ServerRecoveryCredentials, keyId: String): ServerRecoveryAccess {
        val pair = crypto.generateDeviceSigningKeyPair()
        val random = crypto.secureRandom(32)
        val actorId = ActorId(UUID.randomUUID().toString())
        return try {
            ServerRecoveryAccess(workspaceId = workspaceId, serverUrl = serverUrl, actorId = actorId,
                token = random.serverRecoveryHex(), protectedSigningSeed = store.protectSigningSeed(pair.privateKeySeed, actorId), signingPublicKeyHex = pair.copyPublicKey().serverRecoveryHex(),
                recoveryId = credentials.recoveryId, recoveryTokenSha256 = credentials.tokenSha256, keyId = keyId)
        } finally { random.fill(0); pair.destroyPrivateKey() }
    }

    /**
     * 返回 UI 可展示的安全状态，并检查当前工作区密钥是否已轮换。
     * @param session 当前活动会话。
     * @return 不含认证材料的状态。
     */
    private suspend fun ServerRecoveryAccess.status(session: DesktopWorkspaceSession) = WorkspaceServerRecoveryStatus(
        serverUrl, snapshot?.createdAt, !registered || keyId != session.unlocked.file.publicHeader.activeKeyId.value,
        mode, uploadedManifestRevision != session.serverRecoveryRevision(), registered,
        session.sessionMutex.withLock {
            session.syncHistoryRequired = true
            loadReplicaConflicts(session).count { it.status == com.oruke.tagloom.core.sync.ConflictStatus.UNRESOLVED }
        },
    )

    /** @return 当前解锁会话可审阅的全部完整双版本；锁定后不保留明文。 */
    override suspend fun conflicts(): List<WorkspaceSyncConflict> = withContext(Dispatchers.IO) {
        val session = requireNotNull(manager.sessionOrNull())
        session.sessionMutex.withLock { replicaConflictViews(session) }
    }

    /**
     * 重新核对审阅 revision，并把选择和已处理标记放入同一事务。
     * @param conflictId 当前认证历史中的冲突身份。
     * @param useRemote 是否选择第二份历史版本。
     * @param expectedRevision 审阅时的对象 revision，变化后拒绝旧选择。
     * @param fieldId 指定冲突字段；为空时处理全部未处理字段或旧格式完整版本。
     */
    override suspend fun resolveConflict(conflictId: String, useRemote: Boolean, expectedRevision: com.oruke.tagloom.core.model.ObjectRevision,
        fieldId: String?) = withContext(Dispatchers.IO) {
        val session = requireNotNull(manager.sessionOrNull())
        session.sessionMutex.withLock {
            session.requireWritable()
            val conflict = loadReplicaConflicts(session).single { it.conflictId == conflictId }
            DesktopReplicaTransaction(session).restore(conflict, if (useRemote) conflict.remoteSnapshot else conflict.localSnapshot, expectedRevision, fieldId)
        }
        onWorkspaceChanged()
    }

    /**
     * 计算服务器登记要求的访问文本摘要。
     * @param token 安全随机生成的访问文本。
     * @return 不可用于直接认证的摘要。
     */
    private fun digest(token: String): String = crypto.sha256(token.encodeToByteArray()).serverRecoveryHex()
}
