package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.TransactionCommitResult
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 在新工作区中以一个原子事务创建加密默认 Note 标签、基础属性及其初始 Change。
 */
class WorkspaceBootstrapTransactionService(
    /** 提供摘要和随机信封所需密码学原语。 */
    private val cryptoProvider: CryptoProvider,
    /** 为 Tag Snapshot 与 Change 生成用途隔离信封。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 生成引用两个新 artifact 的下一 manifest。 */
    private val manifestService: WorkspaceManifestService,
    /** 以 manifest 为确认点提交初始化事务。 */
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 创建内置根 Note 标签和三个基础属性；已有任何内置对象时拒绝重复引导。
     *
     * @param unlocked 当前已认证的新工作区 manifest。
     * @param actorId 初始化 Actor。
     * @param actorSequence Actor 本地序号。
     * @param transactionId 初始化事务 ID。
     * @param hybridLogicalTime 初始化 HLC。
     * @param committedAt UTC ISO-8601 确认时间。
     * @return 新 manifest、默认 schema 快照和提交结果。
     */
    suspend fun createDefaultNoteTag(
        unlocked: UnlockedWorkspaceManifest,
        actorId: ActorId,
        actorSequence: Long,
        transactionId: TransactionId,
        hybridLogicalTime: HybridLogicalTime,
        committedAt: String,
    ): WorkspaceBootstrapResult {
        require(actorSequence > 0L) { "Bootstrap Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "Bootstrap Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "Bootstrap 时间不能为空" }
        val tagId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
        val propertyDefinitions = WorkspaceDefaultSchemas.propertyDefinitions()
        val reservedObjectIds = propertyDefinitions.map { definition -> definition.id.value }.toSet() + tagId.value
        require(unlocked.payload.objects.none { record -> record.objectId in reservedObjectIds }) {
            "工作区默认 schema 已经部分或全部存在"
        }
        require(actorSequence <= Long.MAX_VALUE - propertyDefinitions.size) { "Bootstrap Actor 序号溢出" }
        require(hybridLogicalTime.logicalCounter <= Int.MAX_VALUE - propertyDefinitions.size) { "Bootstrap HLC 溢出" }

        // 步骤 1：每个 PropertyDef 使用独立 revision 与 Change，并保持同一事务内的稳定顺序。
        val propertyOutputs = propertyDefinitions.mapIndexed { index, propertyDefinition ->
            createPropertyOutput(
                unlocked = unlocked,
                definition = propertyDefinition,
                actorId = actorId,
                actorSequence = actorSequence + index,
                transactionId = transactionId,
                hybridLogicalTime = hybridLogicalTime.copy(
                    logicalCounter = hybridLogicalTime.logicalCounter + index,
                ),
                committedAt = committedAt,
            )
        }

        val tagSequence = actorSequence + propertyDefinitions.size
        val tagTime = hybridLogicalTime.copy(
            logicalCounter = hybridLogicalTime.logicalCounter + propertyDefinitions.size,
        )
        val revision = ObjectRevision(actorId, tagSequence)
        val revisionText = "${actorId.value}:$tagSequence"
        val definition = WorkspaceDefaultSchemas.noteTagDefinition()
        val definitionSha256 = canonicalSha256(TagDefinition.serializer(), definition)
        val seed = BootstrapChangeSeed(
            objectId = tagId.value,
            actorId = actorId.value,
            actorSequence = tagSequence,
            transactionId = transactionId.value,
            physicalTimeMillis = tagTime.physicalTimeMillis,
            logicalCounter = tagTime.logicalCounter,
            definitionSha256 = definitionSha256,
        )
        val change = ObjectChange(
            changeId = ChangeId(canonicalSha256(BootstrapChangeSeed.serializer(), seed)),
            objectId = ObjectId(tagId.value),
            parentChangeIds = emptyList(),
            actorId = actorId,
            hybridLogicalTime = tagTime,
            baseSnapshotRevision = null,
            transactionId = transactionId,
            kind = ChangeKind.CREATE,
            operations = listOf(
                ChangeOperation(
                    type = "create-default-note-tag",
                    payload = CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), definition),
                ),
            ),
        )
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = TAG_SCHEMA_VERSION,
                objectId = ObjectId(tagId.value),
                objectType = ObjectType.TAG,
                revision = revision,
                lastChangeId = change.changeId,
                createdAt = committedAt,
                updatedAt = committedAt,
            ),
            payload = definition,
        )

        // 步骤 2：Tag Snapshot 与初始 Change 分别加密，任何失败都不得写入 staging。
        val tagPath = WorkspaceRelativePath("tags/${tagId.value}.tag.enc")
        val changePath = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc")
        val tagArtifact = encrypt(
            unlocked = unlocked,
            path = tagPath,
            kind = ManifestObjectKind.TAG,
            manifestObjectId = tagId.value,
            revision = revisionText,
            envelopeObjectType = TAG_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = tagId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(TagDefinition.serializer()),
            value = snapshot,
        )
        val changeArtifact = encrypt(
            unlocked = unlocked,
            path = changePath,
            kind = ManifestObjectKind.CHANGE,
            manifestObjectId = change.changeId.value,
            revision = revisionText,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )

        val propertyArtifacts = propertyOutputs.flatMap { output ->
            listOf(output.snapshotArtifact, output.changeArtifact)
        }
        val allArtifacts = propertyArtifacts + listOf(tagArtifact, changeArtifact)

        // 步骤 3：下一 manifest 同时确认全部 schema 与 Change，避免出现部分初始化。
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = transactionId,
            upserts = allArtifacts.map(BootstrapEncryptedArtifact::record),
            removedPaths = emptySet(),
        )
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = allArtifacts.map(BootstrapEncryptedArtifact::artifact),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        return WorkspaceBootstrapResult(
            defaultTagSnapshot = snapshot,
            defaultPropertySnapshots = propertyOutputs.map(BootstrapPropertyOutput::snapshot),
            encryptedManifest = encryptedManifest,
            commit = commit,
            consumedActorSequences = propertyDefinitions.size + 1,
        )
    }

    /**
     * 为一个内置 PropertyDef 构造快照、初始 Change 及两份密文 artifact。
     *
     * @param unlocked 当前解锁工作区。
     * @param definition 内置属性定义。
     * @param actorId 初始化 Actor。
     * @param actorSequence 当前对象 revision 序号。
     * @param transactionId 统一初始化事务。
     * @param hybridLogicalTime 当前对象 HLC。
     * @param committedAt UTC 确认时间。
     * @return 属性快照与两个待提交密文。
     */
    private suspend fun createPropertyOutput(
        unlocked: UnlockedWorkspaceManifest,
        definition: PropertyDefinition,
        actorId: ActorId,
        actorSequence: Long,
        transactionId: TransactionId,
        hybridLogicalTime: HybridLogicalTime,
        committedAt: String,
    ): BootstrapPropertyOutput {
        val revisionText = "${actorId.value}:$actorSequence"
        val definitionSha256 = canonicalSha256(PropertyDefinition.serializer(), definition)
        val seed = BootstrapChangeSeed(
            objectId = definition.id.value,
            actorId = actorId.value,
            actorSequence = actorSequence,
            transactionId = transactionId.value,
            physicalTimeMillis = hybridLogicalTime.physicalTimeMillis,
            logicalCounter = hybridLogicalTime.logicalCounter,
            definitionSha256 = definitionSha256,
        )
        val change = ObjectChange(
            changeId = ChangeId(canonicalSha256(BootstrapChangeSeed.serializer(), seed)),
            objectId = ObjectId(definition.id.value),
            parentChangeIds = emptyList(),
            actorId = actorId,
            hybridLogicalTime = hybridLogicalTime,
            baseSnapshotRevision = null,
            transactionId = transactionId,
            kind = ChangeKind.CREATE,
            operations = listOf(
                ChangeOperation(
                    type = "create-default-property",
                    payload = CanonicalJson.format.encodeToJsonElement(PropertyDefinition.serializer(), definition),
                ),
            ),
        )
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = PROPERTY_SCHEMA_VERSION,
                objectId = ObjectId(definition.id.value),
                objectType = ObjectType.PROPERTY,
                revision = ObjectRevision(actorId, actorSequence),
                lastChangeId = change.changeId,
                createdAt = committedAt,
                updatedAt = committedAt,
            ),
            payload = definition,
        )
        val snapshotArtifact = encrypt(
            unlocked = unlocked,
            path = WorkspaceRelativePath("properties/${definition.id.value}.property.enc"),
            kind = ManifestObjectKind.PROPERTY,
            manifestObjectId = definition.id.value,
            revision = revisionText,
            envelopeObjectType = PROPERTY_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = definition.id.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(PropertyDefinition.serializer()),
            value = snapshot,
        )
        val changeArtifact = encrypt(
            unlocked = unlocked,
            path = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc"),
            kind = ManifestObjectKind.CHANGE,
            manifestObjectId = change.changeId.value,
            revision = revisionText,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )
        return BootstrapPropertyOutput(snapshot, snapshotArtifact, changeArtifact)
    }

    /**
     * 加密初始化对象并生成事务与 manifest 记录。
     *
     * @param T 明文类型。
     * @param unlocked 当前解锁工作区。
     * @param path 最终相对路径。
     * @param kind manifest 对象类别。
     * @param manifestObjectId manifest 对象 ID。
     * @param revision 对象 revision。
     * @param envelopeObjectType 信封对象类型。
     * @param envelopeObjectId 信封对象 ID。
     * @param purpose wrapping key 用途。
     * @param serializer 明文 serializer。
     * @param value 待加密值。
     * @return 密文 artifact 与 manifest 记录。
     */
    private suspend fun <T> encrypt(
        unlocked: UnlockedWorkspaceManifest,
        path: WorkspaceRelativePath,
        kind: ManifestObjectKind,
        manifestObjectId: String,
        revision: String,
        envelopeObjectType: String,
        envelopeObjectId: String,
        purpose: WrappingKeyPurpose,
        serializer: KSerializer<T>,
        value: T,
    ): BootstrapEncryptedArtifact {
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
        return BootstrapEncryptedArtifact(
            artifact = EncryptedTransactionArtifact(path, encrypted, encryptedSha256),
            record = ManifestObjectRecord(
                kind = kind,
                objectId = manifestObjectId,
                revision = revision,
                path = path,
                encryptedSha256 = encryptedSha256,
                keyId = unlocked.file.publicHeader.activeKeyId,
            ),
        )
    }

    /**
     * 计算规范 JSON 摘要并清零临时编码。
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
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        /** 初始 Tag schema 版本。 */
        const val TAG_SCHEMA_VERSION: Int = 1

        /** 初始 PropertyDef schema 版本。 */
        const val PROPERTY_SCHEMA_VERSION: Int = 1

        /** Tag 快照信封类型。 */
        const val TAG_SNAPSHOT_OBJECT_TYPE: String = "tag-snapshot"

        /** PropertyDef 快照信封类型。 */
        const val PROPERTY_SNAPSHOT_OBJECT_TYPE: String = "property-snapshot"

        /** Change 信封类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"
    }
}

/**
 * 返回默认标签快照、下一 manifest 和事务确认状态。
 */
