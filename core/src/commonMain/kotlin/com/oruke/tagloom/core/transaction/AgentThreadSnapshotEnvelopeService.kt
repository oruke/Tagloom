package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AgentThreadValidator
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
 * 认证并解码 `AgentThread` 权威会话快照加密信封。
 *
 * 外层使用 HKDF `WrappingKeyPurpose.SNAPSHOT` 派生包装密钥保护随机 DEK，解密后短期明文字节立即擦除。
 *
 * @param envelopeCryptoService 执行双层 AEAD 与身份绑定解密的服务。
 */
class AgentThreadSnapshotEnvelopeService(
    private val envelopeCryptoService: EnvelopeCryptoService,
) {
    /**
     * 解密并核对 `AgentThread` 的信封身份、通用快照头与领域协议约束。
     *
     * @param encryptedBytes 完整二进制加密信封。
     * @param workspaceId 当前工作区公开标识。
     * @param objectId manifest 声明的会话对象 ID。
     * @param revision manifest 声明的逻辑 revision。
     * @param keyId manifest 声明的主密钥版本。
     * @param workspaceMasterKey 当前已解锁会话主密钥。
     * @return 已通过密码学与协议双重校验的 Agent 会话快照。
     */
    suspend fun decrypt(
        encryptedBytes: ByteArray,
        workspaceId: WorkspaceId,
        objectId: ObjectId,
        revision: String,
        keyId: KeyId,
        workspaceMasterKey: SecretKeyMaterial,
    ): ObjectSnapshot<AgentThread> {
        val envelope = EncryptionEnvelopeCodec.decode(encryptedBytes)
        val plaintext = envelopeCryptoService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = workspaceId,
                    objectType = AGENT_THREAD_SNAPSHOT_OBJECT_TYPE,
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
                ObjectSnapshot.serializer(AgentThread.serializer()),
                plaintext.decodeToString(),
            )
            require(snapshot.header.objectId == objectId) { "AgentThread 快照对象 ID 不一致" }
            require(snapshot.header.objectType == ObjectType.AGENT_THREAD) { "AgentThread 快照类型不一致" }
            require(snapshot.payload.id == objectId) { "AgentThread payload ID 与快照头不一致" }
            require("${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == revision) {
                "AgentThread 快照 revision 不一致"
            }
            if (!snapshot.header.deleted) {
                require(AgentThreadValidator.validate(snapshot.payload).isEmpty()) {
                    "AgentThread 快照协议校验未通过"
                }
            }
            snapshot
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        /** `AgentThread` 快照信封公开对象类型标识。 */
        const val AGENT_THREAD_SNAPSHOT_OBJECT_TYPE: String = "agent-thread-snapshot"
    }
}
