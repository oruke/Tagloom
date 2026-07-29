package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ConflictRecord
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.transaction.ChangeLogCompactionPolicy
import com.oruke.tagloom.core.transaction.ChangeLogCompactionRequest
import com.oruke.tagloom.core.transaction.ChangeLogCompactionResult
import com.oruke.tagloom.core.transaction.ChangeLogCompactionService
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filestore.jvm.JvmAtomicFileOperations
import com.oruke.tagloom.filestore.jvm.JvmAtomicFilePort
import com.oruke.tagloom.filestore.jvm.JvmWorkspaceContentVerifier
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.KSerializer

/**
 * 从 JVM 权威密文加载压缩证据、验证全部快照，并在确认后清理无引用 Change 文件。
 */
class DesktopChangeLogCompactionService(
    /** 当前工作区受控路径。 */
    private val paths: JvmWorkspacePaths,
    /** 提供摘要和真实 AEAD。 */
    private val cryptoProvider: CryptoProvider,
    /** 解密 Change 和冲突证据并加密 checkpoint。 */
    private val envelopeService: EnvelopeCryptoService,
    /** 认证并生成下一版 manifest。 */
    private val manifestService: WorkspaceManifestService,
    /** 提交 checkpoint 事务并更新派生索引。 */
    private val transactionCoordinator: TransactionCoordinator,
    /** 在 manifest 确认后持久化删除无引用密文。 */
    private val atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations(),
) {
    /**
     * 对真实工作区执行一次受策略约束的 Change Log 压缩。
     *
     * @param unlocked 当前认证 manifest 与会话主密钥。
     * @param checkpointId 新 checkpoint 不透明 ID。
     * @param transactionId 压缩事务 ID。
     * @param createdAt checkpoint 创建时间。
     * @param policy 撤销保留、归档和设备确认策略。
     * @return 已确认 checkpoint、manifest 和清理路径。
     */
    suspend fun compact(
        unlocked: UnlockedWorkspaceManifest,
        checkpointId: String,
        transactionId: com.oruke.tagloom.core.model.TransactionId,
        createdAt: String,
        policy: ChangeLogCompactionPolicy,
    ): ChangeLogCompactionResult {
        val verifier = JvmWorkspaceContentVerifier(
            workspaceId = unlocked.file.publicHeader.workspaceId,
            activeKeyId = unlocked.file.publicHeader.activeKeyId,
            workspaceMasterKey = unlocked.workspaceMasterKey,
            envelopeCryptoService = envelopeService,
            workspaceManifestService = manifestService,
        )

        // 步骤 1：逐个验证当前快照摘要、AEAD 和 schema，任何单对象损坏都阻止删除历史。
        val snapshotsValidated = unlocked.payload.objects
            .filterNot { record -> record.kind == ManifestObjectKind.CHANGE || record.kind == ManifestObjectKind.HISTORY }
            .all { record -> verifyRecord(verifier, record) }
        val changes = unlocked.payload.objects
            .filter { record -> record.kind == ManifestObjectKind.CHANGE }
            .map { record ->
                decryptJson(unlocked, record, CHANGE_OBJECT_TYPE, WrappingKeyPurpose.CHANGE, ObjectChange.serializer())
            }
        val conflicts = unlocked.payload.objects
            .filter { record -> record.kind == ManifestObjectKind.HISTORY && record.path.value.startsWith("history/conflicts/") }
            .map { record ->
                decryptJson(unlocked, record, AUDIT_OBJECT_TYPE, WrappingKeyPurpose.AUDIT, ConflictRecord.serializer())
            }

        // 步骤 2：核心服务只接收已经由 manifest 和信封认证的模型，生成并提交 checkpoint。
        val result = ChangeLogCompactionService(
            cryptoProvider = cryptoProvider,
            envelopeCryptoService = envelopeService,
            manifestService = manifestService,
            transactionCoordinator = transactionCoordinator,
        ).compact(
            unlocked = unlocked,
            request = ChangeLogCompactionRequest(
                checkpointId = checkpointId,
                transactionId = transactionId,
                createdAt = createdAt,
                changes = changes,
                conflicts = conflicts,
                snapshotsValidated = snapshotsValidated,
                policy = policy,
            ),
        )

        // 步骤 3：先认证新 manifest 已不引用目标，再物理删除旧 Change；中断只会留下无引用密文。
        val nextPayload = manifestService.inspectWithMasterKey(result.encryptedManifest, unlocked.workspaceMasterKey)
        val referencedPaths = nextPayload.objects.map(ManifestObjectRecord::path).toSet()
        check(result.removedPaths.none(referencedPaths::contains)) { "压缩后 manifest 仍引用待删除 Change" }
        result.removedPaths.forEach { path -> atomicFiles.deleteDurably(paths.target(path)) }
        return result
    }

    /**
     * 校验一个 manifest 对象的密文摘要、路径身份、AEAD 和 schema。
     *
     * @param verifier 当前主密钥内容验证器。
     * @param record manifest 对象记录。
     * @return 全部验证通过时为 true。
     */
    private suspend fun verifyRecord(
        verifier: JvmWorkspaceContentVerifier,
        record: ManifestObjectRecord,
    ): Boolean {
        val source = paths.target(record.path)
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val encrypted = Files.readAllBytes(source)
        if (cryptoProvider.sha256(encrypted).toHex() != record.encryptedSha256) {
            return false
        }
        return verifier.verifyArtifact(
            PreparedArtifactRecord(
                targetPath = record.path,
                stagedFileName = "verification.enc",
                expectedSha256 = record.encryptedSha256,
                previousVersionExists = false,
            ),
            encrypted,
        )
    }

    /**
     * 按 manifest 记录身份解密并解析一个 Change 或冲突证据。
     *
     * @param T 目标协议模型。
     * @param unlocked 当前认证 manifest 与主密钥。
     * @param record 当前认证 manifest 记录。
     * @param objectType 信封固定对象类型。
     * @param purpose wrapping key 用途。
     * @param serializer 明文协议 serializer。
     * @return 已通过 AEAD 和 JSON schema 的模型。
     */
    private suspend fun <T> decryptJson(
        unlocked: UnlockedWorkspaceManifest,
        record: ManifestObjectRecord,
        objectType: String,
        purpose: WrappingKeyPurpose,
        serializer: KSerializer<T>,
    ): T {
        val encrypted = Files.readAllBytes(paths.target(record.path))
        check(cryptoProvider.sha256(encrypted).toHex() == record.encryptedSha256) { "压缩证据摘要不匹配" }
        val plaintext = envelopeService.decrypt(
            envelope = EncryptionEnvelopeCodec.decode(encrypted),
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = unlocked.file.publicHeader.workspaceId,
                    objectType = objectType,
                    objectId = record.objectId,
                    revision = record.revision,
                ),
                expectedKeyId = record.keyId,
                expectedWrappingKeyPurpose = purpose,
            ),
            workspaceMasterKey = unlocked.workspaceMasterKey,
        )
        return try {
            CanonicalJson.decodeFromString(serializer, plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    /** 把摘要编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        /** Change 信封固定对象类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"

        /** 冲突证据信封固定对象类型。 */
        const val AUDIT_OBJECT_TYPE: String = "audit-record"
    }
}