data class WorkspaceBootstrapResult(
    /** 新确认默认标签快照。 */
    val defaultTagSnapshot: ObjectSnapshot<TagDefinition>,
    /** 新确认的三个基础属性快照。 */
    val defaultPropertySnapshots: List<ObjectSnapshot<PropertyDefinition>>,
    /** 新加密 manifest 字节。 */
    val encryptedManifest: ByteArray,
    /** 原子提交结果。 */
    val commit: TransactionCommitResult,
    /** 本事务为 schema 对象消耗的连续 Actor revision 数量。 */
    val consumedActorSequences: Int,
)

/**
 * 保存一个默认 PropertyDef 的快照与两份待提交密文。
 */
private data class BootstrapPropertyOutput(
    /** 默认属性快照。 */
    val snapshot: ObjectSnapshot<PropertyDefinition>,
    /** 属性快照密文。 */
    val snapshotArtifact: BootstrapEncryptedArtifact,
    /** 属性初始 Change 密文。 */
    val changeArtifact: BootstrapEncryptedArtifact,
)

/**
 * 保存 Bootstrap 加密输出。
 */
private data class BootstrapEncryptedArtifact(
    /** 事务 artifact。 */
    val artifact: EncryptedTransactionArtifact,
    /** manifest 摘要记录。 */
    val record: ManifestObjectRecord,
)

/**
 * 保存初始 Change ID 的全部非循环输入。
 */
@Serializable
private data class BootstrapChangeSeed(
    /** 默认标签对象 ID。 */
    val objectId: String,
    /** 初始化 Actor ID。 */
    val actorId: String,
    /** Actor 序号。 */
    val actorSequence: Long,
    /** 初始化事务 ID。 */
    val transactionId: String,
    /** HLC 物理时间。 */
    val physicalTimeMillis: Long,
    /** HLC 逻辑计数。 */
    val logicalCounter: Int,
    /** 默认 TagDefinition 摘要。 */
    val definitionSha256: String,
)
