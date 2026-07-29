package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 把并发 Note 三方合并结果作为一个加密事务提交，保证快照、Merge Change 与冲突证据同时确认。
 */
class NoteMergeTransactionService(
    /** 提供内容摘要与安全初始化的密码学端口。 */
    private val cryptoProvider: CryptoProvider,
    /** 为快照、Change 和历史记录生成用途隔离信封。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 生成包含本事务全部对象摘要的下一版 manifest。 */
    private val workspaceManifestService: WorkspaceManifestService,
    /** 执行 staging、原子发布、manifest 确认和派生索引更新。 */
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 验证并发关系、执行三方合并并原子确认全部加密产物。
     *
     * @param unlocked 当前已认证 manifest；密钥生命周期仍由调用方负责。
     * @param request 合并所需快照、Change DAG、Actor 与事务上下文。
     * @return 已确认的新快照、Merge Change、冲突记录和提交结果。
     */
    suspend fun commit(
        unlocked: UnlockedWorkspaceManifest,
        request: NoteMergeTransactionRequest,
    ): NoteMergeTransactionResult {
        cryptoProvider.initialize()
        validateRequest(unlocked, request)

        // 步骤 1：纯内存执行三方合并，任何结构问题都不得产生 staging 文件。
        val objectId = request.currentSnapshot.header.objectId
        val merge = NoteThreeWayMergeService.merge(
            objectId = objectId,
            base = request.baseNote,
            local = NoteMergeVersion(request.currentSnapshot.payload, request.localChange),
            remote = request.remoteVersion,
            knownChanges = request.knownChanges,
            mergedAt = request.mergedAt,
        )
        val conflicts = makeConflictIdsOpaque(merge.conflicts)
        val revision = "${request.mergeActorId.value}:${request.mergeSequence}"
        val noteSha256 = canonicalSha256(Note.serializer(), merge.note)
        val change = createMergeChange(request, revision, noteSha256, conflicts)
        val currentRecord = requireCurrentNoteRecord(unlocked, objectId.value)
        val snapshot = ObjectSnapshot(
            header = request.currentSnapshot.header.copy(
                revision = ObjectRevision(request.mergeActorId, request.mergeSequence),
                lastChangeId = change.changeId,
                previousSnapshotHash = currentRecord.encryptedSha256,
                updatedAt = request.mergedAt,
            ),
            payload = merge.note,
        )

        // 步骤 2：分别加密权威快照、Merge Change 与冲突证据；明文 JSON 字节在使用后立即清零。
        val notePath = WorkspaceRelativePath("notes/${objectId.value}.note.enc")
        val changePath = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc")
        val artifacts = mutableListOf<EncryptedTransactionArtifact>()
        val records = mutableListOf<ManifestObjectRecord>()
        addEncryptedArtifact(
            artifacts = artifacts,
            records = records,
            unlocked = unlocked,
            targetPath = notePath,
            kind = ManifestObjectKind.NOTE,
            manifestObjectId = objectId.value,
            revision = revision,
            envelopeObjectType = "note-snapshot",
            envelopeObjectId = objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(Note.serializer()),
            value = snapshot,
        )
        addEncryptedArtifact(
            artifacts = artifacts,
            records = records,
            unlocked = unlocked,
            targetPath = changePath,
            kind = ManifestObjectKind.CHANGE,
            manifestObjectId = change.changeId.value,
            revision = revision,
            envelopeObjectType = "object-change",
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )
        conflicts.forEach { conflict ->
            addEncryptedArtifact(
                artifacts = artifacts,
                records = records,
                unlocked = unlocked,
                targetPath = WorkspaceRelativePath("history/conflicts/${conflict.conflictId}.audit.enc"),
                kind = ManifestObjectKind.HISTORY,
                manifestObjectId = conflict.conflictId,
                revision = revision,
                envelopeObjectType = "audit-record",
                envelopeObjectId = conflict.conflictId,
                purpose = WrappingKeyPurpose.AUDIT,
                serializer = ConflictRecord.serializer(),
                value = conflict,
            )
        }

        // 步骤 3：manifest 是唯一确认点；协调器确认成功前调用方不得发布内存中的新快照。
        val nextManifest = workspaceManifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = request.transactionId,
            upserts = records,
            removedPaths = emptySet(),
        )
        val plan = EncryptedTransactionPlan(
            transactionId = request.transactionId,
            baseManifestRevision = unlocked.payload.revision,
            targetManifestRevision = unlocked.payload.revision + 1L,
            artifacts = artifacts,
            encryptedManifest = nextManifest,
            manifestSha256 = sha256(nextManifest),
        )
        val commit = transactionCoordinator.commit(plan)
        return NoteMergeTransactionResult(snapshot, change, conflicts, commit)
    }

    /**
     * 验证对象身份、当前快照映射和共同祖先关系，拒绝把因果后继误当成并发合并。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 待验证合并请求。
     */
    private fun validateRequest(
        unlocked: UnlockedWorkspaceManifest,
        request: NoteMergeTransactionRequest,
    ) {
        val header = request.currentSnapshot.header
        require(header.lastChangeId == request.localChange.changeId) { "当前快照必须对应本地 head Change" }
        require(request.localChange.objectId == header.objectId) { "本地 Change 对象与当前快照不一致" }
        require(request.remoteVersion.change.objectId == header.objectId) { "远端 Change 对象与当前快照不一致" }
        require(request.localChange.changeId != request.remoteVersion.change.changeId) { "两个合并 head 不能相同" }
        require(unlocked.file.publicHeader.workspaceId == unlocked.file.encryptedPayload.header.workspaceId) {
            "已解锁 manifest 工作区身份不一致"
        }
        requireCurrentNoteRecord(unlocked, header.objectId.value)
        require(
            SyncWinnerResolver.causalSuccessor(
                request.localChange,
                request.remoteVersion.change,
                request.knownChanges,
            ) == null,
        ) { "只有并发 heads 才能生成 Merge Change" }
        request.baseChangeId?.let { baseChangeId ->
            require(ObjectChangeDag.isAncestor(baseChangeId, request.localChange.changeId, request.knownChanges)) {
                "Base Change 不是本地 head 的祖先"
            }
            require(ObjectChangeDag.isAncestor(baseChangeId, request.remoteVersion.change.changeId, request.knownChanges)) {
                "Base Change 不是远端 head 的祖先"
            }
        }
    }

    /**
     * 创建连接两个并发 heads 的确定性逻辑 Change。
     *
     * @param request 合并请求。
     * @param revision 目标快照 revision。
     * @param noteSha256 合并 Note payload 摘要。
     * @param conflicts 已改为不透明 ID 的冲突记录。
     * @return 可加密写入 Change Log 的 Merge Change。
     */
    private fun createMergeChange(
        request: NoteMergeTransactionRequest,
        revision: String,
        noteSha256: String,
        conflicts: List<ConflictRecord>,
    ): ObjectChange {
        val parentIds = ObjectChangeDag.mergeParentIds(
            listOf(request.localChange.changeId, request.remoteVersion.change.changeId),
        )
        val operationPayload = NoteMergeOperationPayload(
            baseChangeId = request.baseChangeId,
            mergedNoteSha256 = noteSha256,
            targetSnapshotRevision = revision,
            conflictIds = conflicts.map(ConflictRecord::conflictId).sorted(),
        )
        val seed = MergeChangeIdentitySeed(
            objectId = request.currentSnapshot.header.objectId.value,
            parentChangeIds = parentIds.map(ChangeId::value),
            actorId = request.mergeActorId.value,
            physicalTimeMillis = request.hybridLogicalTime.physicalTimeMillis,
            logicalCounter = request.hybridLogicalTime.logicalCounter,
            transactionId = request.transactionId.value,
            operation = operationPayload,
        )
        val changeId = ChangeId(canonicalSha256(MergeChangeIdentitySeed.serializer(), seed))
        return ObjectChange(
            changeId = changeId,
            objectId = request.currentSnapshot.header.objectId,
            parentChangeIds = parentIds,
            actorId = request.mergeActorId,
            hybridLogicalTime = request.hybridLogicalTime,
            baseSnapshotRevision = request.currentSnapshot.header.revision,
            transactionId = request.transactionId,
            kind = ChangeKind.MERGE,
            operations = listOf(
                ChangeOperation(
                    type = "merge-note-heads",
                    payload = CanonicalJson.format.encodeToJsonElement(NoteMergeOperationPayload.serializer(), operationPayload),
                ),
            ),
        )
    }

    /**
     * 将包含字段路径的临时冲突 ID 替换成固定长度 SHA-256，避免文件名与 manifest 泄露语义。
     *
     * @param conflicts 三方合并生成的冲突记录。
     * @return 只使用不透明 ID 的冲突记录。
     */
    private fun makeConflictIdsOpaque(conflicts: List<ConflictRecord>): List<ConflictRecord> = conflicts.map { conflict ->
        val seed = ConflictIdentitySeed(
            objectId = conflict.objectId.value,
            fieldKind = conflict.fieldKind.name,
            fieldPath = conflict.fieldPath,
            localChangeId = conflict.localChangeId.value,
            remoteChangeId = conflict.remoteChangeId.value,
        )
        conflict.copy(conflictId = canonicalSha256(ConflictIdentitySeed.serializer(), seed))
    }.also { opaqueConflicts ->
        require(opaqueConflicts.map(ConflictRecord::conflictId).distinct().size == opaqueConflicts.size) {
            "冲突身份摘要发生重复"
        }
    }

    /**
     * 加密一个 JSON 对象并同时生成事务 artifact 与 manifest 摘要记录。
     *
     * @param T 明文对象类型。
     * @param artifacts 事务 artifact 累积器。
     * @param records manifest 记录累积器。
     * @param unlocked 当前已认证 manifest 与会话主密钥。
     * @param targetPath 最终受控相对路径。
     * @param kind manifest 对象类别。
     * @param manifestObjectId manifest 使用的不透明对象 ID。
     * @param revision 对象 revision。
     * @param envelopeObjectType 信封固定对象类型。
     * @param envelopeObjectId 必须与文件名一致的信封对象 ID。
     * @param purpose 用途隔离 wrapping key 类型。
     * @param serializer 明文 serializer。
     * @param value 待加密对象。
     */
    private suspend fun <T> addEncryptedArtifact(
        artifacts: MutableList<EncryptedTransactionArtifact>,
        records: MutableList<ManifestObjectRecord>,
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
    ) {
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
        artifacts += EncryptedTransactionArtifact(targetPath, encrypted, encryptedSha256)
        records += ManifestObjectRecord(kind, manifestObjectId, revision, targetPath, encryptedSha256, unlocked.file.publicHeader.activeKeyId)
    }

    /**
     * 查找 manifest 当前确认的 Note 记录。
     *
     * @param unlocked 当前已认证 manifest。
     * @param objectId Note 对象 ID。
     * @return 唯一 Note 摘要记录。
     */
    private fun requireCurrentNoteRecord(
        unlocked: UnlockedWorkspaceManifest,
        objectId: String,
    ): ManifestObjectRecord = unlocked.payload.objects.singleOrNull { record ->
        record.kind == ManifestObjectKind.NOTE && record.objectId == objectId
    } ?: throw IllegalArgumentException("manifest 不包含待合并 Note")

    /**
     * 计算规范 JSON 的 SHA-256 并清零临时字节。
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
     * 把摘要字节转换为小写十六进制文本。
     *
     * @return 小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/** 保存计算 Merge Change 逻辑 ID 的完整非循环输入。 */
@Serializable
private data class MergeChangeIdentitySeed(
    /** 被合并 Note 对象 ID。 */
    val objectId: String,
    /** 两个并发逻辑 Change ID。 */
    val parentChangeIds: List<String>,
    /** 产生合并的 Actor ID。 */
    val actorId: String,
    /** HLC 物理时间。 */
    val physicalTimeMillis: Long,
    /** HLC 逻辑计数。 */
    val logicalCounter: Int,
    /** 原子提交事务 ID。 */
    val transactionId: String,
    /** 合并后内容与冲突摘要。 */
    val operation: NoteMergeOperationPayload,
)

/** 保存计算不透明 Conflict ID 的稳定输入。 */
@Serializable
private data class ConflictIdentitySeed(
    /** 冲突对象 ID。 */
    val objectId: String,
    /** 冲突字段类型。 */
    val fieldKind: String,
    /** 仅存在本地密文中的字段路径。 */
    val fieldPath: String,
    /** 本地候选 Change ID。 */
    val localChangeId: String,
    /** 远端候选 Change ID。 */
    val remoteChangeId: String,
)
