package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 认证并解码工作区中的 Note 快照信封。
 *
 * @param envelopeCryptoService 执行用途隔离 AEAD 解密的领域服务。
 */
class NoteSnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并校验 Note 快照身份、revision、schema 和 Block/Annotation 结构。
     *
     * @param encryptedBytes 完整版本化加密信封。
     * @param workspaceId 当前已解锁工作区 ID。
     * @param objectId manifest 声明的 Note ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前解锁会话主密钥。
     * @return 完成全部认证的 Note 快照。
     */
    suspend fun open(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<Note> {
        val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
        val plaintext = envelopeCryptoService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = workspaceId,
                    objectType = NOTE_SNAPSHOT_OBJECT_TYPE,
                    objectId = objectId.value,
                    revision = revision,
                ),
                expectedKeyId = keyId,
                expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
            ),
            workspaceMasterKey = workspaceMasterKey,
        )
        return try {
            val snapshot = CanonicalJson.decodeFromString(
                ObjectSnapshot.serializer(Note.serializer()),
                plaintext.decodeToString(),
            )
            require(snapshot.header.objectType == ObjectType.NOTE) { "快照对象类型不是 Note" }
            require(snapshot.header.objectId == objectId) { "Note 快照对象身份不一致" }
            require(snapshot.header.revision.toProtocolRevision() == revision) { "Note 快照 revision 不一致" }
            require(NoteValidator.validate(snapshot.payload).isEmpty()) { "Note 快照结构无效" }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 把对象 revision 转换为信封与 manifest 使用的协议文本。
     *
     * @return `actorId:sequence` 格式 revision。
     */
    private fun com.oruke.tagloom.core.model.ObjectRevision.toProtocolRevision(): String =
        "${actorId.value}:$sequence"

    private companion object {
        /** Note 快照信封固定对象类型。 */
        const val NOTE_SNAPSHOT_OBJECT_TYPE: String = "note-snapshot"
    }
}
