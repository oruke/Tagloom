package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 定义二进制信封解析失败时可安全显示的错误类型。
 */
enum class EnvelopeFormatFailureCode {
    /** 文件未包含完整前缀、头或密文。 */
    TRUNCATED,

    /** 文件魔数不属于 Tagloom。 */
    INVALID_MAGIC,

    /** 外层格式或内层信封版本不受支持。 */
    UNSUPPORTED_VERSION,

    /** 公开头 JSON 无法按当前 schema 解析。 */
    INVALID_HEADER,

    /** 外层长度、内层认证头和实际文件长度不一致。 */
    LENGTH_MISMATCH,
}

/**
 * 表示不包含原始文件内容的二进制信封格式异常。
 *
 * @property code 供恢复流程判断隔离原因的稳定错误码。
 * @param cause 仅用于本地诊断的底层异常；不得直接记录其消息。
 */
class EnvelopeFormatException(
    val code: EnvelopeFormatFailureCode,
    cause: Throwable? = null,
) : IllegalArgumentException("加密信封格式无效：${code.name}", cause)

/**
 * 在 [EncryptionEnvelope] 与边界明确的 v1 二进制文件之间转换。
 *
 * 字符串头使用规范化 JSON，密钥、nonce 与密文保持原始字节，避免 Base64 和 JSON 数组膨胀。
 */
object EncryptionEnvelopeCodec {
    /**
     * 将已完成 AEAD 的内存信封编码为单个二进制文件。
     *
     * @param envelope 已通过密码服务创建的信封。
     * @return 可直接写入加密 staging 文件的完整字节。
     */
    fun encode(envelope: EncryptionEnvelope): ByteArray {
        validateEnvelopeForEncoding(envelope)
        val headerBytes = CanonicalJson
            .encodeToString(EncryptionEnvelopeHeader.serializer(), envelope.header)
            .encodeToByteArray()
        if (headerBytes.size > MAX_HEADER_BYTES) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.INVALID_HEADER)
        }
        val totalLength = checkedTotalLength(
            headerLength = headerBytes.size,
            wrappedKeyLength = envelope.wrappedDataKey.size,
            ciphertextLength = envelope.ciphertext.size,
        )
        val output = ByteArray(totalLength)
        var offset = 0

        FILE_MAGIC.copyInto(output, destinationOffset = offset)
        offset += FILE_MAGIC.size
        output[offset++] = FILE_FORMAT_VERSION.toByte()
        writeInt(output, offset, headerBytes.size)
        offset += INT_BYTES
        writeInt(output, offset, envelope.wrappedDataKey.size)
        offset += INT_BYTES
        writeInt(output, offset, envelope.ciphertext.size)
        offset += INT_BYTES
        headerBytes.copyInto(output, destinationOffset = offset)
        offset += headerBytes.size
        envelope.wrapNonce.copyInto(output, destinationOffset = offset)
        offset += envelope.wrapNonce.size
        envelope.payloadNonce.copyInto(output, destinationOffset = offset)
        offset += envelope.payloadNonce.size
        envelope.wrappedDataKey.copyInto(output, destinationOffset = offset)
        offset += envelope.wrappedDataKey.size
        envelope.ciphertext.copyInto(output, destinationOffset = offset)
        return output
    }

    /**
     * 在分配可变长度字段前验证边界并解析完整二进制信封。
     *
     * @param bytes 从单个文件完整读取的字节。
     * @return 可交给 [EnvelopeCryptoService] 做身份核对和 AEAD 验证的信封。
     * @throws EnvelopeFormatException 文件截断、长度伪造或头 schema 无效时抛出。
     */
    fun decode(bytes: ByteArray): EncryptionEnvelope {
        if (bytes.size < FIXED_PREFIX_BYTES) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.TRUNCATED)
        }
        val reader = EnvelopeBinaryReader(bytes)
        if (!reader.readBytes(FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.INVALID_MAGIC)
        }
        if (reader.readUnsignedByte() != FILE_FORMAT_VERSION) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.UNSUPPORTED_VERSION)
        }
        val headerLength = reader.readPositiveInt(MAX_HEADER_BYTES)
        val wrappedKeyLength = reader.readPositiveInt(MAX_WRAPPED_KEY_BYTES)
        val ciphertextLength = reader.readPositiveInt(MAX_CIPHERTEXT_BYTES)
        val expectedLength = checkedTotalLength(headerLength, wrappedKeyLength, ciphertextLength)
        if (expectedLength != bytes.size) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.LENGTH_MISMATCH)
        }

        val headerText = reader.readBytes(headerLength).decodeToString()
        val header = try {
            CanonicalJson.decodeFromString(EncryptionEnvelopeHeader.serializer(), headerText)
        } catch (exception: Throwable) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.INVALID_HEADER, exception)
        }
        if (
            header.magic != CryptoProtocol.ENVELOPE_MAGIC ||
            header.envelopeVersion != CryptoProtocol.ENVELOPE_VERSION ||
            header.cipherSuite != EnvelopeCipherSuite.XCHACHA20_POLY1305
        ) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.UNSUPPORTED_VERSION)
        }
        if (
            wrappedKeyLength != CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES ||
            header.ciphertextLength != ciphertextLength
        ) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.LENGTH_MISMATCH)
        }

        return EncryptionEnvelope(
            header = header,
            wrapNonce = reader.readBytes(CryptoProtocol.NONCE_BYTES),
            payloadNonce = reader.readBytes(CryptoProtocol.NONCE_BYTES),
            wrappedDataKey = reader.readBytes(wrappedKeyLength),
            ciphertext = reader.readBytes(ciphertextLength),
        )
    }

    /**
     * 防止调用方绕过密码服务编码尺寸或版本无效的内存信封。
     *
     * @param envelope 待编码信封。
     */
    private fun validateEnvelopeForEncoding(envelope: EncryptionEnvelope) {
        if (
            envelope.header.magic != CryptoProtocol.ENVELOPE_MAGIC ||
            envelope.header.envelopeVersion != CryptoProtocol.ENVELOPE_VERSION ||
            envelope.header.cipherSuite != EnvelopeCipherSuite.XCHACHA20_POLY1305
        ) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.UNSUPPORTED_VERSION)
        }
        if (
            envelope.wrapNonce.size != CryptoProtocol.NONCE_BYTES ||
            envelope.payloadNonce.size != CryptoProtocol.NONCE_BYTES ||
            envelope.wrappedDataKey.size != CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES ||
            envelope.ciphertext.size != envelope.header.ciphertextLength
        ) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.LENGTH_MISMATCH)
        }
    }

    /**
     * 使用 Long 计算总长度，拒绝整数溢出和超过当前内存模型的文件。
     *
     * @param headerLength 规范化公开头长度。
     * @param wrappedKeyLength 被包装 DEK 长度。
     * @param ciphertextLength payload 密文长度。
     * @return 可安全分配为 ByteArray 的总长度。
     */
    private fun checkedTotalLength(
        headerLength: Int,
        wrappedKeyLength: Int,
        ciphertextLength: Int,
    ): Int {
        val total = FIXED_PREFIX_BYTES.toLong() +
            headerLength +
            CryptoProtocol.NONCE_BYTES * 2L +
            wrappedKeyLength +
            ciphertextLength
        if (total > Int.MAX_VALUE || total < FIXED_PREFIX_BYTES) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.LENGTH_MISMATCH)
        }
        return total.toInt()
    }

    /**
     * 按网络字节序写入非负 Int。
     *
     * @param target 目标文件缓冲区。
     * @param offset 写入起始位置。
     * @param value 待写入非负整数。
     */
    private fun writeInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    /** 文件外层固定魔数。 */
    private val FILE_MAGIC: ByteArray = CryptoProtocol.ENVELOPE_MAGIC.encodeToByteArray()

    /** 二进制布局版本，与内层密码信封版本分别演进。 */
    private const val FILE_FORMAT_VERSION: Int = 1

    /** Int 字段占用字节数。 */
    private const val INT_BYTES: Int = 4

    /** 魔数、版本和三个长度字段的固定前缀长度。 */
    private val FIXED_PREFIX_BYTES: Int = FILE_MAGIC.size + 1 + INT_BYTES * 3

    /** 防止损坏文件诱导分配超大公开头。 */
    private const val MAX_HEADER_BYTES: Int = 64 * 1_024

    /** v1 被包装 DEK 的最大长度。 */
    private const val MAX_WRAPPED_KEY_BYTES: Int = CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES

    /** 当前 ByteArray 文件读取路径允许的最大密文长度。 */
    private const val MAX_CIPHERTEXT_BYTES: Int = Int.MAX_VALUE - 1_024 * 1_024
}

