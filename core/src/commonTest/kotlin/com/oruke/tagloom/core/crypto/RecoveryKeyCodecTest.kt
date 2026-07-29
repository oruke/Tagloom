package com.oruke.tagloom.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验证 Recovery Key 文本的往返、格式容错和抄录错误检测。
 */
class RecoveryKeyCodecTest {
    /**
     * 验证编码文本可忽略分隔符和大小写后恢复相同密钥。
     */
    @Test
    fun roundTripsRecoveryKey() {
        val provider = DeterministicRecoveryCryptoProvider()
        val codec = RecoveryKeyCodec(provider)
        val originalBytes = ByteArray(CryptoProtocol.KEY_BYTES) { index -> index.toByte() }
        val original = SecretKeyMaterial.from(SecretKeyKind.RECOVERY, originalBytes)
        val decoded = codec.decode(codec.encode(original).lowercase().replace('-', ' '))
        try {
            original.useBytes { expected ->
                decoded.useBytes { actual -> assertContentEquals(expected, actual) }
            }
        } finally {
            original.destroy()
            decoded.destroy()
            originalBytes.fill(0)
        }
    }

    /**
     * 验证篡改任意有效字符会在密钥解包前被校验码拒绝。
     */
    @Test
    fun rejectsChangedChecksum() {
        val codec = RecoveryKeyCodec(DeterministicRecoveryCryptoProvider())
        val key = SecretKeyMaterial.from(SecretKeyKind.RECOVERY, ByteArray(CryptoProtocol.KEY_BYTES) { 7 })
        try {
            val encoded = codec.encode(key)
            val changed = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
            assertFailsWith<IllegalArgumentException> { codec.decode(changed) }
        } finally {
            key.destroy()
        }
    }
}

/**
 * 只为 Recovery Key Codec 测试提供确定性 SHA-256 形状，不参与真实密码学验收。
 */
private class DeterministicRecoveryCryptoProvider : CryptoProvider {
    /** 初始化测试端口。 */
    override suspend fun initialize() = Unit

    /**
     * 测试不需要随机数。
     *
     * @param size 请求长度。
     * @return 全零数组。
     */
    override fun secureRandom(size: Int): ByteArray = ByteArray(size)

    /**
     * 测试不执行 Argon2id。
     *
     * @param password 未使用密码。
     * @param parameters 未使用参数。
     * @return 不会实际返回。
     */
    override fun deriveArgon2id(password: String, parameters: Argon2idParameters): ByteArray =
        error("测试不使用 Argon2id")

    /**
     * 测试不执行 HKDF。
     *
     * @param inputKeyMaterial 未使用输入。
     * @param salt 未使用 salt。
     * @param info 未使用域。
     * @param outputLength 未使用长度。
     * @return 不会实际返回。
     */
    override fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray = error("测试不使用 HKDF")

    /**
     * 产生稳定 32 字节摘要形状，覆盖输入的顺序信息。
     *
     * @param input 待摘要输入。
     * @return 确定性测试摘要。
     */
    override fun sha256(input: ByteArray): ByteArray = ByteArray(CryptoProtocol.KEY_BYTES) { index ->
        input.fold(index.toByte()) { accumulator, byte -> (accumulator.toInt() xor byte.toInt()).toByte() }
    }

    /**
     * 测试不执行 AEAD 加密。
     *
     * @param plaintext 未使用明文。
     * @param associatedData 未使用认证数据。
     * @param nonce 未使用 nonce。
     * @param key 未使用密钥。
     * @return 不会实际返回。
     */
    override fun encryptXChaCha20Poly1305(
        plaintext: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray = error("测试不使用 AEAD")

    /**
     * 测试不执行 AEAD 解密。
     *
     * @param ciphertextAndTag 未使用密文。
     * @param associatedData 未使用认证数据。
     * @param nonce 未使用 nonce。
     * @param key 未使用密钥。
     * @return 不会实际返回。
     */
    override fun decryptXChaCha20Poly1305(
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray = error("测试不使用 AEAD")
}
