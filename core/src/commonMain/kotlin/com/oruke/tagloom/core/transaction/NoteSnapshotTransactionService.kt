package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteNormalizer
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
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
 * 统一编排 Note 规范化、Change 生成、快照加密和 manifest 原子确认。
 *
 * UI、CLI、MCP 与 Agent 都必须调用该写入用例，不能各自直接发布快照文件。
 */
class NoteSnapshotTransactionService(
    /** 提供安全摘要与初始化的密码学端口。 */
    private val cryptoProvider: CryptoProvider,
    /** 生成 Note 与 Change 的用途隔离信封。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 生成下一版 manifest。 */
    private val workspaceManifestService: WorkspaceManifestService,
    /** 执行 staging、发布、确认、恢复和派生更新。 */
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 创建、更新、删除或恢复一篇 Note，并以 manifest 为唯一确认点。
     *
     * @param unlocked 当前已认证 manifest；主密钥生命周期仍由调用方负责。
     * @param request 完整 Note 写入上下文。
     * @return 已确认快照、Change、新 manifest 字节和提交结果。
     */
    suspend fun commit(
        unlocked: UnlockedWorkspaceManifest,
        request: NoteSnapshotTransactionRequest,
    ): NoteSnapshotTransactionResult {
        val result = commitBatch(unlocked, listOf(request))
        return NoteSnapshotTransactionResult(result.snapshots.single(), result.changes.single(), result.encryptedManifest, result.commit)
    }

    /**
     * 将多个 Note 的快照与 Change 放入同一 manifest 确认点，任一校验失败均不发布。
     * @param unlocked 当前已认证 manifest。
     * @param requests 共享事务 ID、对象身份不重复的完整写入请求。
     * @return 同时确认的全部快照、Change 和事务结果。
     */
    suspend fun commitBatch(unlocked: UnlockedWorkspaceManifest, requests: List<NoteSnapshotTransactionRequest>): NoteBatchTransactionResult {
        require(requests.isNotEmpty() && requests.map { it.objectId }.distinct().size == requests.size) { "note-batch-identities-invalid" }
        val transactionId = requests.first().transactionId
        require(requests.all { it.transactionId == transactionId }) { "note-batch-transaction-mismatch" }
        // 先在内存校验并加密全部对象；创建链接和来源引用永远不会各自确认半次。
        val prepared = requests.map { prepare(unlocked, it) }
        val nextManifest = workspaceManifestService.confirmTransaction(
            unlocked, transactionId, prepared.flatMap { it.records }, emptySet(),
            minimumFormatVersion = if (requests.any { it.note.linkMetadata != null }) 2 else 1,
        )
        val commit = transactionCoordinator.commit(EncryptedTransactionPlan(
            transactionId, unlocked.payload.revision, unlocked.payload.revision + 1L,
            prepared.flatMap { it.artifacts }, nextManifest, sha256(nextManifest),
        ))
        return NoteBatchTransactionResult(prepared.map { it.snapshot }, prepared.map { it.change }, nextManifest, commit)
    }

    /**
     * 校验并加密一个 Note，不发布文件，供单对象和跨对象事务共同使用。
     * @param unlocked 当前认证状态。
     * @param request 完整 Note 写入请求。
     * @return 尚未确认的密文和对象记录。
     */
    internal suspend fun prepare(unlocked: UnlockedWorkspaceManifest, request: NoteSnapshotTransactionRequest): PreparedNoteSnapshot {
        cryptoProvider.initialize()
        val normalizedNote = NoteNormalizer.normalize(request.note)
        require(NoteValidator.validate(normalizedNote).isEmpty()) { "Note 结构校验失败" }
        val currentRecord = validateCurrentState(unlocked, request)
        val revision = ObjectRevision(request.actorId, request.actorSequence)
        val revisionText = revision.toProtocolRevision()
        val noteSha256 = canonicalSha256(Note.serializer(), normalizedNote)
        val change = createChange(unlocked, request, normalizedNote, noteSha256, revisionText)
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = maxOf(request.previousSnapshot?.header?.schemaVersion ?: NOTE_SCHEMA_VERSION, if (normalizedNote.linkMetadata != null) 2 else 1),
                objectId = request.objectId,
                objectType = ObjectType.NOTE,
                revision = revision,
                lastChangeId = change.changeId,
                previousSnapshotHash = currentRecord?.encryptedSha256,
                createdAt = request.previousSnapshot?.header?.createdAt ?: request.committedAt,
                updatedAt = request.committedAt,
                deleted = request.deleted,
            ),
            payload = normalizedNote,
        )

        // 步骤 1：快照和 Change 分别使用随机 DEK 加密，任何失败都发生在 staging 之前。
        val snapshotPath = WorkspaceRelativePath("notes/${request.objectId.value}.note.enc")
        val changePath = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc")
        val snapshotArtifact = encryptArtifact(
            unlocked = unlocked,
            targetPath = snapshotPath,
            kind = ManifestObjectKind.NOTE,
            manifestObjectId = request.objectId.value,
            revision = revisionText,
            envelopeObjectType = NOTE_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = request.objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(Note.serializer()),
            value = snapshot,
        )
        val changeArtifact = encryptArtifact(
            unlocked = unlocked,
            targetPath = changePath,
            kind = ManifestObjectKind.CHANGE,
            manifestObjectId = change.changeId.value,
            revision = revisionText,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )

        // 成功审计与 Note 共用确认点，防止已写入笔记却缺少审计证据。
        val audit = request.auditEvent?.let { event ->
            AgentAuditTransactionService(cryptoProvider, envelopeCryptoService, workspaceManifestService, transactionCoordinator)
                .encrypt(unlocked, event, request.transactionId)
        }
        val records = listOf(snapshotArtifact.record, changeArtifact.record) + listOfNotNull(audit?.record)
        val artifacts = listOf(snapshotArtifact.artifact, changeArtifact.artifact) + listOfNotNull(audit?.artifact)

        return PreparedNoteSnapshot(snapshot, change, records, artifacts)
    }

    /**
     * 校验请求旧快照与当前 manifest 的一致性，阻止丢失更新和伪造创建。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request Note 写入请求。
     * @return 当前 Note manifest 记录；创建时为空。
     */
    private fun validateCurrentState(
        unlocked: UnlockedWorkspaceManifest,
        request: NoteSnapshotTransactionRequest,
    ): ManifestObjectRecord? {
        val record = unlocked.payload.objects.singleOrNull { candidate ->
            candidate.kind == ManifestObjectKind.NOTE && candidate.objectId == request.objectId.value
        }
        if (request.previousSnapshot == null) {
            require(record == null) { "Note 已存在，不能按创建请求覆盖" }
            require(!request.deleted) { "不存在的 Note 不能直接创建 tombstone" }
            return null
        }
        require(record != null) { "manifest 缺少待更新 Note" }
        require(record.revision == request.previousSnapshot.header.revision.toProtocolRevision()) {
            "Note 旧快照 revision 已落后"
        }
        require(request.actorId != request.previousSnapshot.header.revision.actorId ||
            request.actorSequence > request.previousSnapshot.header.revision.sequence) {
            "同一 Actor 的 Note revision 必须严格递增"
        }
        return record
    }

    /**
     * 创建包含完整 Note 替换语义的不可变 Change。
     *
     * 完整 Note payload 位于加密 Change 内，使历史载体可独立解释；公开 Change ID 只暴露固定长度摘要。
     *
     * @param unlocked 当前 manifest，用于识别上一 Change 是否已被 checkpoint 压缩。
     * @param request Note 写入请求。
     * @param normalizedNote 已规范化 Note。
     * @param noteSha256 Note 规范 JSON 摘要。
     * @param revisionText 目标快照 revision。
     * @return 可与快照一同提交的 Change。
     */
    private fun createChange(
        unlocked: UnlockedWorkspaceManifest,
        request: NoteSnapshotTransactionRequest,
        normalizedNote: Note,
        noteSha256: String,
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
        val seed = NoteChangeIdentitySeed(
            objectId = request.objectId.value,
            parentChangeIds = parentIds.map(ChangeId::value),
            actorId = request.actorId.value,
            actorSequence = request.actorSequence,
            physicalTimeMillis = request.hybridLogicalTime.physicalTimeMillis,
            logicalCounter = request.hybridLogicalTime.logicalCounter,
            transactionId = request.transactionId.value,
            kind = kind.name,
            noteSha256 = noteSha256,
            targetSnapshotRevision = revisionText,
        )
        val changeId = ChangeId(canonicalSha256(NoteChangeIdentitySeed.serializer(), seed))
        return ObjectChange(
            changeId = changeId,
            objectId = request.objectId,
            parentChangeIds = parentIds,
            actorId = request.actorId,
            hybridLogicalTime = request.hybridLogicalTime,
            baseSnapshotRevision = request.previousSnapshot?.header?.revision,
            transactionId = request.transactionId,
            kind = kind,
            operations = listOf(
                ChangeOperation(
                    type = if (request.deleted) "delete-note" else "replace-note",
                    payload = CanonicalJson.format.encodeToJsonElement(Note.serializer(), normalizedNote),
                ),
            ),
        )
    }

    /**
     * 加密一个 JSON 对象并生成事务 artifact 与 manifest 记录。
     *
     * @param T 明文值类型。
     * @param unlocked 当前已解锁工作区。
     * @param targetPath 最终工作区相对路径。
     * @param kind manifest 对象类别。
     * @param manifestObjectId manifest 不透明对象 ID。
     * @param revision 对象 revision。
     * @param envelopeObjectType 信封固定对象类型。
     * @param envelopeObjectId 与文件名一致的信封对象 ID。
     * @param purpose 用途隔离 wrapping key。
     * @param serializer 明文 serializer。
     * @param value 待加密值。
     * @return 成对的事务 artifact 与 manifest 记录。
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
    ): EncryptedArtifactAndRecord {
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
        return EncryptedArtifactAndRecord(
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
     * 计算规范 JSON 的 SHA-256，并清零短期编码字节。
     *
     * @param T 值类型。
     * @param serializer 值 serializer。
     * @param value 待摘要值。
     * @return 64 位小写十六进制摘要。
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
     * 计算字节数组 SHA-256。
     *
     * @param bytes 待摘要字节。
     * @return 64 位小写十六进制摘要。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).toHex()

    /**
     * 把对象 revision 转换为协议文本。
     *
     * @return `actorId:sequence` 格式 revision。
     */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    /**
     * 把摘要字节转换为小写十六进制文本。
     *
     * @return 小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        /** 当前 Note payload schema 版本。 */
        const val NOTE_SCHEMA_VERSION: Int = 1

        /** Note 快照信封固定对象类型。 */
        const val NOTE_SNAPSHOT_OBJECT_TYPE: String = "note-snapshot"

        /** Change 信封固定对象类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"
    }
}

/**
 * 保存一次加密同时产生的事务 artifact 与 manifest 记录。
 */
private data class EncryptedArtifactAndRecord(
    /** 可写入 staging 的密文 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** 下一 manifest 使用的权威摘要记录。 */
    val record: ManifestObjectRecord,
)

/**
 * 保存计算普通 Note Change 逻辑 ID 的全部非循环输入。
 */
@Serializable
private data class NoteChangeIdentitySeed(
    /** Note 对象 ID。 */
    val objectId: String,
    /** 直接父 Change ID。 */
    val parentChangeIds: List<String>,
    /** 产生变更的 Actor ID。 */
    val actorId: String,
    /** Actor 本地对象序号。 */
    val actorSequence: Long,
    /** HLC 物理时间。 */
    val physicalTimeMillis: Long,
    /** HLC 逻辑计数。 */
    val logicalCounter: Int,
    /** 事务 ID。 */
    val transactionId: String,
    /** CREATE、UPDATE 或 DELETE。 */
    val kind: String,
    /** 规范 Note payload 摘要。 */
    val noteSha256: String,
    /** 目标快照 revision。 */
    val targetSnapshotRevision: String,
)
