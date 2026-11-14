package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.MasterKeySlotService
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WorkspaceKeyService
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.PreparedTransactionRecord
import com.oruke.tagloom.core.transaction.TransactionCommitResult
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.JvmAtomicFileOperations
import com.oruke.tagloom.filesystem.jvm.JvmAtomicFilePort
import com.oruke.tagloom.filesystem.jvm.JvmPreparedContentVerifier
import com.oruke.tagloom.filesystem.jvm.JvmPreparedRecordEnvelopeCodec
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceContentVerifier
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import com.oruke.tagloom.filesystem.jvm.ManifestTransactionState
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 把全部权威密文和 manifest 作为单一事务轮换到新 Workspace Master Key。
 *
 * @param paths 当前工作区受控路径。
 * @param fileSystem 当前工作区绑定的 JVM 文件能力。
 * @param cryptoProvider 真实密码学端口。
 * @param manifestService 创建新密钥槽与新 manifest 的服务。
 * @param atomicFiles 提供可注入故障的原子文件能力。
 */
class DesktopWorkspaceKeyRotationService(
    private val paths: JvmWorkspacePaths,
    private val fileSystem: JvmWorkspaceFileSystem,
    private val cryptoProvider: CryptoProvider,
    private val manifestService: WorkspaceManifestService,
    private val atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations(),
) {
    init {
        require(fileSystem.workspaceRoot == paths.root) { "文件能力根目录与密钥轮换路径不一致" }
    }
    /** 负责旧信封解密与新信封加密。 */
    private val envelopeService: EnvelopeCryptoService = EnvelopeCryptoService(cryptoProvider)

    /** 生成新主密钥和 Recovery Key。 */
    private val keyService: WorkspaceKeyService = WorkspaceKeyService(cryptoProvider)

    /** 生成新 Recovery Key 的主密钥槽服务。 */
    private val slotService: MasterKeySlotService = MasterKeySlotService(cryptoProvider, keyService)

    /**
     * 生成全部新信封并以新 manifest 作为唯一原子确认点提交。
     *
     * @param unlocked 当前旧主密钥会话。
     * @param newKeyId 新主密钥版本 ID。
     * @param newPassword 轮换后的密码。
     * @param newParameters 新密码槽 KDF 参数。
     * @param passwordSlotId 新密码槽 ID。
     * @param recoverySlotId 新恢复槽 ID。
     * @param transactionId 轮换事务 ID。
     * @return 新已解锁 manifest、一次性 Recovery Key 和提交结果。
     */
    suspend fun rotate(
        unlocked: UnlockedWorkspaceManifest,
        newKeyId: KeyId,
        newPassword: String,
        newParameters: Argon2idParameters,
        passwordSlotId: String,
        recoverySlotId: String,
        transactionId: TransactionId,
    ): DesktopWorkspaceKeyRotationResult {
        cryptoProvider.initialize()
        val newMasterKey = keyService.generateRandomKey(SecretKeyKind.WORKSPACE_MASTER)
        val newRecoveryKey = slotService.generateRecoveryKey()
        try {
            // 步骤 1：在内存中认证旧信封并生成同身份、同 revision 的新密钥信封。
            val rotated = unlocked.payload.objects.sortedBy { record -> record.path.value }.map { record ->
                rotateArtifact(unlocked, record, newKeyId, newMasterKey)
            }

            // 步骤 2：新 manifest 只引用全部新摘要，并以新密码和 Recovery Key 包装新主密钥。
            val encryptedManifest = manifestService.rotateMasterKey(
                unlocked = unlocked,
                newKeyId = newKeyId,
                newWorkspaceMasterKey = newMasterKey,
                newRecoveryKey = newRecoveryKey,
                newPassword = newPassword,
                newParameters = newParameters,
                passwordSlotId = passwordSlotId,
                recoverySlotId = recoverySlotId,
                transactionId = transactionId,
                rotatedObjects = rotated.map(RotatedArtifact::record),
            )

            // 步骤 3：恢复验证器使用新密钥，确保 prepared 只能完整完成或回滚到旧 manifest。
            val oldVerifier = JvmWorkspaceContentVerifier(
                workspaceId = unlocked.file.publicHeader.workspaceId,
                activeKeyId = unlocked.file.publicHeader.activeKeyId,
                workspaceMasterKey = unlocked.workspaceMasterKey,
                envelopeCryptoService = envelopeService,
                workspaceManifestService = manifestService,
            )
            val newVerifier = JvmWorkspaceContentVerifier(
                workspaceId = unlocked.file.publicHeader.workspaceId,
                activeKeyId = newKeyId,
                workspaceMasterKey = newMasterKey,
                envelopeCryptoService = envelopeService,
                workspaceManifestService = manifestService,
            )
            val coordinator = TransactionCoordinator(
                storage = fileSystem.createTransactionStorage(
                    contentVerifier = RotationWorkspaceContentVerifier(oldVerifier, newVerifier),
                    atomicFilesOverride = atomicFiles,
                ),
                derivedStateUpdater = RotationDerivedStateUpdater,
            )
            val commit = coordinator.commit(
                EncryptedTransactionPlan(
                    transactionId = transactionId,
                    baseManifestRevision = unlocked.payload.revision,
                    targetManifestRevision = unlocked.payload.revision + 1L,
                    artifacts = rotated.map(RotatedArtifact::artifact),
                    encryptedManifest = encryptedManifest,
                    manifestSha256 = cryptoProvider.sha256(encryptedManifest).toHex(),
                ),
            )
            val file = WorkspaceManifestCodec.decode(encryptedManifest)
            val payload = manifestService.inspectWithMasterKey(encryptedManifest, newMasterKey)
            return DesktopWorkspaceKeyRotationResult(
                unlocked = UnlockedWorkspaceManifest(file, payload, newMasterKey),
                recoveryKey = newRecoveryKey,
                commit = commit,
            )
        } catch (error: Throwable) {
            val committed = recoverCommittedRotation(newKeyId, transactionId, newMasterKey)
            if (committed != null) {
                return DesktopWorkspaceKeyRotationResult(
                    unlocked = committed,
                    recoveryKey = newRecoveryKey,
                    commit = TransactionCommitResult(transactionId, derivedStateUpdated = false),
                )
            }
            newMasterKey.destroy()
            newRecoveryKey.destroy()
            throw error
        }
    }

    /**
     * 在提交后诊断步骤失败时检查新 manifest 是否已经成为权威确认点。
     *
     * @param newKeyId 预期新活动 keyId。
     * @param transactionId 轮换事务 ID。
     * @param newMasterKey 新主密钥。
     * @return 已确认时返回可接管会话，否则为空。
     */
    private suspend fun recoverCommittedRotation(
        newKeyId: KeyId,
        transactionId: TransactionId,
        newMasterKey: SecretKeyMaterial,
    ): UnlockedWorkspaceManifest? = runCatching {
        val manifestBytes = Files.readAllBytes(paths.manifest)
        val file = WorkspaceManifestCodec.decode(manifestBytes)
        if (file.publicHeader.activeKeyId != newKeyId) {
            return@runCatching null
        }
        val payload = manifestService.inspectWithMasterKey(manifestBytes, newMasterKey)
        if (payload.lastCommittedTransactionId != transactionId || transactionId !in payload.committedTransactionIds) {
            return@runCatching null
        }
        UnlockedWorkspaceManifest(file, payload, newMasterKey)
    }.getOrNull()

    /**
     * 解密一个旧对象并以新主密钥重新生成完整信封。
     *
     * @param unlocked 当前旧主密钥会话。
     * @param record 当前 manifest 对象记录。
     * @param newKeyId 新主密钥版本。
     * @param newMasterKey 新主密钥。
     * @return 路径不变的新密文 artifact 和摘要记录。
     */
    private suspend fun rotateArtifact(
        unlocked: UnlockedWorkspaceManifest,
        record: ManifestObjectRecord,
        newKeyId: KeyId,
        newMasterKey: SecretKeyMaterial,
    ): RotatedArtifact {
        require(record.keyId == unlocked.file.publicHeader.activeKeyId) { "v1 轮换前对象 keyId 必须统一" }
        val role = RotationArtifactRole.from(record)
        val source = paths.target(record.path)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "轮换对象密文缺失" }
        val sourceBytes = Files.readAllBytes(source)
        check(cryptoProvider.sha256(sourceBytes).toHex() == record.encryptedSha256) { "轮换对象摘要不匹配" }
        val oldEnvelope = EncryptionEnvelopeCodec.decode(sourceBytes)
        val identity = EnvelopeIdentity(
            workspaceId = unlocked.file.publicHeader.workspaceId,
            objectType = role.objectType,
            objectId = record.objectId,
            revision = record.revision,
        )
        val plaintext = envelopeService.decrypt(
            envelope = oldEnvelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = identity,
                expectedKeyId = record.keyId,
                expectedWrappingKeyPurpose = role.purpose,
            ),
            workspaceMasterKey = unlocked.workspaceMasterKey,
        )
        val encrypted = try {
            EncryptionEnvelopeCodec.encode(
                envelopeService.encrypt(
                    request = EnvelopeEncryptionRequest(
                        identity = identity,
                        keyId = newKeyId,
                        wrappingKeyPurpose = role.purpose,
                        plaintext = plaintext,
                    ),
                    workspaceMasterKey = newMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
        val sha256 = cryptoProvider.sha256(encrypted).toHex()
        return RotatedArtifact(
            artifact = EncryptedTransactionArtifact(record.path, encrypted, sha256),
            record = record.copy(encryptedSha256 = sha256, keyId = newKeyId),
        )
    }

    /** 把摘要编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 返回轮换完成后的新会话密钥、Recovery Key 和事务状态。
 */
class DesktopWorkspaceKeyRotationResult(
    /** 取得新主密钥所有权的已认证 manifest。 */
    val unlocked: UnlockedWorkspaceManifest,
    /** 必须只展示一次的新 Recovery Key。 */
    val recoveryKey: SecretKeyMaterial,
    /** 原子事务提交结果。 */
    val commit: TransactionCommitResult,
) {
    /** 在调用方无法接管结果时清零全部新秘密。 */
    fun destroy() {
        unlocked.destroy()
        recoveryKey.destroy()
    }
}

/**
 * 保存单个对象轮换后的密文与 manifest 记录。
 */
private data class RotatedArtifact(
    /** 新主密钥信封 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** 新密文摘要记录。 */
    val record: ManifestObjectRecord,
)

/**
 * 描述 manifest 对象类别对应的信封类型与 wrapping key 用途。
 */
private data class RotationArtifactRole(
    /** 信封公开对象类型。 */
    val objectType: String,
    /** 用途隔离 wrapping key。 */
    val purpose: WrappingKeyPurpose,
) {
    companion object {
        /**
         * 从已认证 manifest 类别返回固定信封角色。
         *
         * @param record manifest 对象记录；历史子类型还需要路径判定。
         * @return 对应信封角色。
         */
        fun from(record: ManifestObjectRecord): RotationArtifactRole = when (record.kind) {
            ManifestObjectKind.NOTE -> RotationArtifactRole("note-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.TAG -> RotationArtifactRole("tag-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.PROPERTY -> RotationArtifactRole("property-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.SAVED_QUERY -> RotationArtifactRole("saved-query-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.DATABASE -> RotationArtifactRole("database-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.VIEW -> RotationArtifactRole("view-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.WORKSPACE_CONFIG -> RotationArtifactRole("workspace-config-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.AGENT_THREAD -> RotationArtifactRole("agent-thread-snapshot", WrappingKeyPurpose.SNAPSHOT)
            ManifestObjectKind.BLOB -> RotationArtifactRole("blob", WrappingKeyPurpose.BLOB)
            ManifestObjectKind.CHANGE -> RotationArtifactRole("object-change", WrappingKeyPurpose.CHANGE)
            ManifestObjectKind.HISTORY -> if (record.path.value.startsWith("history/checkpoints/")) {
                RotationArtifactRole("change-checkpoint", WrappingKeyPurpose.AUDIT)
            } else {
                RotationArtifactRole("audit-record", WrappingKeyPurpose.AUDIT)
            }
        }
    }
}

/**
 * 轮换事务提交后不更新旧密钥派生索引；新会话会删除并重建索引。
 */
private object RotationDerivedStateUpdater : DerivedStateUpdater {
    /**
     * 接受已确认轮换事务但不访问旧索引。
     *
     * @param transactionId 已确认轮换事务。
     */
    override suspend fun update(transactionId: TransactionId) = Unit
}

/**
 * 在轮换确认窗口区分旧确认点与新写入，避免用新 WMK 认证尚未切换的旧 manifest。
 *
 * @param oldVerifier 只用于识别轮换前当前 manifest。
 * @param newVerifier 只用于新对象、待提交 manifest，并优先识别已经提交的新 manifest。
 */
private class RotationWorkspaceContentVerifier(
    private val oldVerifier: JvmPreparedContentVerifier,
    private val newVerifier: JvmPreparedContentVerifier,
) : JvmPreparedContentVerifier {
    /**
     * 同时使用旧/新主密钥加密 prepared 控制记录，覆盖 manifest 切换前后的恢复窗口。
     *
     * @param transactionId 轮换事务目录使用的稳定标识。
     * @param plaintext 规范化 prepared 记录明文。
     * @return 包含两份独立加密信封的规范容器字节。
     */
    override suspend fun encryptPreparedRecord(
        transactionId: TransactionId,
        plaintext: ByteArray,
    ): ByteArray {
        val oldEnvelope = JvmPreparedRecordEnvelopeCodec.decode(
            oldVerifier.encryptPreparedRecord(transactionId, plaintext),
        )
        val newEnvelope = JvmPreparedRecordEnvelopeCodec.decode(
            newVerifier.encryptPreparedRecord(transactionId, plaintext),
        )
        return JvmPreparedRecordEnvelopeCodec.encode(oldEnvelope + newEnvelope)
    }

    /**
     * 优先使用新密钥解密 prepared 记录，失败后回退旧密钥。
     *
     * @param transactionId 轮换事务目录使用的稳定标识。
     * @param encryptedBytes staging 中的加密容器字节。
     * @return 认证后的 prepared 记录明文。
     */
    override suspend fun decryptPreparedRecord(
        transactionId: TransactionId,
        encryptedBytes: ByteArray,
    ): ByteArray = runCatching {
        newVerifier.decryptPreparedRecord(transactionId, encryptedBytes)
    }.getOrElse {
        oldVerifier.decryptPreparedRecord(transactionId, encryptedBytes)
    }

    /**
     * 轮换 artifact 必须全部由新密钥验证器认证。
     *
     * @param record artifact 恢复记录。
     * @param encryptedBytes 新主密钥信封。
     * @return 新信封身份、AEAD 和 schema 全部有效时为 true。
     */
    override suspend fun verifyArtifact(
        record: PreparedArtifactRecord,
        encryptedBytes: ByteArray,
    ): Boolean = newVerifier.verifyArtifact(record, encryptedBytes)

    /**
     * 待提交 manifest 必须绑定新主密钥和本次轮换事务。
     *
     * @param record prepared 事务记录。
     * @param encryptedBytes 新主密钥 manifest。
     * @return 新 manifest 完整有效时为 true。
     */
    override suspend fun verifyNextManifest(
        record: PreparedTransactionRecord,
        encryptedBytes: ByteArray,
    ): Boolean = newVerifier.verifyNextManifest(record, encryptedBytes)

    /**
     * 优先识别已经切换的新确认点；尚未切换时退回旧密钥认证当前 manifest。
     *
     * @param encryptedBytes 当前磁盘 manifest。
     * @return 已认证事务 revision 和近期确认集合。
     */
    override suspend fun inspectManifest(encryptedBytes: ByteArray): ManifestTransactionState =
        runCatching { newVerifier.inspectManifest(encryptedBytes) }
            .getOrElse { oldVerifier.inspectManifest(encryptedBytes) }
}
