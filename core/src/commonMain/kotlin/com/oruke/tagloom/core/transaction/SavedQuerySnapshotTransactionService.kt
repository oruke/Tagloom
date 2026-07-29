package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 统一编排 SavedQuery 的 Change、加密快照和 manifest 原子确认。
 *
 * @param cryptoProvider 提供摘要和初始化的密码学端口。
 * @param envelopeCryptoService 生成用途隔离的查询与 Change 信封。
 * @param manifestService 生成下一版认证 manifest。
 * @param transactionCoordinator 以 manifest 为确认点提交文件事务。
 */
class SavedQuerySnapshotTransactionService(
    private val cryptoProvider: CryptoProvider,
    private val envelopeCryptoService: EnvelopeCryptoService,
    private val manifestService: WorkspaceManifestService,
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 创建、更新或删除一个保存查询。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 完整查询事务上下文。
     * @return 已确认快照、Change、manifest 和提交结果。
     */
    suspend fun commit(
        unlocked: UnlockedWorkspaceManifest,
        request: SavedQuerySnapshotTransactionRequest,
    ): SavedQuerySnapshotTransactionResult {
        cryptoProvider.initialize()
        val currentRecord = validateCurrentState(unlocked, request)
        val revision = ObjectRevision(request.actorId, request.actorSequence)
        val revisionText = revision.toProtocolRevision()
        val change = createChange(
            unlocked,
            request,
            canonicalSha256(SavedQuery.serializer(), request.query),
            revisionText,
        )
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = request.previousSnapshot?.header?.schemaVersion ?: SAVED_QUERY_SCHEMA_VERSION,
                objectId = request.objectId,
                objectType = ObjectType.SAVED_QUERY,
                revision = revision,
                lastChangeId = change.changeId,
                previousSnapshotHash = currentRecord?.encryptedSha256,
                createdAt = request.previousSnapshot?.header?.createdAt ?: request.committedAt,
                updatedAt = request.committedAt,
                deleted = request.deleted,
            ),
            payload = request.query,
        )

        // 步骤 1：查询快照和 Change 在进入 staging 前分别加密。
        val snapshotArtifact = encryptArtifact(
            unlocked = unlocked,
            targetPath = WorkspaceRelativePath("queries/${request.objectId.value}.query.enc"),
            kind = ManifestObjectKind.SAVED_QUERY,
            manifestObjectId = request.objectId.value,
            revision = revisionText,
            envelopeObjectType = SavedQuerySnapshotEnvelopeService.SAVED_QUERY_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = request.objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(SavedQuery.serializer()),
            value = snapshot,
        )
        val changeArtifact = encryptArtifact(
            unlocked = unlocked,
            targetPath = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc"),
            kind = ManifestObjectKind.CHANGE,
            manifestObjectId = change.changeId.value,
            revision = revisionText,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )

        // 步骤 2：下一 manifest 同时引用查询快照和 Change，避免无历史对象可见。
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = request.transactionId,
            upserts = listOf(snapshotArtifact.record, changeArtifact.record),
            removedPaths = emptySet(),
        )

        // 步骤 3：协调器发布密文并以 manifest 原子替换确认业务状态。
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = request.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = listOf(snapshotArtifact.artifact, changeArtifact.artifact),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        return SavedQuerySnapshotTransactionResult(snapshot, change, encryptedManifest, commit)
    }

    /**
     * 校验旧查询快照与当前 manifest 的一致性。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 查询写入请求。
     * @return 当前查询 manifest 记录；创建时为空。
     */
    private fun validateCurrentState(
        unlocked: UnlockedWorkspaceManifest,
        request: SavedQuerySnapshotTransactionRequest,
    ): ManifestObjectRecord? {
        val record = unlocked.payload.objects.singleOrNull { candidate ->
            candidate.kind == ManifestObjectKind.SAVED_QUERY && candidate.objectId == request.objectId.value
        }
        if (request.previousSnapshot == null) {
            require(record == null) { "SavedQuery 已存在，不能按创建请求覆盖" }
            require(!request.deleted) { "不存在的 SavedQuery 不能直接创建 tombstone" }
            return null
        }
        require(record != null) { "manifest 缺少待更新 SavedQuery" }
        require(record.revision == request.previousSnapshot.header.revision.toProtocolRevision()) {
            "SavedQuery 旧快照 revision 已落后"
        }
        require(
            request.actorId != request.previousSnapshot.header.revision.actorId ||
                request.actorSequence > request.previousSnapshot.header.revision.sequence,
        ) { "同一 Actor 的 SavedQuery revision 必须严格递增" }
        return record
    }

    /**
     * 创建包含完整查询替换语义的加密 Change。
     *
     * @param unlocked 当前 manifest，用于识别上一 Change 是否已被 checkpoint 压缩。
     * @param request 查询写入请求。
     * @param querySha256 规范查询摘要。
     * @param revisionText 目标 revision。
     * @return 可与快照一同确认的 Change。
     */
    private fun createChange(
        unlocked: UnlockedWorkspaceManifest,
        request: SavedQuerySnapshotTransactionRequest,
        querySha256: String,
        revisionText: String,
    ): ObjectChange {
        // checkpoint 已完整覆盖快照时允许新 Change 形成新根，避免引用已安全压缩的父节点。
        val parentIds = request.previousSnapshot?.header?.lastChangeId
            ?.takeIf { lastChangeId -> unlocked.payload.objects.any { record ->
                record.kind == ManifestObjectKind.CHANGE && record.objectId == lastChangeId.value
            } }
            ?.let(::listOf)
            .orEmpty()
        val kind = when {
            request.previousSnapshot == null -> ChangeKind.CREATE
            request.deleted -> ChangeKind.DELETE
            else -> ChangeKind.UPDATE
        }
        val seed = SavedQueryChangeIdentitySeed(
            objectId = request.objectId.value,
            parentChangeIds = parentIds.map(ChangeId::value),
            actorId = request.actorId.value,
            actorSequence = request.actorSequence,
            physicalTimeMillis = request.hybridLogicalTime.physicalTimeMillis,
            logicalCounter = request.hybridLogicalTime.logicalCounter,
            transactionId = request.transactionId.value,
            kind = kind.name,
            querySha256 = querySha256,
            targetSnapshotRevision = revisionText,
        )
        return ObjectChange(
            changeId = ChangeId(canonicalSha256(SavedQueryChangeIdentitySeed.serializer(), seed)),
            objectId = request.objectId,
            parentChangeIds = parentIds,
            actorId = request.actorId,
            hybridLogicalTime = request.hybridLogicalTime,
            baseSnapshotRevision = request.previousSnapshot?.header?.revision,
            transactionId = request.transactionId,
            kind = kind,
            operations = listOf(
                ChangeOperation(
                    type = if (request.deleted) "delete-saved-query" else "replace-saved-query",
                    payload = CanonicalJson.format.encodeToJsonElement(SavedQuery.serializer(), request.query),
                ),
            ),
        )
    }

    /**
     * 加密一个协议对象并生成事务 artifact 与 manifest 记录。
     *
     * @param T 明文值类型。
     * @param unlocked 当前解锁工作区。
     * @param targetPath 最终受控路径。
     * @param kind manifest 对象类别。
     * @param manifestObjectId manifest 对象 ID。
     * @param revision 协议 revision。
     * @param envelopeObjectType 信封对象类型。
     * @param envelopeObjectId 信封对象 ID。
     * @param purpose wrapping key 用途。
     * @param serializer 明文 serializer。
     * @param value 待加密值。
     * @return 密文 artifact 与摘要记录。
     */
    private suspend fun <T> encryptArtifact(
        unlocked: UnlockedWorkspaceManifest,
        targetPath: WorkspaceRelativePath,
        kind: ManifestObjectKind,
        manifestObjectId: String,
        revision: String,
        envelopeObjectType: String,
        envelopeObjectId: String,
        purpose: WrappingKeyPurpose,
        serializer: KSerializer<T>,
        value: T,
    ): SavedQueryEncryptedArtifact {
        val plaintext = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        val encrypted = try {
            EncryptionEnvelopeCodec.encode(
                envelopeCryptoService.encrypt(
                    request = EnvelopeEncryptionRequest(
                        identity = EnvelopeIdentity(
                            workspaceId = unlocked.file.publicHeader.workspaceId,
                            objectType = envelopeObjectType,
                            objectId = envelopeObjectId,
                            revision = revision,
                        ),
                        keyId = unlocked.file.publicHeader.activeKeyId,
                        wrappingKeyPurpose = purpose,
                        plaintext = plaintext,
                    ),
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
        val encryptedSha256 = sha256(encrypted)
        return SavedQueryEncryptedArtifact(
            artifact = EncryptedTransactionArtifact(targetPath, encrypted, encryptedSha256),
            record = ManifestObjectRecord(
                kind = kind,
                objectId = manifestObjectId,
                revision = revision,
                path = targetPath,
                encryptedSha256 = encryptedSha256,
                keyId = unlocked.file.publicHeader.activeKeyId,
            ),
        )
    }

    /**
     * 计算规范 JSON 摘要并清零短期编码。
     *
     * @param T 值类型。
     * @param serializer 值 serializer。
     * @param value 待摘要值。
     * @return 小写十六进制 SHA-256。
     */
    private fun <T> canonicalSha256(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val bytes = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 计算字节摘要。
     *
     * @param bytes 待摘要字节。
     * @return 小写十六进制 SHA-256。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).toHex()

    /** 把对象 revision 转换为 `actorId:sequence`。 */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    /** 把摘要字节编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        /** 当前保存查询 schema 版本。 */
        const val SAVED_QUERY_SCHEMA_VERSION: Int = 1

        /** Change 信封固定对象类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"
    }
}

/**
 * 保存 SavedQuery 加密输出。
 */
private data class SavedQueryEncryptedArtifact(
    /** 事务 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** manifest 摘要记录。 */
    val record: ManifestObjectRecord,
)

/**
 * 保存计算 SavedQuery Change ID 的非循环输入。
 */
@Serializable
private data class SavedQueryChangeIdentitySeed(
    /** 保存查询对象 ID。 */
    val objectId: String,
    /** 直接父 Change ID。 */
    val parentChangeIds: List<String>,
    /** 写入 Actor ID。 */
    val actorId: String,
    /** Actor 本地序号。 */
    val actorSequence: Long,
    /** HLC 物理毫秒。 */
    val physicalTimeMillis: Long,
    /** HLC 逻辑计数。 */
    val logicalCounter: Int,
    /** 事务 ID。 */
    val transactionId: String,
    /** Change 类型。 */
    val kind: String,
    /** 查询规范摘要。 */
    val querySha256: String,
    /** 目标快照 revision。 */
    val targetSnapshotRevision: String,
)
