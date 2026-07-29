package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.SyncArtifactDescriptor
import java.security.MessageDigest
import java.util.Base64

/** 在不持有工作区密钥的 JVM 服务端验证密文内容寻址完整性。 */
object JvmSyncArtifactVerifier {
    /**
     * 验证 Base64、密文字节数、payload SHA-256 与描述符内容 ID。
     *
     * @param artifact 待验证盲存储载荷。
     * @return 公开完整性全部通过时为真。
     */
    fun verify(artifact: BlindSyncArtifact): Boolean {
        val payload = runCatching { Base64.getDecoder().decode(artifact.payloadBase64) }.getOrNull() ?: return false
        return try {
            payload.size == artifact.descriptor.payloadByteLength &&
                sha256(payload) == artifact.descriptor.payloadSha256 &&
                descriptorContentId(artifact.descriptor) == artifact.artifactId
        } finally {
            payload.fill(0)
        }
    }

    /**
     * 计算同步公开描述符的内容 ID。
     *
     * @param descriptor 已校验描述符。
     * @return 小写十六进制 SHA-256。
     */
    internal fun descriptorContentId(descriptor: SyncArtifactDescriptor): String {
        val bytes = CanonicalJson.encodeToString(SyncArtifactDescriptor.serializer(), descriptor).encodeToByteArray()
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 计算字节数组 SHA-256 并转换为小写十六进制。
     *
     * @param bytes 待摘要字节。
     * @return 64 字符小写十六进制摘要。
     */
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}
