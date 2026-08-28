package com.oruke.tagloom.crypto.libsodium

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.signature.Signature
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.core.crypto.CryptoFailureCode
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.DeviceSignatureProtocol
import com.oruke.tagloom.core.crypto.DeviceSignatureProvider
import com.oruke.tagloom.core.crypto.DeviceSigningKeyPair
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial

/**
 * 使用 kotlin-multiplatform-libsodium 提供 Tagloom v1 所需密码原语。
 *
 * 该类是具体密码 SDK 的唯一边界；上层只依赖 [CryptoProvider]，便于后续替换为完成独立审计的实现。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumCryptoProvider : CryptoProvider, DeviceSignatureProvider {
    /** 当前实例是否已确认底层 libsodium 运行时可用。 */
    private var initialized: Boolean = false

    /**
     * 初始化跨平台 libsodium 绑定；重复调用不会重复加载。
     *
     * @throws CryptoException 底层运行时加载失败时抛出脱敏异常。
     */
    override suspend fun initialize() {
        if (initialized || LibsodiumInitializer.isInitialized()) {
            initialized = true
            return
        }
        try {
            LibsodiumInitializer.initialize()
            initialized = LibsodiumInitializer.isInitialized()
            if (!initialized) {
                throw CryptoException(CryptoFailureCode.INITIALIZATION_FAILED)
            }
        } catch (exception: CryptoException) {
            throw exception
        } catch (exception: Throwable) {
            throw CryptoException(CryptoFailureCode.INITIALIZATION_FAILED, exception)
        }
    }

    /**
     * 使用 libsodium `randombytes_buf` 生成不可预测随机字节。
     *
     * @param size 请求的正数字节数。
     * @return 新的随机数组。
     */
    override fun secureRandom(size: Int): ByteArray {
        ensureInitialized()
        if (size <= 0) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
        return LibsodiumRandom.buf(size).toByteArray()
    }

    /**
     * 使用 libsodium Argon2id v1.3 从密码派生 256 位 KEK。
     *
     * @param password 用户密码，不缓存也不写入日志。
     * @param parameters manifest 保存的 KDF 参数；当前绑定要求 16 字节 salt 和并行度 1。
     * @return 派生密钥字节。
     */
    override fun deriveArgon2id(
        password: String,
        parameters: Argon2idParameters,
    ): ByteArray {
        ensureInitialized()
        if (
            parameters.salt.size != CryptoProtocol.ARGON2_SALT_BYTES ||
            parameters.parallelism != 1 ||
            parameters.memoryKiB > Int.MAX_VALUE / KIBIBYTE
        ) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
        return try {
            PasswordHash.pwhash(
                outputLength = parameters.outputLength,
                password = password,
                salt = parameters.salt.toUByteArray(),
                opsLimit = parameters.iterations.toULong(),
                memLimit = parameters.memoryKiB * KIBIBYTE,
                algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
            ).toByteArray()
        } catch (exception: Throwable) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER, exception)
        }
    }

    /**
     * 按 RFC 5869 使用 libsodium SHA-256 原语执行 HKDF Extract 与 Expand。
     *
     * @param inputKeyMaterial 输入密钥材料。
     * @param salt 可为空的非秘密 salt。
     * @param info 域分离上下文。
     * @param outputLength 1 到 8160 字节的输出长度。
     * @return 派生字节。
     */
    override fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        ensureInitialized()
        if (inputKeyMaterial.isEmpty() || outputLength !in 1..HKDF_MAX_OUTPUT_BYTES) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
        val extractSalt = if (salt.isEmpty()) ByteArray(SHA256_BYTES) else salt.copyOf()
        val pseudoRandomKey = hmacSha256(extractSalt, inputKeyMaterial)
        extractSalt.fill(0)

        return try {
            expandHkdf(pseudoRandomKey, info, outputLength)
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    /**
     * 使用 libsodium 计算固定长度 SHA-256 摘要。
     *
     * @param input 待摘要字节。
     * @return 32 字节摘要。
     */
    override fun sha256(input: ByteArray): ByteArray = Hash.sha256(input.toUByteArray()).toByteArray()

    /**
     * 使用 libsodium XChaCha20-Poly1305-IETF 组合模式加密。
     *
     * @param plaintext 明文。
     * @param associatedData 认证上下文。
     * @param nonce 独立随机 24 字节 nonce。
     * @param key 32 字节密钥。
     * @return 密文与 16 字节认证标签。
     */
    override fun encryptXChaCha20Poly1305(
        plaintext: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        ensureInitialized()
        validateAeadParameters(nonce, key)
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
                message = plaintext.toUByteArray(),
                associatedData = associatedData.toUByteArray(),
                nonce = nonce.toUByteArray(),
                key = key.toUByteArray(),
            ).toByteArray()
        } catch (exception: Throwable) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER, exception)
        }
    }

    /**
     * 使用 libsodium XChaCha20-Poly1305-IETF 验证并解密。
     *
     * @param ciphertextAndTag 密文与认证标签。
     * @param associatedData 加密时使用的认证上下文。
     * @param nonce 加密时使用的 24 字节 nonce。
     * @param key 32 字节密钥。
     * @return 认证通过的明文。
     */
    override fun decryptXChaCha20Poly1305(
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        ensureInitialized()
        validateAeadParameters(nonce, key)
        if (ciphertextAndTag.size < CryptoProtocol.TAG_BYTES) {
            throw CryptoException(CryptoFailureCode.AUTHENTICATION_FAILED)
        }
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = ciphertextAndTag.toUByteArray(),
                associatedData = associatedData.toUByteArray(),
                nonce = nonce.toUByteArray(),
                key = key.toUByteArray(),
            ).toByteArray()
        } catch (exception: AeadCorrupedOrTamperedDataException) {
            throw CryptoException(CryptoFailureCode.AUTHENTICATION_FAILED, exception)
        } catch (exception: Throwable) {
            throw CryptoException(CryptoFailureCode.AUTHENTICATION_FAILED, exception)
        }
    }

    /**
     * 使用 libsodium 随机生成 Ed25519 设备签名密钥对，并只保留可主动清零的 32 字节 seed。
     *
     * @return 公钥与私钥恢复 seed。
     */
    override fun generateDeviceSigningKeyPair(): DeviceSigningKeyPair {
        ensureInitialized()
        val generated = Signature.keypair()
        val publicKey = generated.publicKey.toByteArray()
        val seed = Signature.ed25519SkToSeed(generated.secretKey).toByteArray()
        return try {
            DeviceSigningKeyPair.from(publicKey, seed)
        } finally {
            generated.secretKey.fill(0u)
            seed.fill(0)
        }
    }

    /**
     * 从设备私钥 seed 确定性恢复 Ed25519 keypair 并生成 detached signature。
     *
     * @param message 待签名字节。
     * @param privateKeySeed 设备私钥恢复 seed。
     * @return 64 字节 detached signature。
     */
    override fun signDetached(
        message: ByteArray,
        privateKeySeed: SecretKeyMaterial,
    ): ByteArray {
        ensureInitialized()
        require(privateKeySeed.kind == SecretKeyKind.DEVICE_SIGNING_SEED) { "设备签名 seed 用途无效" }
        return privateKeySeed.useBytes { seed ->
            val keyPair = Signature.seedKeypair(seed.toUByteArray())
            try {
                Signature.detached(message.toUByteArray(), keyPair.secretKey).toByteArray()
            } finally {
                keyPair.secretKey.fill(0u)
            }
        }
    }

    /**
     * 使用 Ed25519 公钥验证 detached signature，并把任何格式或认证错误转换为 false。
     *
     * @param message 原始签名字节。
     * @param signature 64 字节 detached signature。
     * @param publicKey 32 字节 Ed25519 公钥。
     * @return 签名有效时为真。
     */
    override fun verifyDetached(
        message: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray,
    ): Boolean {
        ensureInitialized()
        if (
            signature.size != DeviceSignatureProtocol.SIGNATURE_BYTES ||
            publicKey.size != DeviceSignatureProtocol.PUBLIC_KEY_BYTES
        ) {
            return false
        }
        return runCatching {
            Signature.verifyDetached(
                signature = signature.toUByteArray(),
                message = message.toUByteArray(),
                publicKey = publicKey.toUByteArray(),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 使用 RFC 2104 规则和 libsodium SHA-256 计算任意长度 key 的 HMAC-SHA-256。
     *
     * @param key HMAC key。
     * @param message 待认证消息。
     * @return 32 字节认证值。
     */
    private fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val normalizedKey = if (key.size > SHA256_BLOCK_BYTES) {
            sha256(key)
        } else {
            key.copyOf()
        }
        val keyBlock = ByteArray(SHA256_BLOCK_BYTES)
        normalizedKey.copyInto(keyBlock)
        normalizedKey.fill(0)
        val innerPad = ByteArray(SHA256_BLOCK_BYTES) { index -> (keyBlock[index].toInt() xor INNER_PAD).toByte() }
        val outerPad = ByteArray(SHA256_BLOCK_BYTES) { index -> (keyBlock[index].toInt() xor OUTER_PAD).toByte() }
        keyBlock.fill(0)
        val innerInput = innerPad + message
        val innerHash = sha256(innerInput)
        innerPad.fill(0)
        innerInput.fill(0)
        val outerInput = outerPad + innerHash
        val result = sha256(outerInput)
        outerPad.fill(0)
        outerInput.fill(0)
        innerHash.fill(0)
        return result
    }

    /**
     * 执行 HKDF Expand 循环并限制 RFC 5869 规定的 255 个块。
     *
     * @param pseudoRandomKey Extract 阶段得到的 32 字节 PRK。
     * @param info 域分离上下文。
     * @param outputLength 目标输出长度。
     * @return 指定长度的输出密钥材料。
     */
    private fun expandHkdf(
        pseudoRandomKey: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        val output = ByteArray(outputLength)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1

        while (written < outputLength) {
            val input = previous + info + byteArrayOf(counter.toByte())
            val block = hmacSha256(pseudoRandomKey, input)
            previous.fill(0)
            input.fill(0)
            previous = block
            val count = minOf(block.size, outputLength - written)
            block.copyInto(output, destinationOffset = written, endIndex = count)
            written += count
            counter += 1
        }
        previous.fill(0)
        return output
    }

    /**
     * 验证 XChaCha20-Poly1305 的固定 key 与 nonce 尺寸。
     *
     * @param nonce 待验证 nonce。
     * @param key 待验证密钥。
     */
    private fun validateAeadParameters(
        nonce: ByteArray,
        key: ByteArray,
    ) {
        if (nonce.size != CryptoProtocol.NONCE_BYTES || key.size != CryptoProtocol.KEY_BYTES) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
    }

    /**
     * 确保调用者已经完成可能涉及原生库加载的挂起式初始化。
     */
    private fun ensureInitialized() {
        if (!initialized || !LibsodiumInitializer.isInitialized()) {
            throw CryptoException(CryptoFailureCode.INITIALIZATION_FAILED)
        }
    }

    companion object {
        /** KiB 到字节的换算。 */
        private const val KIBIBYTE: Int = 1_024

        /** SHA-256 输出长度。 */
        private const val SHA256_BYTES: Int = 32

        /** SHA-256 HMAC block 长度。 */
        private const val SHA256_BLOCK_BYTES: Int = 64

        /** RFC 5869 对 SHA-256 HKDF 输出的最大长度。 */
        private const val HKDF_MAX_OUTPUT_BYTES: Int = 255 * SHA256_BYTES

        /** HMAC inner pad 常量。 */
        private const val INNER_PAD: Int = 0x36

        /** HMAC outer pad 常量。 */
        private const val OUTER_PAD: Int = 0x5C
    }
}
