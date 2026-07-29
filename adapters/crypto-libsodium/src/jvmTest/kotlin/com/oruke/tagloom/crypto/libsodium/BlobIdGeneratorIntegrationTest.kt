package com.oruke.tagloom.crypto.libsodium

import com.oruke.tagloom.core.model.BlobIdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 使用真实安全随机源验证 Blob 文件身份不会退化为明文内容摘要。
 */
class BlobIdGeneratorIntegrationTest {
    /**
     * 验证随机 Blob ID 具有固定前缀、足够熵和批次唯一性，且不是裸 SHA-256 名称。
     */
    @Test
    fun generatesOpaqueRandomIdsInsteadOfPlaintextHashes() = runBlocking {
        val generator = BlobIdGenerator(LibsodiumCryptoProvider())
        val ids = List(64) { generator.generate().value }

        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { id -> id.matches(Regex("blob_[0-9a-f]{40}")) })
        assertFalse(ids.any { id -> id.matches(Regex("[0-9a-f]{64}")) })
    }
}
