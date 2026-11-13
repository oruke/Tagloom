package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.ai.WorkspaceConfigValidator
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
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 认证并解码 `WorkspaceConfig` 权威快照加密信封。
 *
 * 外层使用 HKDF `WrappingKeyPurpose.SNAPSHOT` 派生包装密钥保护随机 DEK，解密后短期明文字节立即清零。
 *
 * @param envelopeCryptoService 执行双层 AEAD 与身份绑定解密的服务。
 */
class WorkspaceConfigSnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并核对 `WorkspaceConfig` 的信封身份、通用快照头与领域协议约束。
     *
     * @param encryptedBytes 完整二进制加密信封。
     * @param workspaceId 当前工作区公开标识。
     * @param objectId manifest 声明的配置对象 ID。
     * @param revision manifest 声明的逻辑 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前已解锁会话主密钥。
     * @return 已通过密码学与协议双重校验的工作区配置快照。
     */
    suspend fun decrypt(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<WorkspaceConfig> {
        val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
        val plaintext = envelopeCryptoService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = workspaceId,
                    objectType = WORKSPACE_CONFIG_SNAPSHOT_OBJECT_TYPE,
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
                ObjectSnapshot.serializer(WorkspaceConfig.serializer()),
                plaintext.decodeToString(),
            )
            require(snapshot.header.objectId == objectId) { "WorkspaceConfig 快照对象 ID 不一致" }
            require(snapshot.header.objectType == ObjectType.WORKSPACE_CONFIG) { "WorkspaceConfig 快照类型不一致" }
            require(snapshot.payload.id == objectId) { "WorkspaceConfig payload ID 与快照头不一致" }
            require("${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == revision) {
                "WorkspaceConfig 快照 revision 不一致"
            }
            if (!snapshot.header.deleted) {
                require(WorkspaceConfigValidator.validate(snapshot.payload).isEmpty()) {
                    "WorkspaceConfig 快照协议校验未通过"
                }
            }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        /** `WorkspaceConfig` 快照信封公开对象类型标识。 */
        const val WORKSPACE_CONFIG_SNAPSHOT_OBJECT_TYPE: String = "workspace-config-snapshot"
    }
}
