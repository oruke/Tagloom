package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.KSerializer

/**
 * 认证并解码 DatabaseDefinition 与 ViewDefinition 权威快照信封。
 *
 * @param envelopeCryptoService 执行 AEAD 和用途隔离解密的服务。
 */
class DatabaseCatalogSnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并核对 DatabaseDefinition 信封与 manifest 身份。
     *
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的对象 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @return 已认证 Database 快照。
     */
    suspend fun decryptDatabase(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<DatabaseDefinition> = decrypt(
        encryptedBytes,
        workspaceId,
        objectId,
        revision,
        keyId,
        workspaceMasterKey,
        DATABASE_SNAPSHOT_OBJECT_TYPE,
        ObjectType.DATABASE,
        DatabaseDefinition.serializer(),
    )

    /**
     * 解密并核对 ViewDefinition 信封与 manifest 身份。
     *
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的对象 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @return 已认证 View 快照。
     */
    suspend fun decryptView(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<ViewDefinition> = decrypt(
        encryptedBytes,
        workspaceId,
        objectId,
        revision,
        keyId,
        workspaceMasterKey,
        VIEW_SNAPSHOT_OBJECT_TYPE,
        ObjectType.VIEW,
        ViewDefinition.serializer(),
    )

    /**
     * 解密一种目录对象并验证信封和快照头身份。
     *
     * @param T payload 类型。
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 对象 ID。
     * @param revision manifest revision。
     * @param keyId manifest 主密钥版本。
     * @param workspaceMasterKey 当前主密钥。
     * @param envelopeObjectType 信封公开类型。
     * @param expectedObjectType 快照头业务类型。
     * @param serializer payload serializer。
     * @return 已认证对象快照。
     */
    private suspend fun <T> decrypt(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
        envelopeObjectType: String,
        expectedObjectType: ObjectType,
        serializer: KSerializer<T>,
    ): ObjectSnapshot<T> {
        val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
        val plaintext = envelopeCryptoService.decrypt(
            envelope,
            EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(workspaceId, envelopeObjectType, objectId.value, revision),
                expectedKeyId = keyId,
                expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
            ),
            workspaceMasterKey,
        )
        return try {
            val snapshot = CanonicalJson.decodeFromString(ObjectSnapshot.serializer(serializer), plaintext.decodeToString())
            require(snapshot.header.objectId == objectId) { "Database 目录快照对象 ID 不一致" }
            require(snapshot.header.objectType == expectedObjectType) { "Database 目录快照类型不一致" }
            require("${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == revision) {
                "Database 目录快照 revision 不一致"
            }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        /** Database 快照信封公开类型。 */
        const val DATABASE_SNAPSHOT_OBJECT_TYPE: String = "database-snapshot"

        /** View 快照信封公开类型。 */
        const val VIEW_SNAPSHOT_OBJECT_TYPE: String = "view-snapshot"
    }
}
