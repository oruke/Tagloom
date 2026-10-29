package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.crypto.EncryptionEnvelope
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseProtocolValidator
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ConflictRecord
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.PreparedTransactionRecord
import com.oruke.tagloom.core.transaction.ChangeLogCheckpoint
import com.oruke.tagloom.core.workspace.WorkspaceManifestPayload
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import kotlinx.serialization.KSerializer
import kotlin.coroutines.cancellation.CancellationException

/**
 * 使用已授权会话主密钥验证 JVM staging 对象与 manifest 的真实 AEAD 和 schema。
 *
 * 调用方拥有主密钥生命周期；锁定工作区前必须先停止事务恢复，再销毁该密钥。
 */
class JvmWorkspaceContentVerifier(
    /** 当前工作区公开随机标识。 */
    private val workspaceId: WorkspaceId,
    /** 当前会话允许解密的新写入主密钥版本。 */
    private val activeKeyId: KeyId,
    /** 当前会话使用的 Workspace Master Key。 */
    private val workspaceMasterKey: SecretKeyMaterial,
    /** 负责对象信封 AEAD 与身份校验。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 负责 manifest 公开头、载荷和确认状态校验。 */
    private val workspaceManifestService: WorkspaceManifestService,
) : JvmPreparedContentVerifier {
    /**
     * 使用活动主密钥加密 prepared 事务控制记录。
     *
     * @param transactionId 事务目录使用的稳定标识。
     * @param plaintext 规范化 prepared 记录明文。
     * @return 只包含加密信封的规范容器字节。
     */
    override suspend fun encryptPreparedRecord(
        transactionId: TransactionId,
        plaintext: ByteArray,
    ): ByteArray {
        val envelope = envelopeCryptoService.encrypt(
            request = EnvelopeEncryptionRequest(
                identity = preparedRecordIdentity(transactionId),
                keyId = activeKeyId,
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                plaintext = plaintext,
            ),
            workspaceMasterKey = workspaceMasterKey,
        )
        return JvmPreparedRecordEnvelopeCodec.encode(envelope)
    }

    /**
     * 使用活动主密钥尝试解密 prepared 控制记录。
     *
     * 容器允许包含旧/新两份轮换信封；当前会话只会接受身份、密钥版本和用途均匹配的那一份。
     *
     * @param transactionId 事务目录使用的稳定标识。
     * @param encryptedBytes staging 中的加密容器字节。
     * @return 认证后的 prepared 记录明文。
     */
    override suspend fun decryptPreparedRecord(
        transactionId: TransactionId,
        encryptedBytes: ByteArray,
    ): ByteArray {
        val identity = preparedRecordIdentity(transactionId)
        val envelopes = JvmPreparedRecordEnvelopeCodec.decode(encryptedBytes)
        var lastFailure: Throwable? = null
        for (envelope in envelopes) {
            try {
                return envelopeCryptoService.decrypt(
                    envelope = envelope,
                    context = EnvelopeDecryptionContext(
                        expectedIdentity = identity,
                        expectedKeyId = activeKeyId,
                        expectedWrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                    ),
                    workspaceMasterKey = workspaceMasterKey,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw IllegalStateException("prepared 事务记录认证失败", lastFailure)
    }

    /**
     * 验证密文信封、路径身份以及对应对象 schema。
     *
     * @param record artifact 恢复记录。
     * @param encryptedBytes 完整二进制信封。
     * @return 全部验证通过时为 `true`。
     */
    override suspend fun verifyArtifact(
        record: PreparedArtifactRecord,
        encryptedBytes: ByteArray,
    ): Boolean {
        return try {
            val role = ArtifactRole.from(record.targetPath.value)
            val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
            val plaintext = decryptArtifact(role, envelope)
            try {
                validatePlaintext(role, envelope, plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 验证下一 manifest 的 revision、确认事务和全部待发布对象摘要。
     *
     * @param record prepared 事务记录。
     * @param encryptedBytes 待确认 manifest 字节。
     * @return manifest 可作为当前事务确认点时为 `true`。
     */
    override suspend fun verifyNextManifest(
        record: PreparedTransactionRecord,
        encryptedBytes: ByteArray,
    ): Boolean {
        return try {
            val payload = workspaceManifestService.inspectWithMasterKey(encryptedBytes, workspaceMasterKey)
            if (
                payload.revision != record.targetManifestRevision ||
                payload.lastCommittedTransactionId != record.transactionId ||
                record.transactionId !in payload.committedTransactionIds
            ) {
                false
            } else {
                val objectsByPath = payload.objects.associateBy { objectRecord -> objectRecord.path }
                record.artifacts.all { artifact ->
                    objectsByPath[artifact.targetPath]?.encryptedSha256 == artifact.expectedSha256
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 解密当前 manifest 并返回事务恢复所需的最小确认状态。
     *
     * @param encryptedBytes 当前 manifest 完整字节。
     * @return 已认证 revision 与近期事务确认集合。
     */
    override suspend fun inspectManifest(encryptedBytes: ByteArray): ManifestTransactionState {
        val payload = workspaceManifestService.inspectWithMasterKey(encryptedBytes, workspaceMasterKey)
        return payload.toTransactionState()
    }

    /**
     * 构造 prepared 控制记录使用的固定信封身份。
     *
     * @param transactionId 事务目录使用的稳定标识。
     * @return 绑定工作区和事务目录的公开身份。
     */
    private fun preparedRecordIdentity(transactionId: TransactionId): EnvelopeIdentity = EnvelopeIdentity(
        workspaceId = workspaceId,
        objectType = PREPARED_RECORD_OBJECT_TYPE,
        objectId = transactionId.value,
        revision = PREPARED_RECORD_REVISION,
    )

    /**
     * 按路径推导出的可信角色解密对象信封。
     *
     * @param role 由受控相对路径解析出的对象角色。
     * @param envelope 待验证二进制信封。
     * @return 通过 AEAD 的短期明文字节。
     */
    private suspend fun decryptArtifact(
        role: ArtifactRole,
        envelope: EncryptionEnvelope,
    ): ByteArray = envelopeCryptoService.decrypt(
        envelope = envelope,
        context = EnvelopeDecryptionContext(
            expectedIdentity = EnvelopeIdentity(
                workspaceId = workspaceId,
                objectType = role.envelopeObjectType,
                objectId = role.objectId,
                revision = envelope.header.revision,
            ),
            expectedKeyId = activeKeyId,
            expectedWrappingKeyPurpose = role.wrappingKeyPurpose,
        ),
        workspaceMasterKey = workspaceMasterKey,
    )

    /**
     * 解析对象 schema 并核对明文头与信封身份。
     *
     * @param role 路径决定的对象角色。
     * @param envelope 已通过 AEAD 的信封。
     * @param plaintext 待解析的短期明文。
     * @return schema 与身份均有效时为 `true`。
     */
    private fun validatePlaintext(
        role: ArtifactRole,
        envelope: EncryptionEnvelope,
        plaintext: ByteArray,
    ): Boolean = when (role.kind) {
        ArtifactSchemaKind.NOTE -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(Note.serializer()),
            expectedType = ObjectType.NOTE,
        ) { snapshot -> NoteValidator.validate(snapshot.payload).isEmpty() }

        ArtifactSchemaKind.TAG -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(TagDefinition.serializer()),
            expectedType = ObjectType.TAG,
        ) { true }

        ArtifactSchemaKind.PROPERTY -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(PropertyDefinition.serializer()),
            expectedType = ObjectType.PROPERTY,
        ) { true }

        ArtifactSchemaKind.SAVED_QUERY -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(SavedQuery.serializer()),
            expectedType = ObjectType.SAVED_QUERY,
        ) { true }

        // 单个 staging 的恢复顺序不能假定其他目录对象已经发布；跨对象引用由事务规划阶段统一校验。
        ArtifactSchemaKind.DATABASE -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(DatabaseDefinition.serializer()),
            expectedType = ObjectType.DATABASE,
        ) { snapshot -> snapshot.payload.id.value == role.objectId }

        ArtifactSchemaKind.VIEW -> validateSnapshot(
            role = role,
            envelope = envelope,
            plaintext = plaintext,
            serializer = ObjectSnapshot.serializer(ViewDefinition.serializer()),
            expectedType = ObjectType.VIEW,
        ) { snapshot ->
            snapshot.payload.id.value == role.objectId &&
                DatabaseProtocolValidator.validate(snapshot.payload).none { !it.readOnlyCompatibility }
        }

        ArtifactSchemaKind.CHANGE -> {
            val change = CanonicalJson.decodeFromString(ObjectChange.serializer(), plaintext.decodeToString())
            change.changeId.value == role.objectId
        }

        ArtifactSchemaKind.CONFLICT -> {
            val conflict = CanonicalJson.decodeFromString(ConflictRecord.serializer(), plaintext.decodeToString())
            conflict.conflictId == role.objectId
        }

        ArtifactSchemaKind.CHECKPOINT -> {
            val checkpoint = CanonicalJson.decodeFromString(
                ChangeLogCheckpoint.serializer(),
                plaintext.decodeToString(),
            )
            checkpoint.checkpointId == role.objectId
        }

        ArtifactSchemaKind.OPAQUE -> plaintext.isNotEmpty()
    }

    /**
     * 校验通用对象快照头，并执行具体 payload 的附加规则。
     *
     * @param T 快照 payload 类型。
     * @param role 路径推导的对象角色。
     * @param envelope 已认证信封。
     * @param plaintext 快照 JSON 明文。
     * @param serializer 具体快照 serializer。
     * @param expectedType 路径对应的对象类型。
     * @param validatePayload 具体 payload 的领域校验。
     * @return 快照头、revision 和 payload 全部有效时为 `true`。
     */
    private fun <T> validateSnapshot(
        role: ArtifactRole,
        envelope: EncryptionEnvelope,
        plaintext: ByteArray,
        serializer: KSerializer<ObjectSnapshot<T>>,
        expectedType: ObjectType,
        validatePayload: (ObjectSnapshot<T>) -> Boolean,
    ): Boolean {
        val snapshot = CanonicalJson.decodeFromString(serializer, plaintext.decodeToString())
        return snapshot.header.objectId.value == role.objectId &&
            snapshot.header.objectType == expectedType &&
            snapshot.header.schemaVersion in 1..2 &&
            snapshot.header.revision.toProtocolRevision() == envelope.header.revision &&
            validatePayload(snapshot)
    }

    /**
     * 把认证 manifest 载荷投影成文件事务恢复所需的最小状态。
     *
     * @return revision 与近期确认事务集合。
     */
    private fun WorkspaceManifestPayload.toTransactionState(): ManifestTransactionState = ManifestTransactionState(
        revision = revision,
        committedTransactionIds = committedTransactionIds.toSet(),
    )

    /**
     * 把对象 revision 转换为信封公开头使用的稳定文本。
     *
     * @return `actorId:sequence` 格式 revision。
     */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    private companion object {
        /** prepared 控制记录的固定信封对象类型。 */
        const val PREPARED_RECORD_OBJECT_TYPE: String = "prepared-transaction"

        /** prepared 控制记录的固定信封 revision。 */
        const val PREPARED_RECORD_REVISION: String = "prepared:v1"
    }
}

/**
 * 区分 JVM staging 明文需要执行的 schema 校验类型。
 */
private enum class ArtifactSchemaKind {
    /** Note 对象快照。 */
    NOTE,

    /** Tag 对象快照。 */
    TAG,

    /** PropertyDef 对象快照。 */
    PROPERTY,

    /** SavedQuery 对象快照。 */
    SAVED_QUERY,

    /** Database 权威定义快照。 */
    DATABASE,

    /** View 权威定义快照，未知查询或展示版本仍可按只读兼容保留。 */
    VIEW,

    /** Change DAG 单节点。 */
    CHANGE,

    /** Base/Local/Remote 冲突证据。 */
    CONFLICT,

    /** 已认证快照根与被压缩 Change 集合。 */
    CHECKPOINT,

    /** Blob 等只校验 AEAD 且不解释内容的字节。 */
    OPAQUE,
}

/**
 * 保存由受控工作区路径推导出的信封身份、用途和 schema。
 */
private data class ArtifactRole(
    /** 信封公开头固定对象类型。 */
    val envelopeObjectType: String,
    /** 从文件名提取的稳定对象标识。 */
    val objectId: String,
    /** 用途隔离的 Wrapping Key 类别。 */
    val wrappingKeyPurpose: WrappingKeyPurpose,
    /** 解密后执行的 schema 校验类型。 */
    val kind: ArtifactSchemaKind,
) {
    companion object {
        /**
         * 从已经过 `WorkspaceRelativePath` 校验的路径推导对象角色。
         *
         * @param path 工作区受控相对路径文本。
         * @return 对应信封和 schema 角色。
         */
        fun from(path: String): ArtifactRole = when {
            path.startsWith("notes/") -> snapshotRole(path, ".note.enc", "note-snapshot", ArtifactSchemaKind.NOTE)
            path.startsWith("tags/") -> snapshotRole(path, ".tag.enc", "tag-snapshot", ArtifactSchemaKind.TAG)
            path.startsWith("properties/") -> snapshotRole(
                path,
                ".property.enc",
                "property-snapshot",
                ArtifactSchemaKind.PROPERTY,
            )
            path.startsWith("queries/") -> snapshotRole(
                path,
                ".query.enc",
                "saved-query-snapshot",
                ArtifactSchemaKind.SAVED_QUERY,
            )
            path.startsWith("databases/") -> snapshotRole(path, ".database.enc", "database-snapshot", ArtifactSchemaKind.DATABASE)
            path.startsWith("views/") -> snapshotRole(path, ".view.enc", "view-snapshot", ArtifactSchemaKind.VIEW)
            path.startsWith("changes/") -> ArtifactRole(
                envelopeObjectType = "object-change",
                objectId = extractFileId(path, ".change.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.CHANGE,
                kind = ArtifactSchemaKind.CHANGE,
            )
            path.startsWith("blobs/") -> ArtifactRole(
                envelopeObjectType = "blob",
                objectId = extractFileId(path, ".blob.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.BLOB,
                kind = ArtifactSchemaKind.OPAQUE,
            )
            path.startsWith("history/conflicts/") -> ArtifactRole(
                envelopeObjectType = "audit-record",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.CONFLICT,
            )
            path.startsWith("history/checkpoints/") -> ArtifactRole(
                envelopeObjectType = "change-checkpoint",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.CHECKPOINT,
            )
            path.startsWith("history/") -> ArtifactRole(
                envelopeObjectType = "audit-record",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.OPAQUE,
            )
            else -> throw IllegalArgumentException("不支持的 staging artifact 路径")
        }

        /**
         * 构造权威对象快照角色。
         *
         * @param path 完整受控相对路径。
         * @param suffix 路径类别固定后缀。
         * @param objectType 信封固定对象类型。
         * @param kind 解密后的 schema 类型。
         * @return 使用 SNAPSHOT wrapping key 的角色。
         */
        private fun snapshotRole(
            path: String,
            suffix: String,
            objectType: String,
            kind: ArtifactSchemaKind,
        ): ArtifactRole = ArtifactRole(
            envelopeObjectType = objectType,
            objectId = extractFileId(path, suffix),
            wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
            kind = kind,
        )

        /**
         * 从路径末段剥离严格后缀并验证不透明 ID。
         *
         * @param path 完整受控相对路径。
         * @param suffix 该类别要求的文件后缀。
         * @return 文件名中的稳定对象 ID。
         */
        private fun extractFileId(
            path: String,
            suffix: String,
        ): String {
            val fileName = path.substringAfterLast('/')
            require(fileName.endsWith(suffix)) { "artifact 文件后缀不匹配" }
            val objectId = fileName.removeSuffix(suffix)
            require(objectId.matches(OPAQUE_ID_PATTERN)) { "artifact 对象 ID 格式无效" }
            return objectId
        }

        /** 工作区文件名允许的不透明 ID 格式。 */
        private val OPAQUE_ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