/**
 * 在严格边界内顺序读取二进制信封，所有越界统一转换为脱敏格式异常。
 *
 * @param source 完整文件字节。
 */
private class EnvelopeBinaryReader(
    /** 完整文件字节。 */
    private val source: ByteArray,
) {
    /** 下一次读取的起始偏移。 */
    private var offset: Int = 0

    /**
     * 读取指定数量的字节并推进游标。
     *
     * @param length 要读取的非负长度。
     * @return 独立字节副本。
     */
    fun readBytes(length: Int): ByteArray {
        if (length < 0 || offset > source.size - length) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.TRUNCATED)
        }
        return source.copyOfRange(offset, offset + length).also { offset += length }
    }

    /**
     * 读取一个无符号字节。
     *
     * @return 0 到 255 的整数。
     */
    fun readUnsignedByte(): Int = readBytes(1).single().toInt() and 0xFF

    /**
     * 按网络字节序读取受上限约束的正 Int。
     *
     * @param maximum 允许的最大值。
     * @return 1 到 maximum 的长度。
     */
    fun readPositiveInt(maximum: Int): Int {
        val bytes = readBytes(4)
        val value =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        if (value <= 0 || value > maximum) {
            throw EnvelopeFormatException(EnvelopeFormatFailureCode.LENGTH_MISMATCH)
        }
        return value
    }
}
