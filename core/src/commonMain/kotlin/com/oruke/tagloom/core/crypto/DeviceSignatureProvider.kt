package com.oruke.tagloom.core.crypto

/** 汇总 Ed25519 设备签名协议的固定尺寸。 */
object DeviceSignatureProtocol {
    /** 私钥恢复 seed 长度。 */
    const val SEED_BYTES: Int = 32

    /** Ed25519 公钥长度。 */
    const val PUBLIC_KEY_BYTES: Int = 32

    /** Ed25519 detached signature 长度。 */
    const val SIGNATURE_BYTES: Int = 64
}

/** 持有新建设备签名密钥对，并允许主动销毁私钥 seed。 */
class DeviceSigningKeyPair private constructor(
    publicKey: ByteArray,
    /** 可主动清零且禁止序列化的 Ed25519 私钥恢复 seed。 */
    val privateKeySeed: SecretKeyMaterial,
) {
    /** 由当前对象持有的公钥副本。 */
    private val publicKeyBytes: ByteArray = publicKey.copyOf()

    init {
        require(publicKey.size == DeviceSignatureProtocol.PUBLIC_KEY_BYTES) { "设备签名公钥长度无效" }
        require(privateKeySeed.kind == SecretKeyKind.DEVICE_SIGNING_SEED) { "设备签名 seed 用途无效" }
    }

    /**
     * 返回可写入设备凭据的公钥副本。
     *
     * @return 32 字节 Ed25519 公钥。
     */
    fun copyPublicKey(): ByteArray = publicKeyBytes.copyOf()

    /** 主动销毁私钥 seed；公钥不属于秘密材料。 */
    fun destroyPrivateKey() {
        privateKeySeed.destroy()
    }

    companion object {
        /**
         * 从平台密码库生成结果创建受控密钥对。
         *
         * @param publicKey 32 字节 Ed25519 公钥。
         * @param privateKeySeed 32 字节 Ed25519 私钥恢复 seed。
         * @return 持有独立公钥副本与可销毁 seed 的密钥对。
         */
        fun from(
            publicKey: ByteArray,
            privateKeySeed: ByteArray,
        ): DeviceSigningKeyPair {
            require(privateKeySeed.size == DeviceSignatureProtocol.SEED_BYTES) { "设备签名 seed 长度无效" }
            val seed = SecretKeyMaterial.from(SecretKeyKind.DEVICE_SIGNING_SEED, privateKeySeed)
            return DeviceSigningKeyPair(publicKey, seed)
        }
    }
}

/** 抽象设备 Change 签名所需的 Ed25519 原语。 */
interface DeviceSignatureProvider {
    /** 初始化底层密码学运行时；重复调用必须安全。 */
    suspend fun initialize()

    /**
     * 生成新的随机设备签名密钥对。
     *
     * @return 公钥与可主动销毁的私钥 seed。
     */
    fun generateDeviceSigningKeyPair(): DeviceSigningKeyPair

    /**
     * 使用设备私钥 seed 创建 detached signature。
     *
     * @param message 待签名字节。
     * @param privateKeySeed 设备 Ed25519 私钥恢复 seed。
     * @return 64 字节 detached signature。
     */
    fun signDetached(
        message: ByteArray,
        privateKeySeed: SecretKeyMaterial,
    ): ByteArray

    /**
     * 验证设备 detached signature。
     *
     * @param message 原始签名字节。
     * @param signature 64 字节 detached signature。
     * @param publicKey 32 字节 Ed25519 公钥。
     * @return 签名有效时为真，任何格式或认证错误均为假。
     */
    fun verifyDetached(
        message: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray,
    ): Boolean
}
