package com.oruke.tagloom.core.crypto

/**
 * 把 256 位 Recovery Key 编解码为带版本和校验码的离线文本。
 *
 * 文本只允许在工作区创建确认或显式恢复流程中短期展示，禁止写入日志、崩溃报告或普通配置。
 * 调用方必须先初始化注入的 [CryptoProvider]，因为同步编解码流程会立即计算校验摘要。
 *
 * @param cryptoProvider 计算 Recovery Key 校验码的密码学端口。
 */
class RecoveryKeyCodec(
    private val cryptoProvider: CryptoProvider,
) {
    /**
     * 生成便于人工抄录的版本化 Recovery Key 文本。
     *
     * @param recoveryKey 待离线保存的 Recovery Key。
     * @return `TLRK1` 前缀、分组密钥和校验码组成的文本。
     */
    fun encode(recoveryKey: SecretKeyMaterial): String {
        recoveryKey.requireKind(SecretKeyKind.RECOVERY)
        val bytes = recoveryKey.copyBytes()
        return try {
            val payload = bytes.toUpperHex()
            val checksum = checksum(bytes)
            (PREFIX + payload + checksum)
                .chunked(GROUP_CHARACTERS)
                .joinToString(separator = "-")
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 校验并解析用户提供的 Recovery Key 文本。
     *
     * @param encoded 允许包含空格或连字符的 Recovery Key 文本。
     * @return 可用于一次恢复尝试且必须由调用方销毁的密钥材料。
     */
    fun decode(encoded: String): SecretKeyMaterial {
        val normalized = encoded
            .filterNot { character -> character == '-' || character.isWhitespace() }
            .uppercase()
        require(normalized.startsWith(PREFIX)) { "Recovery Key 版本不受支持" }
        require(normalized.length == TOTAL_CHARACTERS) { "Recovery Key 长度无效" }
        val payloadText = normalized.substring(PREFIX.length, PREFIX.length + KEY_HEX_CHARACTERS)
        val checksumText = normalized.takeLast(CHECKSUM_HEX_CHARACTERS)
        require(payloadText.all(::isUpperHex) && checksumText.all(::isUpperHex)) { "Recovery Key 格式无效" }
        val bytes = payloadText.hexToBytes()
        return try {
            require(checksum(bytes) == checksumText) { "Recovery Key 校验码无效" }
            SecretKeyMaterial.from(SecretKeyKind.RECOVERY, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 计算固定四字节校验码，用于在执行密钥解包前识别抄录错误。
     *
     * @param bytes 32 字节 Recovery Key。
     * @return 八位大写十六进制校验码。
     */
    private fun checksum(bytes: ByteArray): String {
        val digest = cryptoProvider.sha256(CHECKSUM_DOMAIN.encodeToByteArray() + bytes)
        return try {
            digest.copyOfRange(0, CHECKSUM_BYTES).toUpperHex()
        } finally {
            digest.fill(0)
        }
    }

    /**
     * 把字节转换为大写十六进制文本。
     *
     * @return 每字节两个字符的文本。
     */
    private fun ByteArray.toUpperHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    /**
     * 把已验证的偶数长度十六进制文本解析为字节。
     *
     * @return 新字节数组。
     */
    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    /**
     * 判断字符是否属于规范大写十六进制集合。
     *
     * @param character 待检查字符。
     * @return 字符可用于 Recovery Key 时为 `true`。
     */
    private fun isUpperHex(character: Char): Boolean = character in '0'..'9' || character in 'A'..'F'

    private companion object {
        /** Recovery Key 文本协议版本。 */
        const val PREFIX: String = "TLRK1"

        /** 每组字符数，便于人工抄录。 */
        const val GROUP_CHARACTERS: Int = 5

        /** 256 位密钥的十六进制字符数。 */
        const val KEY_HEX_CHARACTERS: Int = CryptoProtocol.KEY_BYTES * 2

        /** 校验码字节数。 */
        const val CHECKSUM_BYTES: Int = 4

        /** 校验码十六进制字符数。 */
        const val CHECKSUM_HEX_CHARACTERS: Int = CHECKSUM_BYTES * 2

        /** 规范化文本总字符数。 */
        const val TOTAL_CHARACTERS: Int = PREFIX.length + KEY_HEX_CHARACTERS + CHECKSUM_HEX_CHARACTERS

        /** 防止普通 SHA-256 用途与 Recovery Key 校验混用的域。 */
        const val CHECKSUM_DOMAIN: String = "tagloom/recovery-key-checksum/v1"
    }
}
