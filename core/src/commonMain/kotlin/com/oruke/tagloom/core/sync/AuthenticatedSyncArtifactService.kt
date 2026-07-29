package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.crypto.EncryptionEnvelope
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 在领域 Change/Note Snapshot 与签名端到端密文 artifact 之间提供唯一 typed codec。
 *
 * 服务端仍只处理盲密文；该服务只应在持有工作区主密钥和设备公钥目录的客户端调用。
 */
class AuthenticatedSyncArtifactService(
    /** 创建和验证随机 DEK 加密信封。 */
    private val envelopeCryptoService: EnvelopeCryptoService,
    /** 创建和验证内容 ID、密文摘要与长度。 */
    private val integrityService: SyncArtifactIntegrityService,
    /** 创建和验证原始 Actor Ed25519 签名。 */
    private val signatureService: DeviceArtifactSignatureService,
    /** 提供标准 Base64 密文编解码。 */
    private val payloadCodec: SyncPayloadCodec,
) {
    /**
     * 把逻辑 ObjectChange 加密、内容寻址并由原始 Actor 签名。
     *
     * @param workspaceId 当前工作区。
     * @param keyId 当前活动 Workspace Master Key 版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param change 待同步逻辑 Change。
     * @param actorSequence Actor 同步序号。
     * @param parents 已认证直接父 artifacts。
     * @param privateSigningSeed Change Actor 的 Ed25519 私钥 seed。
     * @return 可上传盲同步服务的已认证密文 Change。
     */
    suspend fun createChange(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        change: ObjectChange,
        actorSequence: Long,
        parents: List<AuthenticatedObjectChangeArtifact>,
        privateSigningSeed: SecretKeyMaterial,
    ): AuthenticatedObjectChangeArtifact {
        requireChangeParents(change, parents)
        val artifact = createArtifact(
            workspaceId = workspaceId,
            keyId = keyId,
            workspaceMasterKey = workspaceMasterKey,
            kind = SyncArtifactKind.CHANGE,
            objectId = change.objectId,
            parentArtifactIds = parents.map { parent -> parent.artifact.artifactId },
            actorId = change.actorId,
            actorSequence = actorSequence,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = change.changeId.value,
            purpose = WrappingKeyPurpose.CHANGE,
            plaintext = CanonicalJson.encodeToString(ObjectChange.serializer(), change).encodeToByteArray(),
            privateSigningSeed = privateSigningSeed,
        )
        return AuthenticatedObjectChangeArtifact(artifact, change)
    }

    /**
     * 验证并打开远端加密 Change，同时核对公开父 artifact 与加密父 Change 的一一映射。
     *
     * @param artifact 待打开密文 Change。
     * @param expectedKeyId 当前工作区允许的主密钥版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param authorPublicKey artifact 原始 Actor 的 Ed25519 公钥。
     * @param parents 已先完成认证的直接父 artifacts。
     * @return 可交给 Change DAG 与合并服务的认证 Change。
     */
    suspend fun openChange(
        artifact: BlindSyncArtifact,
        expectedKeyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        authorPublicKey: ByteArray,
        parents: List<AuthenticatedObjectChangeArtifact>,
    ): AuthenticatedObjectChangeArtifact {
        require(artifact.descriptor.kind == SyncArtifactKind.CHANGE) { "同步 artifact 不是 Change" }
        val plaintext = openArtifact(
            artifact = artifact,
            expectedKeyId = expectedKeyId,
            workspaceMasterKey = workspaceMasterKey,
            authorPublicKey = authorPublicKey,
            envelopeObjectType = CHANGE_OBJECT_TYPE,
            envelopeObjectId = null,
            purpose = WrappingKeyPurpose.CHANGE,
        )
        val change = try {
            CanonicalJson.decodeFromString(ObjectChange.serializer(), plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
        val descriptor = artifact.descriptor
        require(change.changeId.value == decodeEnvelope(artifact).header.objectId) { "Change ID 与信封身份不一致" }
        require(change.objectId == descriptor.objectId) { "Change 对象与公开描述符不一致" }
        require(change.actorId == descriptor.actorId) { "Change Actor 与公开描述符不一致" }
        requireChangeParents(change, parents)
        require(artifact.descriptor.parentArtifactIds == parents.map { parent -> parent.artifact.artifactId }.sorted()) {
            "Change 公开父 artifact 与认证父节点不一致"
        }
        return AuthenticatedObjectChangeArtifact(artifact, change)
    }

    /**
     * 把 Note 快照副本加密、内容寻址并由产生该 revision 的 Actor 签名。
     *
     * @param workspaceId 当前工作区。
     * @param keyId 当前活动主密钥版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param snapshot 待同步 Note 快照。
     * @param privateSigningSeed 快照 Actor 的 Ed25519 私钥 seed。
     * @return 可上传的已认证快照 artifact。
     */
    suspend fun createNoteSnapshot(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        snapshot: ObjectSnapshot<Note>,
        privateSigningSeed: SecretKeyMaterial,
    ): AuthenticatedNoteSnapshotArtifact {
        require(snapshot.header.objectType == ObjectType.NOTE) { "快照对象类型不是 Note" }
        require(NoteValidator.validate(snapshot.payload).isEmpty()) { "Note 快照领域校验失败" }
        val revision = snapshot.header.revision
        val artifact = createArtifact(
            workspaceId = workspaceId,
            keyId = keyId,
            workspaceMasterKey = workspaceMasterKey,
            kind = SyncArtifactKind.SNAPSHOT,
            objectId = snapshot.header.objectId,
            parentArtifactIds = emptyList(),
            actorId = revision.actorId,
            actorSequence = revision.sequence,
            envelopeObjectType = NOTE_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = snapshot.header.objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
            plaintext = CanonicalJson.encodeToString(
                ObjectSnapshot.serializer(Note.serializer()),
                snapshot,
            ).encodeToByteArray(),
            privateSigningSeed = privateSigningSeed,
        )
        return AuthenticatedNoteSnapshotArtifact(artifact, snapshot)
    }

    /**
     * 验证并打开远端 Note Snapshot 副本；返回值仍不是本地权威状态。
     *
     * @param artifact 待打开 Snapshot artifact。
     * @param expectedKeyId 当前工作区允许的主密钥版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param authorPublicKey artifact 原始 Actor 的 Ed25519 公钥。
     * @return 可作为远端候选或恢复材料的认证 Note 快照。
     */
    suspend fun openNoteSnapshot(
        artifact: BlindSyncArtifact,
        expectedKeyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        authorPublicKey: ByteArray,
    ): AuthenticatedNoteSnapshotArtifact {
        require(artifact.descriptor.kind == SyncArtifactKind.SNAPSHOT) { "同步 artifact 不是 Snapshot" }
        val objectId = requireNotNull(artifact.descriptor.objectId) { "Note Snapshot 缺少对象 ID" }
        val plaintext = openArtifact(
            artifact = artifact,
            expectedKeyId = expectedKeyId,
            workspaceMasterKey = workspaceMasterKey,
            authorPublicKey = authorPublicKey,
            envelopeObjectType = NOTE_SNAPSHOT_OBJECT_TYPE,
            envelopeObjectId = objectId.value,
            purpose = WrappingKeyPurpose.SNAPSHOT,
        )
        val snapshot = try {
            CanonicalJson.decodeFromString(
                ObjectSnapshot.serializer(Note.serializer()),
                plaintext.decodeToString(),
            )
        } finally {
            plaintext.fill(0)
        }
        val descriptor = artifact.descriptor
        require(snapshot.header.objectType == ObjectType.NOTE && snapshot.header.objectId == objectId) {
            "Note Snapshot 领域身份不一致"
        }
        require(snapshot.header.revision.actorId == descriptor.actorId) { "Note Snapshot Actor 不一致" }
        require(snapshot.header.revision.sequence == descriptor.actorSequence) { "Note Snapshot revision 不一致" }
        require(NoteValidator.validate(snapshot.payload).isEmpty()) { "Note Snapshot 领域校验失败" }
        return AuthenticatedNoteSnapshotArtifact(artifact, snapshot)
    }

    /**
     * 创建用途隔离信封、内容 ID 与设备签名，并始终清零短期明文和信封字节。
     *
     * @param workspaceId 当前工作区。
     * @param keyId 活动主密钥版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param kind 同步载荷类型。
     * @param objectId 可选业务对象 ID。
     * @param parentArtifactIds 公开父 artifact IDs。
     * @param actorId 原始 Actor。
     * @param actorSequence Actor 同步序号。
     * @param envelopeObjectType 信封对象类型。
     * @param envelopeObjectId 信封对象 ID。
     * @param purpose wrapping key 用途。
     * @param plaintext 待加密规范明文字节；本方法取得并清零该数组。
     * @param privateSigningSeed 原始 Actor 私钥 seed。
     * @return 已签名盲同步载荷。
     */
    private suspend fun createArtifact(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        kind: SyncArtifactKind,
        objectId: com.oruke.tagloom.core.model.ObjectId?,
        parentArtifactIds: List<String>,
        actorId: ActorId,
        actorSequence: Long,
        envelopeObjectType: String,
        envelopeObjectId: String,
        purpose: WrappingKeyPurpose,
        plaintext: ByteArray,
        privateSigningSeed: SecretKeyMaterial,
    ): BlindSyncArtifact {
        val revision = "${actorId.value}:$actorSequence"
        val envelopeBytes = try {
            EncryptionEnvelopeCodec.encode(
                envelopeCryptoService.encrypt(
                    EnvelopeEncryptionRequest(
                        EnvelopeIdentity(workspaceId, envelopeObjectType, envelopeObjectId, revision),
                        keyId,
                        purpose,
                        plaintext,
                    ),
                    workspaceMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
        return try {
            val unsigned = integrityService.create(
                workspaceId,
                kind,
                objectId,
                parentArtifactIds,
                actorId,
                actorSequence,
                envelopeBytes,
            )
            signatureService.sign(unsigned, privateSigningSeed)
        } finally {
            envelopeBytes.fill(0)
        }
    }

    /**
     * 依次验证内容寻址、作者签名、信封身份和 AEAD，并返回短期明文。
     *
     * @param artifact 待打开载荷。
     * @param expectedKeyId 预期主密钥版本。
     * @param workspaceMasterKey 当前会话工作区主密钥。
     * @param authorPublicKey 原始 Actor 公钥。
     * @param envelopeObjectType 预期信封对象类型。
     * @param envelopeObjectId 可选预期信封对象 ID；Change 在解析明文后核对。
     * @param purpose 预期 wrapping key 用途。
     * @return 调用方负责尽快清零的认证明文。
     */
    private suspend fun openArtifact(
        artifact: BlindSyncArtifact,
        expectedKeyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        authorPublicKey: ByteArray,
        envelopeObjectType: String,
        envelopeObjectId: String?,
        purpose: WrappingKeyPurpose,
    ): ByteArray {
        check(integrityService.verify(artifact)) { "同步 artifact 内容寻址校验失败" }
        check(signatureService.verify(artifact, authorPublicKey)) { "同步 artifact 设备签名校验失败" }
        val envelope = decodeEnvelope(artifact)
        val descriptor = artifact.descriptor
        val revision = "${descriptor.actorId.value}:${descriptor.actorSequence}"
        return envelopeCryptoService.decrypt(
            envelope,
            EnvelopeDecryptionContext(
                EnvelopeIdentity(
                    descriptor.workspaceId,
                    envelopeObjectType,
                    envelopeObjectId ?: envelope.header.objectId,
                    revision,
                ),
                expectedKeyId,
                purpose,
            ),
            workspaceMasterKey,
        )
    }

    /**
     * 解码完整二进制信封并清零 Base64 解码缓冲区。
     *
     * @param artifact 待解码同步载荷。
     * @return 结构校验通过的加密信封。
     */
    private fun decodeEnvelope(artifact: BlindSyncArtifact): EncryptionEnvelope {
        val bytes = payloadCodec.decode(artifact.payloadBase64)
        return try {
            EncryptionEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 核对逻辑父 Change 与公开父 artifact 的对象和 ID 映射。
     *
     * @param change 待验证 Change。
     * @param parents 已认证直接父 artifacts。
     */
    private fun requireChangeParents(
        change: ObjectChange,
        parents: List<AuthenticatedObjectChangeArtifact>,
    ) {
        require(parents.map { parent -> parent.artifact.artifactId }.distinct().size == parents.size) {
            "Change 父 artifact 不能重复"
        }
        require(parents.all { parent -> parent.change.objectId == change.objectId }) { "Change 父节点跨对象" }
        require(change.parentChangeIds == ObjectChangeDag.mergeParentIds(parents.map { parent -> parent.change.changeId })) {
            "Change 加密父 ID 与认证父节点不一致"
        }
    }

    private companion object {
        /** Change 信封固定对象类型。 */
        const val CHANGE_OBJECT_TYPE: String = "object-change"

        /** Note Snapshot 信封固定对象类型。 */
        const val NOTE_SNAPSHOT_OBJECT_TYPE: String = "note-snapshot"
    }
}
