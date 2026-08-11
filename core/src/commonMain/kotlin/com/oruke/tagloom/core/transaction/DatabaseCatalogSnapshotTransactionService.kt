package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseProtocolValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.ViewDefinition
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

/**
 * 以 manifest 为确认点提交 Database/View 权威对象，并支持原子创建 Database 与首个 View。
 *
 * @param cryptoProvider 提供摘要与密码学初始化。
 * @param envelopeCryptoService 生成用途隔离的快照与 Change 信封。
 * @param manifestService 生成下一版认证 manifest。
 * @param transactionCoordinator 发布密文并原子替换 manifest。
 */
class DatabaseCatalogSnapshotTransactionService(
    private val cryptoProvider: CryptoProvider,
    private val envelopeCryptoService: EnvelopeCryptoService,
    private val manifestService: WorkspaceManifestService,
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 在一个文件事务中创建 Database、首个 View 及各自 Change。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request 完整原子创建请求。
     * @return 两个快照、两个 Change 和单一确认 manifest。
     */
    suspend fun createCatalog(
        unlocked: UnlockedWorkspaceManifest,
        request: DatabaseCatalogCreateRequest,
    ): DatabaseCatalogSnapshotTransactionResult {
        require(request.initialView.databaseId == request.database.id) { "首个 View 不属于目标 Database" }
        require(request.database.defaultViewId == request.initialView.id) { "Database 默认视图必须指向首个 View" }
        require(DatabaseProtocolValidator.validate(request.database, listOf(request.initialView)).isEmpty()) {
            "Database 与首个 View 协议无效"
        }
        val database = prepare(
            unlocked = unlocked,
            payload = request.database,
            previousSnapshot = null,
            context = request.context,
            actorSequence = request.context.actorSequenceStart,
            deleted = false,
            descriptor = DATABASE_DESCRIPTOR,
        )
        val view = prepare(
            unlocked = unlocked,
            payload = request.initialView,
            previousSnapshot = null,
            context = request.context,
            actorSequence = request.context.actorSequenceStart + 1L,
            deleted = false,
            descriptor = VIEW_DESCRIPTOR,
        )
        return commitPrepared(unlocked, request.context, listOf(database, view), consumedActorSequences = 2L)
    }

    /**
     * 更新或 tombstone 一个已确认 DatabaseDefinition。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request Database 更新请求。
     * @return 单对象确认结果。
     */
    suspend fun commitDatabase(
        unlocked: UnlockedWorkspaceManifest,
        request: DatabaseDefinitionSnapshotTransactionRequest,
    ): DatabaseCatalogSnapshotTransactionResult {
        if (!request.deleted) {
            require(DatabaseProtocolValidator.validate(request.database, request.views).isEmpty()) {
                "Database 默认视图或视图协议无效"
            }
        }
        val prepared = prepare(
            unlocked,
            request.database,
            request.previousSnapshot,
            request.context,
            request.context.actorSequenceStart,
            request.deleted,
            DATABASE_DESCRIPTOR,
        )
        return commitPrepared(unlocked, request.context, listOf(prepared), consumedActorSequences = 1L)
    }

    /**
     * 创建、更新或 tombstone 一个 ViewDefinition。
     *
     * @param unlocked 当前已认证 manifest。
     * @param request View 更新请求。
     * @return 单对象确认结果。
     */
    suspend fun commitView(
        unlocked: UnlockedWorkspaceManifest,
        request: ViewDefinitionSnapshotTransactionRequest,
    ): DatabaseCatalogSnapshotTransactionResult {
        if (!request.deleted) {
            require(DatabaseProtocolValidator.validate(request.view).isEmpty()) { "View 协议无效或只读" }
        }
        val prepared = prepare(
            unlocked,
            request.view,
            request.previousSnapshot,
            request.context,
            request.context.actorSequenceStart,
            request.deleted,
            VIEW_DESCRIPTOR,
        )
        return commitPrepared(unlocked, request.context, listOf(prepared), consumedActorSequences = 1L)
    }

    /**
     * 准备一个目录对象的快照、Change 和加密 artifact，但不发布文件。
     *
     * @param T payload 类型。
     * @param unlocked 当前 manifest。
     * @param payload 完整对象定义。
     * @param previousSnapshot 当前快照；创建时为空。
     * @param context 写入身份与时间。
     * @param actorSequence 当前对象使用的 Actor 序号。
     * @param deleted 是否写 tombstone。
     * @param descriptor 对象协议参数。
     * @return 可与同事务其他对象一起确认的准备结果。
     */
    private suspend fun <T> prepare(
        unlocked: UnlockedWorkspaceManifest,
        payload: T,
        previousSnapshot: ObjectSnapshot<T>?,
        context: WorkspaceDatabaseWriteContext,
        actorSequence: Long,
        deleted: Boolean,
        descriptor: CatalogDescriptor<T>,
    ): PreparedCatalogObject<T> {
        cryptoProvider.initialize()
        val objectId = ObjectId(descriptor.objectId(payload))
        val currentRecord = validateCurrentState(
            unlocked,
            objectId,
            previousSnapshot,
            context.actorId,
            actorSequence,
            deleted,
            descriptor,
        )
        val revision = ObjectRevision(context.actorId, actorSequence)
        val revisionText = revision.toProtocolRevision()
        val parentChangeIds = previousSnapshot?.header?.lastChangeId
            ?.takeIf { id -> unlocked.payload.objects.any { record ->
                record.kind == ManifestObjectKind.CHANGE && record.objectId == id.value
            } }
            ?.let(::listOf)
            .orEmpty()
        val kind = when {
            previousSnapshot == null -> ChangeKind.CREATE
            deleted -> ChangeKind.DELETE
            else -> ChangeKind.UPDATE
        }
        val seed = CatalogChangeIdentitySeed(
            objectId = objectId.value,
            parentChangeIds = parentChangeIds.map(ChangeId::value),
            actorId = context.actorId.value,
            actorSequence = actorSequence,
            transactionId = context.transactionId.value,
            kind = kind.name,
            payloadSha256 = canonicalSha256(descriptor.serializer, payload),
            targetSnapshotRevision = revisionText,
        )
        val change = ObjectChange(
            changeId = ChangeId(canonicalSha256(CatalogChangeIdentitySeed.serializer(), seed)),
            objectId = objectId,
            parentChangeIds = parentChangeIds,
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
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = previousSnapshot?.header?.schemaVersion ?: SNAPSHOT_SCHEMA_VERSION,
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
        val snapshotArtifact = encryptArtifact(
            unlocked,
            descriptor.snapshotPath(objectId.value),
            descriptor.manifestKind,
            objectId.value,
            revisionText,
            descriptor.envelopeObjectType,
            objectId.value,
            WrappingKeyPurpose.SNAPSHOT,
            ObjectSnapshot.serializer(descriptor.serializer),
            snapshot,
        )
        val changeArtifact = encryptArtifact(
            unlocked,
            WorkspaceRelativePath("changes/${change.changeId.value}.change.enc"),
            ManifestObjectKind.CHANGE,
            change.changeId.value,
            revisionText,
            CHANGE_OBJECT_TYPE,
            change.changeId.value,
            WrappingKeyPurpose.CHANGE,
            ObjectChange.serializer(),
            change,
        )
        return PreparedCatalogObject(snapshot, change, listOf(snapshotArtifact, changeArtifact), descriptor.objectType)
    }

    /**
     * 用一个 manifest 和一个 TransactionCoordinator 提交全部准备对象。
     *
     * @param unlocked 当前 manifest。
     * @param context 写入身份与时间。
     * @param prepared 待同时确认的对象。
     * @param consumedActorSequences 消耗序号数量。
     * @return 已确认结果。
     */
    private suspend fun commitPrepared(
        unlocked: UnlockedWorkspaceManifest,
        context: WorkspaceDatabaseWriteContext,
        prepared: List<PreparedCatalogObject<*>>,
        consumedActorSequences: Long,
    ): DatabaseCatalogSnapshotTransactionResult {
        val artifacts = prepared.flatMap(PreparedCatalogObject<*>::artifacts)
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked,
            context.transactionId,
            artifacts.map(CatalogEncryptedArtifact::record),
            emptySet(),
        )
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = context.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = artifacts.map(CatalogEncryptedArtifact::artifact),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val databaseSnapshot = prepared.singleOrNull { item -> item.objectType == ObjectType.DATABASE }
            ?.snapshot as? ObjectSnapshot<DatabaseDefinition>
        @Suppress("UNCHECKED_CAST")
        val viewSnapshot = prepared.singleOrNull { item -> item.objectType == ObjectType.VIEW }
            ?.snapshot as? ObjectSnapshot<ViewDefinition>
        return DatabaseCatalogSnapshotTransactionResult(
            databaseSnapshot = databaseSnapshot,
            viewSnapshot = viewSnapshot,
            changes = prepared.map(PreparedCatalogObject<*>::change),
            encryptedManifest = encryptedManifest,
            commit = commit,
            consumedActorSequences = consumedActorSequences,
        )
    }

    /**
     * 校验调用方基线与当前 manifest 一致，防止静默覆盖其他设备或窗口的更新。
     *
     * @param T payload 类型。
     * @param unlocked 当前 manifest。
     * @param objectId 目标对象 ID。
     * @param previousSnapshot 调用方基线快照。
     * @param actorId 当前写入 Actor。
     * @param actorSequence 当前 Actor 序号。
     * @param deleted 是否删除。
     * @param descriptor 对象协议参数。
     * @return 当前 manifest 记录；创建时为空。
     */
    private fun <T> validateCurrentState(
        unlocked: UnlockedWorkspaceManifest,
        objectId: ObjectId,
        previousSnapshot: ObjectSnapshot<T>?,
        actorId: com.oruke.tagloom.core.model.ActorId,
        actorSequence: Long,
        deleted: Boolean,
        descriptor: CatalogDescriptor<T>,
    ): ManifestObjectRecord? {
        val record = unlocked.payload.objects.singleOrNull { candidate ->
            candidate.kind == descriptor.manifestKind && candidate.objectId == objectId.value
        }
        if (previousSnapshot == null) {
            require(record == null) { "Database 目录对象已经存在" }
            require(!deleted) { "不存在的目录对象不能直接删除" }
            return null
        }
        require(previousSnapshot.header.objectId == objectId) { "目录对象 ID 与旧快照不一致" }
        require(previousSnapshot.header.objectType == descriptor.objectType) { "目录对象旧快照类型不一致" }
        require(record?.revision == previousSnapshot.header.revision.toProtocolRevision()) { "目录对象 revision 已落后" }
        require(
            previousSnapshot.header.revision.actorId != actorId ||
                actorSequence > previousSnapshot.header.revision.sequence,
        ) { "同一 Actor 的目录对象 revision 必须严格递增" }
        return record
    }

    /**
     * 加密一个快照或 Change 并生成 manifest 摘要记录。
     *
     * @param T 明文值类型。
     * @param unlocked 当前工作区。
     * @param targetPath 最终受控路径。
     * @param kind manifest 类别。
     * @param manifestObjectId manifest 对象 ID。
     * @param revision 协议 revision。
     * @param envelopeObjectType 信封公开类型。
     * @param envelopeObjectId 信封对象 ID。
     * @param purpose 包装密钥用途。
     * @param serializer 明文 serializer。
     * @param value 明文值。
     * @return 密文 artifact 和摘要记录。
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
    ): CatalogEncryptedArtifact {
        val plaintext = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        val encrypted = try {
            EncryptionEnvelopeCodec.encode(
                envelopeCryptoService.encrypt(
                    EnvelopeEncryptionRequest(
                        EnvelopeIdentity(
                            unlocked.file.publicHeader.workspaceId,
                            envelopeObjectType,
                            envelopeObjectId,
                            revision,
                        ),
                        unlocked.file.publicHeader.activeKeyId,
                        purpose,
                        plaintext,
                    ),
                    unlocked.workspaceMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
        val digest = sha256(encrypted)
        return CatalogEncryptedArtifact(
            EncryptedTransactionArtifact(targetPath, encrypted, digest),
            ManifestObjectRecord(kind, manifestObjectId, revision, targetPath, digest, unlocked.file.publicHeader.activeKeyId),
        )
    }

    /**
     * 计算规范 JSON 摘要并清零短期编码。
     *
     * @param T 值类型。
     * @param serializer serializer。
     * @param value 待摘要值。
     * @return 小写十六进制摘要。
     */
    private fun <T> canonicalSha256(serializer: KSerializer<T>, value: T): String {
        val bytes = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 计算字节 SHA-256。
     *
     * @param bytes 待摘要字节。
     * @return 小写十六进制摘要。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    /**
     * 转换对象 revision 为 manifest 协议文本。
     *
     * @return `actorId:sequence`。
     */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    private companion object {
        /** 当前目录快照 schema。 */
        const val SNAPSHOT_SCHEMA_VERSION: Int = 1

        /** Change 信封固定公开类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"

        /** Database 协议描述。 */
        val DATABASE_DESCRIPTOR = CatalogDescriptor(
            DatabaseDefinition.serializer(),
            ObjectType.DATABASE,
            ManifestObjectKind.DATABASE,
            DatabaseCatalogSnapshotEnvelopeService.DATABASE_SNAPSHOT_OBJECT_TYPE,
            "database-definition",
            { database -> database.id.value },
            { id -> WorkspaceRelativePath("databases/$id.database.enc") },
        )

        /** View 协议描述。 */
        val VIEW_DESCRIPTOR = CatalogDescriptor(
            ViewDefinition.serializer(),
            ObjectType.VIEW,
            ManifestObjectKind.VIEW,
            DatabaseCatalogSnapshotEnvelopeService.VIEW_SNAPSHOT_OBJECT_TYPE,
            "view-definition",
            { view -> view.id.value },
            { id -> WorkspaceRelativePath("views/$id.view.enc") },
        )
    }
}

/** 保存一种 Database 目录对象的稳定协议参数。 */
private data class CatalogDescriptor<T>(
    /** payload serializer。 */ val serializer: KSerializer<T>,
    /** 快照头对象类型。 */ val objectType: ObjectType,
    /** manifest 类别。 */ val manifestKind: ManifestObjectKind,
    /** 信封公开类型。 */ val envelopeObjectType: String,
    /** Change 操作名称。 */ val protocolName: String,
    /** 稳定对象 ID 提取器。 */ val objectId: (T) -> String,
    /** 受控快照路径生成器。 */ val snapshotPath: (String) -> WorkspaceRelativePath,
)

/** 保存一个已准备但尚未发布的目录对象。 */
private data class PreparedCatalogObject<T>(
    /** 新权威快照。 */ val snapshot: ObjectSnapshot<T>,
    /** 新历史 Change。 */ val change: ObjectChange,
    /** 快照与 Change 密文。 */ val artifacts: List<CatalogEncryptedArtifact>,
    /** 用于结果分派的对象类型。 */ val objectType: ObjectType,
)

/** 保存事务 artifact 与对应 manifest 记录。 */
private data class CatalogEncryptedArtifact(
    /** 待发布密文。 */ val artifact: EncryptedTransactionArtifact,
    /** 确认点摘要记录。 */ val record: ManifestObjectRecord,
)

/** 保存生成 Change ID 的非循环稳定输入。 */
@Serializable
private data class CatalogChangeIdentitySeed(
    /** 目标对象 ID。 */ val objectId: String,
    /** 直接父 Change。 */ val parentChangeIds: List<String>,
    /** 写入 Actor。 */ val actorId: String,
    /** Actor 序号。 */ val actorSequence: Long,
    /** 事务 ID。 */ val transactionId: String,
    /** Change 种类。 */ val kind: String,
    /** payload 规范摘要。 */ val payloadSha256: String,
    /** 目标 revision。 */ val targetSnapshotRevision: String,
)
