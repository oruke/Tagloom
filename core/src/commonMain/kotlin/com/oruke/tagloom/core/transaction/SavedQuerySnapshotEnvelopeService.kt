package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 认证并解码 SavedQuery 权威快照信封。
 *
 * @param envelopeCryptoService 执行 AEAD 和用途隔离解密的服务。
 */
class SavedQuerySnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并核对 SavedQuery 的信封身份、通用快照头和 schema。
     *
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的查询 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @return 已认证保存查询快照。
     */
    suspend fun decrypt(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<SavedQuery> {
        val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
        val plaintext = envelopeCryptoService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = workspaceId,
                    objectType = SAVED_QUERY_SNAPSHOT_OBJECT_TYPE,
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
                ObjectSnapshot.serializer(SavedQuery.serializer()),
                plaintext.decodeToString(),
            )
            require(snapshot.header.objectId == objectId) { "SavedQuery 快照对象 ID 不一致" }
            require(snapshot.header.objectType == ObjectType.SAVED_QUERY) { "SavedQuery 快照类型不一致" }
            require("${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == revision) {
                "SavedQuery 快照 revision 不一致"
            }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        /** SavedQuery 信封公开对象类型。 */
        const val SAVED_QUERY_SNAPSHOT_OBJECT_TYPE: String = "saved-query-snapshot"
    }
}
