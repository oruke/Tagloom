package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.crypto.EncryptionEnvelope
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.Serializable

/**
 * 保存一个或多个 prepared 记录加密信封的容器。
 *
 * 密钥轮换事务在 manifest 切换前后可能分别只能使用旧密钥或新密钥恢复，因此会在同一
 * 控制文件中保存两份独立 AEAD 信封；容器本身不包含 prepared 明文。
 */
@Serializable
data class JvmPreparedRecordEnvelope(
    /** 可由当前工作区密钥尝试解密的 prepared 信封集合。 */
    val envelopes: List<EncryptionEnvelope>,
) {
    init {
        require(envelopes.size in 1..JvmPreparedRecordEnvelopeCodec.MAX_ENVELOPES) { "prepared 加密信封数量无效" }
    }
}

/** 提供 prepared 加密信封容器的规范编码与解码。 */
object JvmPreparedRecordEnvelopeCodec {
    /** prepared 控制记录最多保留旧/新两份密钥信封。 */
    internal const val MAX_ENVELOPES: Int = 2

    /**
     * 编码单个 prepared 加密信封。
     *
     * @param envelope 已完成 AEAD 的 prepared 信封。
     * @return 可持久化的规范 JSON 字节。
     */
    fun encode(envelope: EncryptionEnvelope): ByteArray = encode(listOf(envelope))

    /**
     * 编码多份 prepared 加密信封。
     *
     * @param envelopes 已完成 AEAD 的 prepared 信封集合。
     * @return 可持久化的规范 JSON 字节。
     */
    fun encode(envelopes: List<EncryptionEnvelope>): ByteArray = CanonicalJson
        .encodeToString(JvmPreparedRecordEnvelope.serializer(), JvmPreparedRecordEnvelope(envelopes))
        .encodeToByteArray()

    /**
     * 解码 prepared 加密信封容器。
     *
     * @param bytes 待读取的容器字节。
     * @return 容器内的加密信封集合。
     */
    fun decode(bytes: ByteArray): List<EncryptionEnvelope> = CanonicalJson
        .decodeFromString(JvmPreparedRecordEnvelope.serializer(), bytes.decodeToString())
        .envelopes

}
