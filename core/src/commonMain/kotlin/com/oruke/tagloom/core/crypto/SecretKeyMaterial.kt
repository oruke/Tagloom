package com.oruke.tagloom.core.crypto

/**
 * 区分密钥在层级中的用途，防止调用方把不同层级的 32 字节数组混用。
 */
enum class SecretKeyKind {
    /** 用户密码通过 Argon2id 派生的包装密钥。 */
    KEY_ENCRYPTION,

    /** 工作区根密钥。 */
    WORKSPACE_MASTER,

    /** 由主密钥按用途派生的包装密钥。 */
    WRAPPING,

    /** 每次文件写入随机生成的数据密钥。 */
    DATA_ENCRYPTION,

    /** 独立恢复材料解码后的包装密钥。 */
    RECOVERY,

    /** 仅用于加密本地可重建 SQLite 索引的派生密钥。 */
    INDEX,

    /** 用于确定性恢复 Ed25519 设备签名密钥对的 32 字节 seed。 */
    DEVICE_SIGNING_SEED,
}

/**
 * 持有可主动清零的 256 位会话密钥材料。
 *
 * JVM 与 Kotlin/Native 无法保证所有复制均被清除，因此该类型只提供尽力清零，不宣称内存零残留。
 *
 * @param bytes 调用方提供的 32 字节密钥材料；构造时立即复制。
 */
class SecretKeyMaterial private constructor(
    /** 当前密钥在层级中的业务用途。 */
    val kind: SecretKeyKind,
    bytes: ByteArray,
) {
    /** 由本对象独占、可在销毁时覆盖的密钥副本。 */
    private val value: ByteArray = bytes.copyOf()

    /** 密钥是否已经被调用方销毁。 */
    private var destroyed: Boolean = false

    /**
     * 返回不暴露密钥内容的销毁状态，供生命周期控制器和测试确认锁定结果。
     */
    val isDestroyed: Boolean
        get() = destroyed

    /**
     * 为单次密码调用复制密钥字节。
     *
     * 调用方必须在使用后立即清零返回数组，禁止保存、序列化或记录。
     *
     * @return 新的 32 字节密钥副本。
     */
    internal fun copyBytes(): ByteArray {
        check(!destroyed) { "密钥材料已经销毁" }
        return value.copyOf()
    }

    /**
     * 在受限作用域内向平台适配器提供一次性密钥副本。
     *
     * 该方法始终在回调返回或抛出异常后清零副本；调用方不得从回调中返回、缓存、序列化或记录该数组。
     * 它用于必须把密钥交给平台密码库或加密数据库驱动的边界，避免开放长期可读的密钥属性。
     *
     * @param block 使用临时密钥副本完成单次平台调用的回调。
     * @return 回调产生的不含密钥材料的结果。
     */
    fun <T> useBytes(block: (ByteArray) -> T): T {
        val temporary = copyBytes()
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }

    /**
     * 验证密钥用途与当前操作匹配。
     *
     * @param expected 当前操作要求的密钥用途。
     */
    internal fun requireKind(expected: SecretKeyKind) {
        require(kind == expected) { "密钥用途不匹配" }
        check(!destroyed) { "密钥材料已经销毁" }
    }

    /**
     * 尽力覆盖本对象持有的可变密钥缓冲区，并使后续使用立即失败。
     */
    fun destroy() {
        value.fill(0)
        destroyed = true
    }

    /**
     * 返回不会泄露密钥内容的诊断文本。
     *
     * @return 仅包含用途和销毁状态的脱敏文本。
     */
    override fun toString(): String = "SecretKeyMaterial(kind=$kind, destroyed=$destroyed)"

    companion object {
        /**
         * 从调用方字节创建独立密钥副本。
         *
         * @param kind 密钥用途。
         * @param bytes 恰好 32 字节的密钥材料。
         * @return 持有独立副本的密钥对象。
         */
        fun from(
            kind: SecretKeyKind,
            bytes: ByteArray,
        ): SecretKeyMaterial {
            require(bytes.size == CryptoProtocol.KEY_BYTES) { "密钥必须为 32 字节" }
            return SecretKeyMaterial(kind, bytes)
        }
    }
}
