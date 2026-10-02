package com.oruke.tagloom.core.crypto

/** Recovery Key 的用途隔离派生结果；认证材料只允许用于显式服务器恢复流程。 */
class ServerRecoveryCredentials(
    /** 不透明恢复定位符，不包含笔记或工作区名称。 */
    val recoveryId: String,
    /** 高熵恢复访问凭据；不得持久化到普通配置、日志或服务器。 */
    val accessToken: String,
    /** 可持久化到服务器的恢复访问凭据摘要。 */
    val tokenSha256: String,
)

/**
 * 从高熵 Recovery Key 派生独立的服务器定位符和访问凭据，不发送解密材料。
 * @param cryptoProvider 已初始化的 HKDF-SHA-256 密码学端口。
 * @return 用途隔离的恢复定位与认证材料，调用方应缩短其内存生命周期。
 */
fun SecretKeyMaterial.serverRecoveryCredentials(cryptoProvider: CryptoProvider): ServerRecoveryCredentials {
    requireKind(SecretKeyKind.RECOVERY)
    val key = copyBytes()
    var lookup = byteArrayOf()
    var authentication = byteArrayOf()
    return try {
        lookup = cryptoProvider.hkdfSha256(key, byteArrayOf(), "tagloom/server-recovery/lookup/v1".encodeToByteArray(), 32)
        authentication = cryptoProvider.hkdfSha256(key, byteArrayOf(), "tagloom/server-recovery/auth/v1".encodeToByteArray(), 32)
        val token = authentication.toRecoveryHex()
        val digest = cryptoProvider.sha256(token.encodeToByteArray())
        try { ServerRecoveryCredentials(lookup.toRecoveryHex(), token, digest.toRecoveryHex()) }
        finally { digest.fill(0) }
    } finally {
        key.fill(0)
        lookup.fill(0)
        authentication.fill(0)
    }
}

/** @return 当前字节序列的规范小写十六进制，用于跨平台稳定的恢复协议。 */
private fun ByteArray.toRecoveryHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
