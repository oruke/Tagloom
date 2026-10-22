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
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.KSerializer

/**
 * 认证并解码工作区 Tag 与 PropertyDefinition 权威快照信封。
 *
 * @param envelopeCryptoService 执行 AEAD 和用途隔离解密的服务。
 */
class WorkspaceSchemaSnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并核对 TagDefinition 信封、快照头和 manifest 身份。
     *
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的标签对象 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @return 已认证标签快照。
     */
    suspend fun decryptTag(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<TagDefinition> = decrypt(
        encryptedBytes = encryptedBytes,
        workspaceId = workspaceId,
        objectId = objectId,
        revision = revision,
        keyId = keyId,
        workspaceMasterKey = workspaceMasterKey,
        envelopeObjectType = TAG_SNAPSHOT_OBJECT_TYPE,
        expectedObjectType = ObjectType.TAG,
        serializer = TagDefinition.serializer(),
    )

    /**
     * 解密并核对 PropertyDefinition 信封、快照头和 manifest 身份。
     *
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的属性对象 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @return 已认证属性定义快照。
     */
    suspend fun decryptProperty(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<PropertyDefinition> = decrypt(
        encryptedBytes = encryptedBytes,
        workspaceId = workspaceId,
        objectId = objectId,
        revision = revision,
        keyId = keyId,
        workspaceMasterKey = workspaceMasterKey,
        envelopeObjectType = PROPERTY_SNAPSHOT_OBJECT_TYPE,
        expectedObjectType = ObjectType.PROPERTY,
        serializer = PropertyDefinition.serializer(),
    )

    /**
     * 解密一种 schema 快照并验证通用身份字段。
     *
     * @param T schema payload 类型。
     * @param encryptedBytes 完整二进制信封。
     * @param workspaceId 当前工作区 ID。
     * @param objectId manifest 声明的对象 ID。
     * @param revision manifest 声明的 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前会话主密钥。
     * @param envelopeObjectType 信封公开对象类型。
     * @param expectedObjectType 快照头业务对象类型。
     * @param serializer schema payload serializer。
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
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(workspaceId, envelopeObjectType, objectId.value, revision),
                expectedKeyId = keyId,
                expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
            ),
            workspaceMasterKey = workspaceMasterKey,
        )
        return try {
            val snapshot = CanonicalJson.decodeFromString(
                ObjectSnapshot.serializer(serializer),
                plaintext.decodeToString(),
            )
            require(snapshot.header.objectId == objectId) { "Schema 快照对象 ID 不一致" }
            require(snapshot.header.schemaVersion in 1..2) { "schema-version-unsupported" }
            require(snapshot.header.objectType == expectedObjectType) { "Schema 快照类型不一致" }
            require("${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == revision) {
                "Schema 快照 revision 不一致"
            }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        /** TagDefinition 快照信封公开类型。 */
        const val TAG_SNAPSHOT_OBJECT_TYPE: String = "tag-snapshot"

        /** PropertyDefinition 快照信封公开类型。 */
        const val PROPERTY_SNAPSHOT_OBJECT_TYPE: String = "property-snapshot"
    }
}
