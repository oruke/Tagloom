package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AgentThreadValidator
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

/**
 * 以 manifest 为原子确认点发布工作区 `AgentThread` 加密快照与对应 `ObjectChange`。
 *
 * @param cryptoProvider 提供 SHA-256 与密码学初始化能力。
 * @param envelopeCryptoService 生成用途隔离的快照与 Change 加密信封。
 * @param manifestService 生成下一版认证 manifest。
 * @param transactionCoordinator 暂存密文并原子替换 manifest。
 */
class AgentThreadSnapshotTransactionService(
    private val cryptoProvider: CryptoProvider,
    private val envelopeCryptoService: EnvelopeCryptoService,
    private val manifestService: WorkspaceManifestService,
    private val transactionCoordinator: TransactionCoordinator,
) {
    /**
     * 校验协议不变量与乐观锁基线后，将新 `AgentThread` 快照及 Change 在同一事务中确认。
     *
     * @param unlocked 当前已认证工作区 manifest 与主密钥。
     * @param request 完整写入上下文与目标会话。
     * @return 新确认的会话快照、Change 及加密 manifest。
     */
    suspend fun commit(
        unlocked: UnlockedWorkspaceManifest,
        request: AgentThreadSnapshotTransactionRequest,
    ): AgentThreadSnapshotTransactionResult {
        require(request.thread.id == request.objectId) { "AgentThread payload ID 与请求对象 ID 不一致" }
        if (!request.deleted) {
            val violations = AgentThreadValidator.validate(request.thread)
            require(violations.isEmpty()) { "AgentThread 协议无效: ${violations.first()}" }
        }
        cryptoProvider.initialize()

        val currentRecord = validateCurrentState(unlocked, request)
        val revision = ObjectRevision(request.actorId, request.actorSequence)
        val revisionText = revision.toProtocolRevision()
        val parentChangeIds = request.previousSnapshot?.header?.lastChangeId
            ?.takeIf { changeId ->
                unlocked.payload.objects.any { record ->
                    record.kind == ManifestObjectKind.CHANGE && record.objectId == changeId.value
                }
            }
            ?.let(::listOf)
            .orEmpty()
        val kind = when {
            request.previousSnapshot == null -> ChangeKind.CREATE
            request.deleted -> ChangeKind.DELETE
            else -> ChangeKind.UPDATE
        }
        val seed = AgentThreadChangeIdentitySeed(
            objectId = request.objectId.value,
            parentChangeIds = parentChangeIds.map(ChangeId::value),
            actorId = request.actorId.value,
            actorSequence = request.actorSequence,
            transactionId = request.transactionId.value,
            kind = kind.name,
            threadSha256 = canonicalSha256(AgentThread.serializer(), request.thread),
            targetSnapshotRevision = revisionText,
        )
        val change = ObjectChange(
            changeId = ChangeId(canonicalSha256(AgentThreadChangeIdentitySeed.serializer(), seed)),
            objectId = request.objectId,
            parentChangeIds = parentChangeIds,
            actorId = request.actorId,
            hybridLogicalTime = request.hybridLogicalTime,
            baseSnapshotRevision = request.previousSnapshot?.header?.revision,
            transactionId = request.transactionId,
            kind = kind,
            operations = listOf(
                ChangeOperation(
                    type = if (request.deleted) "delete-agent-thread" else "replace-agent-thread",
                    payload = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), request.thread),
                ),
            ),
        )
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = request.previousSnapshot?.header?.schemaVersion ?: SNAPSHOT_SCHEMA_VERSION,
                objectId = request.objectId,
                objectType = ObjectType.AGENT_THREAD,
                revision = revision,
                lastChangeId = change.changeId,
                previousSnapshotHash = currentRecord?.encryptedSha256,
                createdAt = request.previousSnapshot?.header?.createdAt ?: request.committedAt,
                updatedAt = request.committedAt,
                deleted = request.deleted,
            ),
            payload = request.thread,
        )
        val snapshotPath = WorkspaceRelativePath("threads/${request.objectId.value}.thread.enc")
        val changePath = WorkspaceRelativePath("changes/${change.changeId.value}.change.enc")
        val snapshotBytes = encryptEnvelope(
            unlocked = unlocked,
            objectType = AgentThreadSnapshotEnvelopeService.AGENT_THREAD_SNAPSHOT_OBJECT_TYPE,
            objectId = request.objectId.value,
            revision = revisionText,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            serializer = ObjectSnapshot.serializer(AgentThread.serializer()),
            value = snapshot,
        )
        val changeBytes = encryptEnvelope(
            unlocked = unlocked,
            objectType = CHANGE_OBJECT_TYPE,
            objectId = change.changeId.value,
            revision = revisionText,
            purpose = WrappingKeyPurpose.CHANGE,
            serializer = ObjectChange.serializer(),
            value = change,
        )
        val snapshotDigest = sha256(snapshotBytes)
        val changeDigest = sha256(changeBytes)
        val encryptedManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = request.transactionId,
            upserts = listOf(
                ManifestObjectRecord(
                    kind = ManifestObjectKind.AGENT_THREAD,
                    objectId = request.objectId.value,
                    revision = revisionText,
                    path = snapshotPath,
                    encryptedSha256 = snapshotDigest,
                    keyId = unlocked.file.publicHeader.activeKeyId,
                ),
                ManifestObjectRecord(
                    kind = ManifestObjectKind.CHANGE,
                    objectId = change.changeId.value,
                    revision = revisionText,
                    path = changePath,
                    encryptedSha256 = changeDigest,
                    keyId = unlocked.file.publicHeader.activeKeyId,
                ),
            ),
            removedPaths = emptySet(),
        )
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = request.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = listOf(
                    EncryptedTransactionArtifact(snapshotPath, snapshotBytes, snapshotDigest),
                    EncryptedTransactionArtifact(changePath, changeBytes, changeDigest),
                ),
                encryptedManifest = encryptedManifest,
                manifestSha256 = sha256(encryptedManifest),
            ),
        )
        return AgentThreadSnapshotTransactionResult(snapshot, change, encryptedManifest, commit)
    }

    /**
     * 校验调用方基线快照与当前 manifest 一致，防止静默覆盖并发确认的会话版本。
     *
     * @param unlocked 当前工作区 manifest。
     * @param request 写入请求。
     * @return 当前 manifest 中的会话记录；首次创建时为空。
     */
    private fun validateCurrentState(
        unlocked: UnlockedWorkspaceManifest,
        request: AgentThreadSnapshotTransactionRequest,
    ): ManifestObjectRecord? {
        val currentRecord = unlocked.payload.objects.singleOrNull { record ->
            record.kind == ManifestObjectKind.AGENT_THREAD && record.objectId == request.objectId.value
        }
        val previous = request.previousSnapshot
        if (previous == null) {
            require(currentRecord == null) { "AgentThread 已存在，不能按新建方式覆盖" }
            require(!request.deleted) { "不存在的 AgentThread 不能直接删除" }
            return null
        }
        require(previous.header.objectId == request.objectId) { "AgentThread 旧快照对象 ID 不一致" }
        require(previous.header.objectType == ObjectType.AGENT_THREAD) { "AgentThread 旧快照类型不一致" }
        require(currentRecord?.revision == previous.header.revision.toProtocolRevision()) {
            "AgentThread revision 已落后于当前 manifest"
        }
        require(
            previous.header.revision.actorId != request.actorId ||
                request.actorSequence > previous.header.revision.sequence,
        ) {
            "同一 Actor 的 AgentThread revision 必须严格递增"
        }
        return currentRecord
    }

    /**
     * 将业务载荷编码后通过 `EnvelopeCryptoService` 双层加密，并在 finally 中擦除短期明文字节。
     *
     * @param T 明文对象类型。
     * @param unlocked 当前工作区 manifest 与主密钥。
     * @param objectType 信封公开对象类型。
     * @param objectId 信封绑定对象 ID。
     * @param revision 信封绑定协议 revision。
     * @param purpose HKDF 包装密钥隔离用途。
     * @param serializer 明文 serializer。
     * @param value 待加密对象。
     * @return 编码后的二进制加密信封。
     */
    private suspend fun <T> encryptEnvelope(
        unlocked: UnlockedWorkspaceManifest,
        objectType: String,
        objectId: String,
        revision: String,
        purpose: WrappingKeyPurpose,
        serializer: KSerializer<T>,
        value: T,
    ): ByteArray {
        val plaintext = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        return try {
            val envelope = envelopeCryptoService.encrypt(
                request = EnvelopeEncryptionRequest(
                    identity = EnvelopeIdentity(
                        workspaceId = unlocked.file.publicHeader.workspaceId,
                        objectType = objectType,
                        objectId = objectId,
                        revision = revision,
                    ),
                    keyId = unlocked.file.publicHeader.activeKeyId,
                    wrappingKeyPurpose = purpose,
                    plaintext = plaintext,
                ),
                workspaceMasterKey = unlocked.workspaceMasterKey,
            )
            EncryptionEnvelopeCodec.encode(envelope)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 计算规范 JSON 载荷的 SHA-256 小写十六进制摘要并擦除临时明文字节。
     *
     * @param T 载荷类型。
     * @param serializer 对应 serializer。
     * @param value 待摘要值。
     * @return 64 位小写十六进制摘要。
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
     * 计算字节数组的 SHA-256 小写十六进制摘要。
     *
     * @param bytes 输入字节。
     * @return 64 位小写十六进制字符串。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    /**
     * 格式化对象 revision 为 manifest 协议表示。
     *
     * @return `actorId:sequence` 字符串。
     */
    private fun ObjectRevision.toProtocolRevision(): String = "${actorId.value}:$sequence"

    private companion object {
        /** 当前会话快照头 schema 版本。 */
        const val SNAPSHOT_SCHEMA_VERSION: Int = 1

        /** Change 信封公开类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"
    }
}

/**
 * 保存生成 `AgentThread` 逻辑 Change ID 所需的确定性非循环种子。
 */
@Serializable
private data class AgentThreadChangeIdentitySeed(
    /** 目标会话 ID。 */
    val objectId: String,
    /** 直接父 Change ID 列表。 */
    val parentChangeIds: List<String>,
    /** 写入 Actor。 */
    val actorId: String,
    /** Actor 序号。 */
    val actorSequence: Long,
    /** 所属事务 ID。 */
    val transactionId: String,
    /** Change 类型名称。 */
    val kind: String,
    /** 会话规范 JSON 摘要。 */
    val threadSha256: String,
    /** 目标快照协议 revision。 */
    val targetSnapshotRevision: String,
)
