package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.crypto.CryptoProvider

/**
 * 生成不依赖文件名、内容摘要或本机信息的随机稳定 Blob ID。
 *
 * @param cryptoProvider 提供密码学安全随机数的跨平台端口。
 */
class BlobIdGenerator(
    private val cryptoProvider: CryptoProvider,
) {
    /**
     * 生成带协议前缀的 160 位随机 Blob ID。
     *
     * 前缀使文件名无法被误解为明文 SHA-256；随机部分只用于身份，不承担内容完整性校验。
     *
     * @return 可安全用于 `blobs/<id>.blob.enc` 的新 ID。
     */
    suspend fun generate(): BlobId {
        cryptoProvider.initialize()
        val random = cryptoProvider.secureRandom(RANDOM_BYTES)
        return try {
            BlobId(BLOB_ID_PREFIX + random.joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            })
        } finally {
            random.fill(0)
        }
    }

    private companion object {
        /** Blob ID 的随机熵长度。 */
        const val RANDOM_BYTES: Int = 20

        /** 区分随机业务 ID 与任何裸内容摘要。 */
        const val BLOB_ID_PREFIX: String = "blob_"
    }
}
