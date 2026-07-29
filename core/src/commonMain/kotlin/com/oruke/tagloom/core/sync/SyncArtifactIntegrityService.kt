package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson

/** 在公共同步协议与平台 Base64 实现之间提供最小编码边界。 */
interface SyncPayloadCodec {
    /**
     * 把密文字节编码为无换行标准 Base64。
     *
     * @param payload 待编码密文字节。
     * @return 标准 Base64 文本。
     */
    fun encode(payload: ByteArray): String

    /**
     * 解码标准 Base64 密文。
     *
     * @param payloadBase64 待解码文本。
     * @return 新的密文字节数组。
     */
    fun decode(payloadBase64: String): ByteArray
}

/**
 * 创建并验证服务器可复算的同步内容 ID 与密文摘要。
 *
 * @param cryptoProvider 提供 SHA-256 的密码边界。
 * @param payloadCodec 提供平台 Base64 编解码。
 */
class SyncArtifactIntegrityService(
    /** 提供 SHA-256 的密码边界。 */
    private val cryptoProvider: CryptoProvider,
    /** 提供平台 Base64 编解码。 */
    private val payloadCodec: SyncPayloadCodec,
) {
    /**
     * 从完整加密信封创建内容寻址同步载荷。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 载荷类型。
     * @param objectId Change/Snapshot 对应对象。
     * @param parentArtifactIds Change 直接父内容 ID。
     * @param actorId 当前已认证设备或 Agent。
     * @param actorSequence Actor 本地严格递增序号。
     * @param encryptedPayload 已完成 AEAD 的完整信封字节。
     * @return 服务端可盲验证并幂等保存的载荷。
     */
    suspend fun create(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
        objectId: ObjectId?,
        parentArtifactIds: List<String>,
        actorId: ActorId,
        actorSequence: Long,
        encryptedPayload: ByteArray,
    ): BlindSyncArtifact {
        cryptoProvider.initialize()
        require(encryptedPayload.isNotEmpty()) { "同步密文载荷不能为空" }
        val payloadSha256 = cryptoProvider.sha256(encryptedPayload).toHex()
        val descriptor = SyncArtifactDescriptor(
            protocolVersion = CURRENT_SYNC_PROTOCOL_VERSION,
            workspaceId = workspaceId,
            kind = kind,
            objectId = objectId,
            parentArtifactIds = parentArtifactIds.distinct().sorted(),
            actorId = actorId,
            actorSequence = actorSequence,
            payloadSha256 = payloadSha256,
            payloadByteLength = encryptedPayload.size,
        )
        return BlindSyncArtifact(
            artifactId = descriptorContentId(descriptor),
            descriptor = descriptor,
            payloadBase64 = payloadCodec.encode(encryptedPayload),
        )
    }

    /**
     * 验证 Base64、字节长度、payload 摘要和公开描述符内容 ID。
     *
     * 这只能证明传输内容未被盲存储服务器篡改；客户端仍必须执行加密信封 AEAD 与身份校验后才能应用 Change。
     *
     * @param artifact 待验证载荷。
     * @return 全部公开完整性检查通过时为真。
     */
    suspend fun verify(artifact: BlindSyncArtifact): Boolean {
        cryptoProvider.initialize()
        val payload = runCatching { payloadCodec.decode(artifact.payloadBase64) }.getOrNull() ?: return false
        return try {
            payload.size == artifact.descriptor.payloadByteLength &&
                cryptoProvider.sha256(payload).toHex() == artifact.descriptor.payloadSha256 &&
                descriptorContentId(artifact.descriptor) == artifact.artifactId
        } finally {
            payload.fill(0)
        }
    }

    /**
     * 计算公开描述符的确定性内容 ID。
     *
     * @param descriptor 已校验同步描述符。
     * @return 小写十六进制 SHA-256。
     */
    private fun descriptorContentId(descriptor: SyncArtifactDescriptor): String {
        val canonical = CanonicalJson.encodeToString(SyncArtifactDescriptor.serializer(), descriptor)
            .encodeToByteArray()
        return try {
            cryptoProvider.sha256(canonical).toHex()
        } finally {
            canonical.fill(0)
        }
    }

    /**
     * 把摘要转换为固定小写十六进制文本。
     *
     * @return 每个字节固定两字符的文本。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
