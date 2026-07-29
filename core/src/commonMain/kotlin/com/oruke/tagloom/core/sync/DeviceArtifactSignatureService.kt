package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.crypto.DeviceSignatureProtocol
import com.oruke.tagloom.core.crypto.DeviceSignatureProvider
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.Serializable

/**
 * 为端到端密文同步载荷创建和验证 Ed25519 作者签名。
 *
 * 签名覆盖 artifactId 与完整公开描述符；artifactId 又绑定密文摘要，因此无需把大密文重复送入签名输入。
 */
class DeviceArtifactSignatureService(
    /** 提供 Ed25519 keypair、签名和验证原语的平台适配器。 */
    private val signatureProvider: DeviceSignatureProvider,
) {
    /**
     * 使用当前 Actor 的私钥 seed 签署盲同步载荷。
     *
     * @param artifact 已完成内容寻址但尚未签名的密文载荷。
     * @param privateKeySeed 当前 Actor 的 Ed25519 私钥恢复 seed。
     * @return 带固定小写十六进制签名的载荷副本。
     */
    suspend fun sign(
        artifact: BlindSyncArtifact,
        privateKeySeed: SecretKeyMaterial,
    ): BlindSyncArtifact {
        require(privateKeySeed.kind == SecretKeyKind.DEVICE_SIGNING_SEED) { "设备签名 seed 用途无效" }
        signatureProvider.initialize()
        val message = signingBytes(artifact)
        return try {
            val signature = signatureProvider.signDetached(message, privateKeySeed)
            try {
                require(signature.size == DeviceSignatureProtocol.SIGNATURE_BYTES) { "设备签名长度无效" }
                artifact.copy(authorSignatureHex = signature.toHex())
            } finally {
                signature.fill(0)
            }
        } finally {
            message.fill(0)
        }
    }

    /**
     * 验证载荷签名是否属于凭据登记的原始 Actor 公钥。
     *
     * @param artifact 待验证密文载荷。
     * @param publicKey 凭据登记的 32 字节 Ed25519 公钥。
     * @return 签名格式与认证全部有效时为真。
     */
    suspend fun verify(
        artifact: BlindSyncArtifact,
        publicKey: ByteArray,
    ): Boolean {
        if (publicKey.size != DeviceSignatureProtocol.PUBLIC_KEY_BYTES) return false
        val signature = artifact.authorSignatureHex?.hexToBytesOrNull() ?: return false
        if (signature.size != DeviceSignatureProtocol.SIGNATURE_BYTES) return false
        signatureProvider.initialize()
        val message = signingBytes(artifact)
        return try {
            signatureProvider.verifyDetached(message, signature, publicKey)
        } finally {
            signature.fill(0)
            message.fill(0)
        }
    }

    /**
     * 生成不包含签名自身的规范签名输入。
     *
     * @param artifact 已通过内容寻址校验的载荷。
     * @return UTF-8 规范 JSON 字节。
     */
    private fun signingBytes(artifact: BlindSyncArtifact): ByteArray = CanonicalJson.encodeToString(
        SyncArtifactSignaturePayload.serializer(),
        SyncArtifactSignaturePayload(artifact.artifactId, artifact.descriptor),
    ).encodeToByteArray()

    /**
     * 把签名字节转换为小写十六进制。
     *
     * @return 小写十六进制文本。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    /**
     * 安全解析小写十六进制签名。
     *
     * @return 解析字节；格式无效时为空。
     */
    private fun String.hexToBytesOrNull(): ByteArray? {
        if (!SYNC_SIGNATURE_PATTERN.matches(this)) return null
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

/** 描述设备签名覆盖的稳定公开同步身份。 */
@Serializable
private data class SyncArtifactSignaturePayload(
    /** 由描述符计算得到的内容 ID。 */
    val artifactId: String,
    /** 完整公开描述符，其中 payload SHA-256 绑定端到端密文。 */
    val descriptor: SyncArtifactDescriptor,
)
