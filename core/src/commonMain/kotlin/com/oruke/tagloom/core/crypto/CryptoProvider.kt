package com.oruke.tagloom.core.crypto

import kotlinx.serialization.Serializable

/**
 * 描述密码派生失败、认证失败或协议不受支持等可安全暴露的错误类型。
 */
enum class CryptoFailureCode {
    /** 密码学运行时尚未正确初始化。 */
    INITIALIZATION_FAILED,

    /** 输入密钥、nonce 或 KDF 参数不符合协议约束。 */
    INVALID_PARAMETER,

    /** 密文、认证头或密钥不匹配，无法通过 AEAD 认证。 */
    AUTHENTICATION_FAILED,

    /** 信封版本或算法套件不受当前客户端支持。 */
    UNSUPPORTED_ENVELOPE,

    /** 信封身份与调用方预期的文件身份不一致。 */
    IDENTITY_MISMATCH,
}

/**
 * 表示不携带密码、密钥、正文或原始密码库消息的安全异常。
 *
 * @property code 供应用服务和恢复界面判断的稳定错误类型。
 * @param cause 仅用于本地诊断的底层异常；调用方不得直接记录其消息。
 */
class CryptoException(
    val code: CryptoFailureCode,
    cause: Throwable? = null,
) : RuntimeException("密码操作失败：${code.name}", cause)

/**
 * 描述 Argon2id 派生参数。
 *
 * 参数随 manifest 保存；默认值是桌面测试基线，发布前仍需按目标设备基准测试。
 */
@Serializable
data class Argon2idParameters(
    /** 每个工作区独立生成的随机 salt，至少 16 字节。 */
    val salt: ByteArray,
    /** 内存成本，单位为 KiB。 */
    val memoryKiB: Int = 65_536,
    /** 迭代次数。 */
    val iterations: Int = 3,
    /** 并行度；当前适配器只接受 1。 */
    val parallelism: Int = 1,
    /** 输出密钥长度，Tagloom v1 固定为 32 字节。 */
    val outputLength: Int = CryptoProtocol.KEY_BYTES,
) {
    init {
        require(salt.size == CryptoProtocol.ARGON2_SALT_BYTES) { "Tagloom v1 Argon2id salt 必须为 16 字节" }
        require(memoryKiB >= 8) { "Argon2id 内存成本至少为 8 KiB" }
        require(iterations > 0) { "Argon2id 迭代次数必须大于 0" }
        require(parallelism > 0) { "Argon2id 并行度必须大于 0" }
        require(outputLength == CryptoProtocol.KEY_BYTES) { "Tagloom v1 密钥长度必须为 32 字节" }
    }
}

/**
 * 抽象 Tagloom 所需的密码学原语，隔离具体跨平台密码库。
 *
 * 实现必须使用安全随机数、Argon2id、HKDF-SHA-256 和 XChaCha20-Poly1305，禁止静默替换算法。
 */
interface CryptoProvider {
    /**
     * 初始化底层密码学运行时；重复调用必须安全。
     */
    suspend fun initialize()

    /**
     * 生成不可预测的安全随机字节。
     *
     * @param size 请求的字节数，必须大于零。
     * @return 指定长度的新随机数组。
     */
    fun secureRandom(size: Int): ByteArray

    /**
     * 使用 Argon2id 从密码派生密钥。
     *
     * @param password 用户提供的密码；实现不得缓存或记录。
     * @param parameters manifest 中保存的 KDF 参数。
     * @return 新的派生密钥字节。
     */
    fun deriveArgon2id(
        password: String,
        parameters: Argon2idParameters,
    ): ByteArray

    /**
     * 按 RFC 5869 执行 HKDF-SHA-256。
     *
     * @param inputKeyMaterial 输入密钥材料。
     * @param salt 非秘密 salt；允许为空。
     * @param info 域分离上下文。
     * @param outputLength 输出长度。
     * @return 派生字节。
     */
    fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray

    /**
     * 计算协议所需的 SHA-256 摘要。
     *
     * @param input 待摘要字节。
     * @return 固定 32 字节摘要。
     */
    fun sha256(input: ByteArray): ByteArray

    /**
     * 使用 XChaCha20-Poly1305-IETF 加密并附加认证标签。
     *
     * @param plaintext 明文。
     * @param associatedData 只认证不加密的上下文。
     * @param nonce 本次加密独立生成的 24 字节 nonce。
     * @param key 32 字节密钥。
     * @return 密文与 16 字节认证标签的组合。
     */
    fun encryptXChaCha20Poly1305(
        plaintext: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * 验证并解密 XChaCha20-Poly1305-IETF 密文。
     *
     * @param ciphertextAndTag 密文与认证标签组合。
     * @param associatedData 加密时使用的认证上下文。
     * @param nonce 加密时使用的 24 字节 nonce。
     * @param key 32 字节密钥。
     * @return 认证通过后的明文。
     * @throws CryptoException 认证失败或参数错误时抛出安全异常。
     */
    fun decryptXChaCha20Poly1305(
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray
}

/**
 * 汇总 v1 密码协议的固定尺寸与版本号。
 */
object CryptoProtocol {
    /** 对称密钥长度。 */
    const val KEY_BYTES: Int = 32

    /** XChaCha20-Poly1305 nonce 长度。 */
    const val NONCE_BYTES: Int = 24

    /** Poly1305 认证标签长度。 */
    const val TAG_BYTES: Int = 16

    /** Argon2id salt 最小长度。 */
    const val ARGON2_SALT_BYTES: Int = 16

    /** 当前信封版本。 */
    const val ENVELOPE_VERSION: Int = 1

    /** 所有加密信封的固定魔数。 */
    const val ENVELOPE_MAGIC: String = "TAGLOOM"
}
