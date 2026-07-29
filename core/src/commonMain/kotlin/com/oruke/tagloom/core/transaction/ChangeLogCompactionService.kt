package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import kotlinx.serialization.builtins.ListSerializer

/**
 * 把安全压缩 checkpoint 与删除 Change 后的 manifest 作为单一原子事务提交。
 */
class ChangeLogCompactionService(
    /** 提供摘要和算法初始化。 */
    private val cryptoProvider: CryptoProvider,
    /** 生成 checkpoint 的 AUDIT 用途信封。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 生成移除 Change 后的下一版认证 manifest。 */
    private val manifestService: WorkspaceManifestService,
    /** 以 manifest 为唯一确认点提交 checkpoint。 */
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 验证压缩证据、生成快照根摘要并确认 checkpoint。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @param request 包含保留证据和已认证 Change 的压缩请求。
     * @return 已确认 checkpoint、manifest 和确认后清理路径。
     */
    suspend fun compact(
        unlocked: UnlockedWorkspaceManifest,
        request: ChangeLogCompactionRequest,
    ): ChangeLogCompactionResult {
        cryptoProvider.initialize()
        val manifestChangeRecords = unlocked.payload.objects.filter { record ->
            record.kind == ManifestObjectKind.CHANGE
        }
        require(request.changes.map { change -> change.changeId.value }.toSet() ==
            manifestChangeRecords.map(ManifestObjectRecord::objectId).toSet()) {
            "压缩请求必须覆盖 manifest 中全部 Change"
        }
        val decision = ChangeLogCompactionPlanner.plan(request)
        val snapshots = createSnapshotEntries(unlocked)
        val coveredTransactionId = requireNotNull(unlocked.payload.lastCommittedTransactionId) {
            "没有已确认事务，不能创建 Change checkpoint"
        }
        val checkpoint = ChangeLogCheckpoint(
            checkpointId = request.checkpointId,
            coveredTransactionId = coveredTransactionId,
            coveredManifestRevision = unlocked.payload.revision,
            snapshotHashRoot = snapshotHashRoot(snapshots),
            snapshots = snapshots,
            compactedChangeIds = decision.removableChanges.map { change -> change.changeId },
            createdAt = request.createdAt,
        )

        // 步骤 1：checkpoint 先进入用途隔离的 AUDIT 信封，不产生明文维护文件。
        val checkpointPath = WorkspaceRelativePath(
            "history/checkpoints/${request.checkpointId}.audit.enc",
        )
        val checkpointRevision = unlocked.payload.revision.toString()
        val checkpointArtifact = encryptCheckpoint(
            unlocked = unlocked,
            checkpoint = checkpoint,
            targetPath = checkpointPath,
            revision = checkpointRevision,
        )
        val removedPaths = decision.removableChanges.map { change ->
            WorkspaceRelativePath("changes/${change.changeId.value}.change.enc")
        }.toSet()

        // 步骤 2：下一 manifest 同时引用 checkpoint 并移除被覆盖 Change，形成唯一压缩确认点。
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = request.transactionId,
            upserts = listOf(checkpointArtifact.record),
            removedPaths = removedPaths,
        )

        // 步骤 3：事务只发布新 checkpoint；旧 Change 文件在 manifest 确认后作为无引用密文清理。
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = request.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = listOf(checkpointArtifact.artifact),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        return ChangeLogCompactionResult(checkpoint, encryptedManifest, commit, removedPaths)
    }

    /**
     * 从 manifest 提取不含 Change 和历史证据的当前权威对象摘要。
     *
     * @param unlocked 当前认证 manifest。
     * @return 稳定排序的 checkpoint 快照条目。
     */
    private fun createSnapshotEntries(unlocked: UnlockedWorkspaceManifest): List<ChangeCheckpointSnapshot> =
        unlocked.payload.objects
            .filterNot { record -> record.kind == ManifestObjectKind.CHANGE || record.kind == ManifestObjectKind.HISTORY }
            .map { record ->
                ChangeCheckpointSnapshot(record.kind, record.objectId, record.revision, record.encryptedSha256)
            }
            .sortedWith(compareBy({ entry -> entry.kind.name }, ChangeCheckpointSnapshot::objectId))
            .also { entries -> require(entries.isNotEmpty()) { "checkpoint 没有可覆盖的权威快照" } }

    /**
     * 对稳定快照摘要列表计算 checkpoint 根摘要。
     *
     * @param snapshots 已稳定排序的快照条目。
     * @return 64 位小写十六进制摘要。
     */
    private fun snapshotHashRoot(snapshots: List<ChangeCheckpointSnapshot>): String {
        val bytes = CanonicalJson.encodeToString(
            ListSerializer(ChangeCheckpointSnapshot.serializer()),
            snapshots,
        ).encodeToByteArray()
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 加密 checkpoint 并生成 manifest 摘要记录。
     *
     * @param unlocked 当前认证 manifest 与主密钥。
     * @param checkpoint 待保护 checkpoint。
     * @param targetPath 最终受控历史路径。
     * @param revision checkpoint 覆盖的 manifest revision。
     * @return 可直接提交的密文 artifact。
     */
    private suspend fun encryptCheckpoint(
        unlocked: UnlockedWorkspaceManifest,
        checkpoint: ChangeLogCheckpoint,
        targetPath: WorkspaceRelativePath,
        revision: String,
    ): EncryptedCheckpointArtifact {
        val plaintext = CanonicalJson.encodeToString(ChangeLogCheckpoint.serializer(), checkpoint).encodeToByteArray()
        val encrypted = try {
            EncryptionEnvelopeCodec.encode(
                envelopeCryptoService.encrypt(
                    request = EnvelopeEncryptionRequest(
                        identity = EnvelopeIdentity(
                            workspaceId = unlocked.file.publicHeader.workspaceId,
                            objectType = CHECKPOINT_OBJECT_TYPE,
                            objectId = checkpoint.checkpointId,
                            revision = revision,
                        ),
                        keyId = unlocked.file.publicHeader.activeKeyId,
                        wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                        plaintext = plaintext,
                    ),
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
        val encryptedSha256 = sha256(encrypted)
        return EncryptedCheckpointArtifact(
            artifact = EncryptedTransactionArtifact(targetPath, encrypted, encryptedSha256),
            record = ManifestObjectRecord(
                kind = ManifestObjectKind.HISTORY,
                objectId = checkpoint.checkpointId,
                revision = revision,
                path = targetPath,
                encryptedSha256 = encryptedSha256,
                keyId = unlocked.file.publicHeader.activeKeyId,
            ),
        )
    }

    /**
     * 计算字节数组 SHA-256。
     *
     * @param bytes 待摘要字节。
     * @return 小写十六进制摘要。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    companion object {
        /** Change checkpoint 信封固定对象类型。 */
        const val CHECKPOINT_OBJECT_TYPE: String = "change-checkpoint"
    }
}

/**
 * 保存 checkpoint 加密输出。
 */
private data class EncryptedCheckpointArtifact(
    /** 事务 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** manifest 摘要记录。 */
    val record: ManifestObjectRecord,
)
