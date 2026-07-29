package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 验证版本化二进制信封的确定性编码与损坏输入边界。
 */
class EncryptionEnvelopeCodecTest {
    /**
     * 验证完整信封经过二进制往返后所有认证字段和密文字节保持不变。
     */
    @Test
    fun binaryEnvelopeRoundTripsWithoutChangingAuthenticatedBytes() {
        val original = envelope()

        val decoded = EncryptionEnvelopeCodec.decode(EncryptionEnvelopeCodec.encode(original))

        assertEquals(original.header, decoded.header)
        assertContentEquals(original.wrapNonce, decoded.wrapNonce)
        assertContentEquals(original.payloadNonce, decoded.payloadNonce)
        assertContentEquals(original.wrappedDataKey, decoded.wrappedDataKey)
        assertContentEquals(original.ciphertext, decoded.ciphertext)
    }

    /**
     * 验证截断文件和外层长度伪造在进入 AEAD 前被拒绝。
     */
    @Test
    fun binaryEnvelopeRejectsTruncationAndLengthForgery() {
        val encoded = EncryptionEnvelopeCodec.encode(envelope())
        val truncated = encoded.copyOf(encoded.size - 1)
        val forgedLength = encoded.copyOf().also { bytes ->
            val ciphertextLengthLastByte = 7 + 1 + 4 + 4 + 3
            bytes[ciphertextLengthLastByte] = (bytes[ciphertextLengthLastByte].toInt() + 1).toByte()
        }

        assertFormatFailure(EnvelopeFormatFailureCode.LENGTH_MISMATCH, truncated)
        assertFormatFailure(EnvelopeFormatFailureCode.LENGTH_MISMATCH, forgedLength)
    }

    /**
     * 验证错误魔数和未知外层版本返回稳定错误码。
     */
    @Test
    fun binaryEnvelopeRejectsWrongMagicAndVersion() {
        val encoded = EncryptionEnvelopeCodec.encode(envelope())
        val wrongMagic = encoded.copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() }
        val unknownVersion = encoded.copyOf().also { bytes -> bytes[7] = 99 }

        assertFormatFailure(EnvelopeFormatFailureCode.INVALID_MAGIC, wrongMagic)
        assertFormatFailure(EnvelopeFormatFailureCode.UNSUPPORTED_VERSION, unknownVersion)
    }

    /**
     * 创建尺寸满足 v1 约束的测试信封。
     *
     * @return 使用固定非秘密字节的信封。
     */
    private fun envelope(): EncryptionEnvelope {
        val ciphertext = ByteArray(64) { index -> (index + 3).toByte() }
        return EncryptionEnvelope(
            header = EncryptionEnvelopeHeader(
                workspaceId = WorkspaceId("workspace-codec"),
                objectType = "note-snapshot",
                objectId = "note-codec",
                keyId = KeyId("workspace-key-1"),
                revision = "device-a:1",
                wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
                ciphertextLength = ciphertext.size,
            ),
            wrappedDataKey = ByteArray(48) { index -> index.toByte() },
            wrapNonce = ByteArray(24) { index -> (index + 1).toByte() },
            payloadNonce = ByteArray(24) { index -> (index + 2).toByte() },
            ciphertext = ciphertext,
        )
    }

    /**
     * 断言二进制解码以指定脱敏错误码失败。
     *
     * @param expected 预期格式错误码。
     * @param bytes 损坏文件字节。
     */
    private fun assertFormatFailure(
        expected: EnvelopeFormatFailureCode,
        bytes: ByteArray,
    ) {
        val exception = assertFailsWith<EnvelopeFormatException> {
            EncryptionEnvelopeCodec.decode(bytes)
        }
        assertEquals(expected, exception.code)
    }
}
