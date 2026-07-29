package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TagDefinition
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
 * 统一编排 TagDefinition 与 PropertyDefinition 的 Change、加密快照和 manifest 原子确认。
 *
 * @param cryptoProvider 提供摘要与初始化密码学端口。
 * @param envelopeCryptoService 生成用途隔离的 schema 与 Change 信封。
 * @param manifestService 生成下一版认证 manifest。
 * @param transactionCoordinator 以 manifest 为确认点提交文件事务。
 */
class WorkspaceSchemaSnapshotTransactionService(
    private val cryptoProvider: CryptoProvider,
    private val envelopeCryptoService: EnvelopeCryptoService,
    private val manifestService: WorkspaceManifestService,
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 创建、更新或删除一个标签定义。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 完整标签定义事务上下文。
     * @return 已确认标签快照、Change、manifest 和提交结果。
     */
    suspend fun commitTag(
        unlocked: UnlockedWorkspaceManifest,
        request: TagDefinitionSnapshotTransactionRequest,
    ): WorkspaceSchemaSnapshotTransactionResult<TagDefinition> = commit(
        unlocked = unlocked,
        payload = request.definition,
        previousSnapshot = request.previousSnapshot,
        context = request.context,
        deleted = request.deleted,
        descriptor = TAG_DESCRIPTOR,
    )

    /**
     * 创建、更新或删除一个属性定义。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 完整属性定义事务上下文。
     * @return 已确认属性快照、Change、manifest 和提交结果。
     */
    suspend fun commitProperty(
        unlocked: UnlockedWorkspaceManifest,
        request: PropertyDefinitionSnapshotTransactionRequest,
    ): WorkspaceSchemaSnapshotTransactionResult<PropertyDefinition> = commit(
        unlocked = unlocked,
        payload = request.definition,
        previousSnapshot = request.previousSnapshot,
        context = request.context,
        deleted = request.deleted,
        descriptor = PROPERTY_DESCRIPTOR,
    )

    /**
     * 以同一协议提交一种工作区 schema 对象。
     *
     * @param T schema payload 类型。
     * @param unlocked 当前已认证 manifest。
     * @param payload 待确认完整定义。
     * @param previousSnapshot 当前已确认快照；创建时为空。
     * @param context 写入事务身份与时间。
     * @param deleted 是否写为 tombstone。
     * @param descriptor 当前 schema 的协议描述。
     * @return 已确认 schema 事务结果。
     */
    private suspend fun <T> commit(
        unlocked: UnlockedWorkspaceManifest,
        payload: T,
        previousSnapshot: ObjectSnapshot<T>?,
        context: WorkspaceSchemaWriteContext,
        deleted: Boolean,
        descriptor: WorkspaceSchemaDescriptor<T>,
    ): WorkspaceSchemaSnapshotTransactionResult<T> {
        cryptoProvider.initialize()
        val objectId = ObjectId(descriptor.objectId(payload))
        val currentRecord = validateCurrentState(unlocked, objectId, previousSnapshot, context, deleted, descriptor)
        val revision = ObjectRevision(context.actorId, context.actorSequence)
        val revisionText = revision.toProtocolRevision()
        val change = createChange(
            unlocked = unlocked,
            objectId = objectId,
            payload = payload,
            previousSnapshot = previousSnapshot,
            context = context,
            deleted = deleted,
            descriptor = descriptor,
            revisionText = revisionText,
        )
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = previousSnapshot?.header?.schemaVersion ?: SCHEMA_VERSION,
                objectId = objectId,
                objectType = descriptor.objectType,
                revision = revision,
                lastChangeId = change.changeId,
                previousSnapshotHash = currentRecord?.encryptedSha256,
                createdAt = previousSnapshot?.header?.createdAt ?: context.committedAt,
                updatedAt = context.committedAt,
                deleted = deleted,
            ),
            payload = payload,
        )

        // 步骤 1：schema 快照和 Change 在进入 staging 前分别加密。
        val snapshotArtifact = encryptArtifact(
            unlocked = unlocked,
            targetPath = descriptor.snapshotPath(objectId.value),
            kind = descriptor.manifestKind,
            manifestObjectId = objectId.value,
            revision = revisionText,
            envelopeObjectType = descriptor.envelopeObjectType,
            envelopeObjectId = objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(descriptor.serializer),
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

        // 步骤 2：下一 manifest 同时引用快照与 Change，避免出现无历史的 schema 状态。
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = context.transactionId,
            upserts = listOf(snapshotArtifact.record, changeArtifact.record),
            removedPaths = emptySet(),
        )

        // 步骤 3：协调器发布密文并以 manifest 原子替换确认业务状态。
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = context.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = listOf(snapshotArtifact.artifact, changeArtifact.artifact),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        return WorkspaceSchemaSnapshotTransactionResult(snapshot, change, encryptedManifest, commit)
    }

    /**
     * 校验旧 schema 快照与当前 manifest 的一致性。
     *
     * @param T schema payload 类型。
     * @param unlocked 当前已认证 manifest。
     * @param objectId 目标对象 ID。
     * @param previousSnapshot 调用方当前快照。
     * @param context 写入身份。
     * @param deleted 是否写 tombstone。
     * @param descriptor schema 协议描述。
     * @return 当前 manifest 记录；创建时为空。
     */
    private fun <T> validateCurrentState(
        unlocked: UnlockedWorkspaceManifest,
        objectId: ObjectId,
        previousSnapshot: ObjectSnapshot<T>?,
        context: WorkspaceSchemaWriteContext,
        deleted: Boolean,
        descriptor: WorkspaceSchemaDescriptor<T>,
    ): ManifestObjectRecord? {
        val record = unlocked.payload.objects.singleOrNull { candidate ->
            candidate.kind == descriptor.manifestKind && candidate.objectId == objectId.value
        }
        if (previousSnapshot == null) {
            require(record == null) { "Schema 对象已存在，不能按创建请求覆盖" }
            require(!deleted) { "不存在的 Schema 对象不能直接创建 tombstone" }
            return null
        }
        require(previousSnapshot.header.objectId == objectId) { "Schema payload ID 与旧快照不一致" }
        require(previousSnapshot.header.objectType == descriptor.objectType) { "Schema 旧快照类型不一致" }
        require(record != null) { "manifest 缺少待更新 Schema 对象" }
        require(record.revision == previousSnapshot.header.revision.toProtocolRevision()) { "Schema 旧快照 revision 已落后" }
        require(
            context.actorId != previousSnapshot.header.revision.actorId ||
                context.actorSequence > previousSnapshot.header.revision.sequence,
        ) { "同一 Actor 的 Schema revision 必须严格递增" }
        return record
    }

    /**
     * 创建包含完整定义替换语义的加密 Change。
     *
     * @param T schema payload 类型。
     * @param unlocked 当前 manifest，用于识别已压缩父 Change。
     * @param objectId 目标 schema 对象 ID。
     * @param payload 完整定义。
     * @param previousSnapshot 当前已确认快照。
     * @param context 写入事务身份与时间。
     * @param deleted 是否写 tombstone。
     * @param descriptor schema 协议描述。
     * @param revisionText 目标协议 revision。
     * @return 可与快照一同确认的 Change。
     */
    private fun <T> createChange(
        unlocked: UnlockedWorkspaceManifest,
        objectId: ObjectId,
        payload: T,
        previousSnapshot: ObjectSnapshot<T>?,
        context: WorkspaceSchemaWriteContext,
        deleted: Boolean,
        descriptor: WorkspaceSchemaDescriptor<T>,
        revisionText: String,
    ): ObjectChange {
        // checkpoint 覆盖快照后允许形成新根，避免重新引用已安全压缩的父节点。
        val parentIds = previousSnapshot?.header?.lastChangeId
            ?.takeIf { lastChangeId -> unlocked.payload.objects.any { record ->
                record.kind == ManifestObjectKind.CHANGE && record.objectId == lastChangeId.value
            } }
            ?.let(::listOf)
            .orEmpty()
        val kind = when {
            previousSnapshot == null -> ChangeKind.CREATE
            deleted -> ChangeKind.DELETE
            else -> ChangeKind.UPDATE
        }
        val payloadSha256 = canonicalSha256(descriptor.serializer, payload)
        val seed = WorkspaceSchemaChangeIdentitySeed(
            objectId = objectId.value,
            parentChangeIds = parentIds.map(ChangeId::value),
            actorId = context.actorId.value,
            actorSequence = context.actorSequence,
            physicalTimeMillis = context.hybridLogicalTime.physicalTimeMillis,
            logicalCounter = context.hybridLogicalTime.logicalCounter,
            transactionId = context.transactionId.value,
            kind = kind.name,
            payloadSha256 = payloadSha256,
            targetSnapshotRevision = revisionText,
        )
        return ObjectChange(
            changeId = ChangeId(canonicalSha256(WorkspaceSchemaChangeIdentitySeed.serializer(), seed)),
            objectId = objectId,
            parentChangeIds = parentIds,
            actorId = context.actorId,
            hybridLogicalTime = context.hybridLogicalTime,
            baseSnapshotRevision = previousSnapshot?.header?.revision,
            transactionId = context.transactionId,
            kind = kind,
            operations = listOf(
                ChangeOperation(
                    type = if (deleted) "delete-${descriptor.protocolName}" else "replace-${descriptor.protocolName}",
                    payload = CanonicalJson.format.encodeToJsonElement(descriptor.serializer, payload),
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
    ): WorkspaceSchemaEncryptedArtifact {
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
        return WorkspaceSchemaEncryptedArtifact(
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

    /**
     * 把对象 revision 转换为协议文本。
     *
     * @return `actorId:sequence` 文本。
     */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    /**
     * 把摘要字节编码为小写十六进制。
     *
     * @return 小写十六进制文本。
     */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        /** 当前 Tag 与 PropertyDefinition schema 版本。 */
        const val SCHEMA_VERSION: Int = 1

        /** Change 信封固定对象类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"

        /** TagDefinition 协议描述。 */
        val TAG_DESCRIPTOR = WorkspaceSchemaDescriptor(
            serializer = TagDefinition.serializer(),
            objectType = ObjectType.TAG,
            manifestKind = ManifestObjectKind.TAG,
            envelopeObjectType = WorkspaceSchemaSnapshotEnvelopeService.TAG_SNAPSHOT_OBJECT_TYPE,
            protocolName = "tag-definition",
            objectId = { definition -> definition.id.value },
            snapshotPath = { id -> WorkspaceRelativePath("tags/$id.tag.enc") },
        )

        /** PropertyDefinition 协议描述。 */
        val PROPERTY_DESCRIPTOR = WorkspaceSchemaDescriptor(
            serializer = PropertyDefinition.serializer(),
            objectType = ObjectType.PROPERTY,
            manifestKind = ManifestObjectKind.PROPERTY,
            envelopeObjectType = WorkspaceSchemaSnapshotEnvelopeService.PROPERTY_SNAPSHOT_OBJECT_TYPE,
            protocolName = "property-definition",
            objectId = { definition -> definition.id.value },
            snapshotPath = { id -> WorkspaceRelativePath("properties/$id.property.enc") },
        )
    }
}

/**
 * 保存一种 schema 对象的稳定协议参数。
 *
 * @param T schema payload 类型。
 */
private data class WorkspaceSchemaDescriptor<T>(
    /** payload serializer。 */
    val serializer: KSerializer<T>,
    /** 通用快照头对象类型。 */
    val objectType: ObjectType,
    /** manifest 对象类别。 */
    val manifestKind: ManifestObjectKind,
    /** 加密信封公开对象类型。 */
    val envelopeObjectType: String,
    /** Change 操作使用的稳定协议名称。 */
    val protocolName: String,
    /** 从 payload 读取稳定对象 ID。 */
    val objectId: (T) -> String,
    /** 根据对象 ID 生成受控快照路径。 */
    val snapshotPath: (String) -> WorkspaceRelativePath,
)

/**
 * 保存工作区 schema 加密输出。
 */
private data class WorkspaceSchemaEncryptedArtifact(
    /** 事务 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** manifest 摘要记录。 */
    val record: ManifestObjectRecord,
)

/**
 * 保存计算工作区 schema Change ID 的非循环输入。
 */
@Serializable
private data class WorkspaceSchemaChangeIdentitySeed(
    /** schema 对象 ID。 */
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
    /** schema payload 规范摘要。 */
    val payloadSha256: String,
    /** 目标快照 revision。 */
    val targetSnapshotRevision: String,
)
