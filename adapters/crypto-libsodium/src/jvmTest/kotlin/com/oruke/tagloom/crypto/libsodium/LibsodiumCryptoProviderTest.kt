package com.oruke.tagloom.crypto.libsodium

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.core.crypto.CryptoFailureCode
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.MasterKeySlotService
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.crypto.WorkspaceKeyService
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 在 JVM 原生运行时使用官方固定向量和端到端信封场景验证 libsodium 适配器。
 */
class LibsodiumCryptoProviderTest {
    /**
     * 验证 HKDF-SHA-256 与 RFC 5869 附录 A.1 完全一致。
     */
    @Test
    fun hkdfMatchesRfc5869CaseOne() = runBlocking {
        val provider = initializedProvider()
        val output = provider.hkdfSha256(
            inputKeyMaterial = "0b".repeat(22).hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            outputLength = 42,
        )

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            output.toHex(),
        )
    }

    /**
     * 验证 XChaCha20-Poly1305 与 libsodium 官方测试向量完全一致。
     */
    @Test
    fun xChaCha20Poly1305MatchesLibsodiumVector() = runBlocking {
        val provider = initializedProvider()
        val ciphertext = provider.encryptXChaCha20Poly1305(
            plaintext = (
                "Ladies and Gentlemen of the class of '99: If I could offer you " +
                    "only one tip for the future, sunscreen would be it."
                ).encodeToByteArray(),
            associatedData = "50515253c0c1c2c3c4c5c6c7".hexToBytes(),
            nonce = "07000000404142434445464748494a4b4c4d4e4f50515253".hexToBytes(),
            key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToBytes(),
        )

        assertEquals(
            "f8ebea4875044066fc162a0604e171feecfb3d20425248563bcfd5a155dcc47b" +
                "bda70b86e5ab9b55002bd1274c02db35321acd7af8b2e2d25015e136b7679458" +
                "e9f43243bf719d639badb5feac03f80a19a96ef10cb1d15333a837b90946ba38" +
                "54ee74da3f2585efc7e1e170e17e15e563e77601f4f85cafa8e5877614e143e6" +
                "8420",
            ciphertext.toHex(),
        )
    }

    /**
     * 验证 Argon2id 对相同输入确定，对不同 salt 产生不同结果。
     */
    @Test
    fun argon2idUsesManifestParametersAndSalt() = runBlocking {
        val provider = initializedProvider()
        val parameters = Argon2idParameters(
            salt = ByteArray(16) { index -> index.toByte() },
            memoryKiB = 8_192,
            iterations = 1,
        )

        val first = provider.deriveArgon2id("correct horse battery staple", parameters)
        val second = provider.deriveArgon2id("correct horse battery staple", parameters)
        val differentSalt = provider.deriveArgon2id(
            "correct horse battery staple",
            parameters.copy(salt = ByteArray(16) { index -> (index + 1).toByte() }),
        )

        assertContentEquals(first, second)
        assertFalse(first.contentEquals(differentSalt))
    }

    /**
     * 验证 SQLite 索引密钥具有独立用途类型，并与对象信封包装密钥完成域分离。
     */
    @Test
    fun indexKeyUsesIndependentHkdfDomain() = runBlocking {
        val provider = initializedProvider()
        val service = WorkspaceKeyService(provider)
        val masterKey = key(12)
        val workspaceId = WorkspaceId("workspace-index-domain")
        val indexKey = service.deriveIndexKey(masterKey, workspaceId)
        val snapshotWrappingKey = service.deriveWrappingKey(
            workspaceMasterKey = masterKey,
            workspaceId = workspaceId,
            purpose = WrappingKeyPurpose.SNAPSHOT,
        )

        assertEquals(SecretKeyKind.INDEX, indexKey.kind)
        assertTrue(
            indexKey.useBytes { indexBytes ->
                snapshotWrappingKey.useBytes { wrappingBytes -> !indexBytes.contentEquals(wrappingBytes) }
            },
        )

        indexKey.destroy()
        snapshotWrappingKey.destroy()
        masterKey.destroy()
    }

    /**
     * 验证信封往返、随机 nonce、头部/密文篡改、错误密钥和文件调包保护。
     */
    @Test
    fun envelopeRejectsTamperingWrongKeyAndObjectSwap() = runBlocking {
        val provider = initializedProvider()
        val service = EnvelopeCryptoService(provider)
        val masterKey = key(0)
        val wrongMasterKey = key(1)
        val firstIdentity = identity("note-a", "device-a:1")
        val secondIdentity = identity("note-b", "device-a:1")
        val request = request(firstIdentity, "加密正文")
        val first = service.encrypt(request, masterKey)
        val repeated = service.encrypt(request, masterKey)
        val second = service.encrypt(request(secondIdentity, "另一篇正文"), masterKey)
        val context = context(firstIdentity)

        assertFalse(first.wrapNonce.contentEquals(repeated.wrapNonce))
        assertFalse(first.payloadNonce.contentEquals(repeated.payloadNonce))
        assertContentEquals("加密正文".encodeToByteArray(), service.decrypt(first, context, masterKey))

        val tamperedCiphertext = first.copy(ciphertext = first.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertCryptoFailure(CryptoFailureCode.AUTHENTICATION_FAILED) {
            service.decrypt(tamperedCiphertext, context, masterKey)
        }
        assertCryptoFailure(CryptoFailureCode.AUTHENTICATION_FAILED) {
            service.decrypt(first, context, wrongMasterKey)
        }

        val tamperedHeader = first.copy(
            header = first.header.copy(objectId = "note-tampered"),
        )
        assertCryptoFailure(CryptoFailureCode.AUTHENTICATION_FAILED) {
            service.decrypt(tamperedHeader, context(identity("note-tampered", "device-a:1")), masterKey)
        }
        assertCryptoFailure(CryptoFailureCode.IDENTITY_MISMATCH) {
            service.decrypt(second, context, masterKey)
        }

        masterKey.destroy()
        wrongMasterKey.destroy()
    }

    /**
     * 验证密码变更只重包主密钥，Recovery Key 也能恢复同一对象密文。
     */
    @Test
    fun passwordChangeAndRecoveryDoNotReencryptObjects() = runBlocking {
        val provider = initializedProvider()
        val slotService = MasterKeySlotService(provider)
        val envelopeService = EnvelopeCryptoService(provider)
        val workspaceId = WorkspaceId("workspace-test")
        val keyId = KeyId("workspace-key-1")
        val masterKey = key(0)
        val recoveryKey = slotService.generateRecoveryKey()
        val parameters = Argon2idParameters(
            salt = ByteArray(16) { index -> index.toByte() },
            memoryKiB = 8_192,
            iterations = 1,
        )
        val originalPasswordSlot = slotService.createPasswordSlot(
            workspaceId = workspaceId,
            keyId = keyId,
            slotId = "password-1",
            password = "old password",
            parameters = parameters,
            workspaceMasterKey = masterKey,
        )
        val recoverySlot = slotService.createRecoverySlot(
            workspaceId = workspaceId,
            keyId = keyId,
            slotId = "recovery-1",
            recoveryKey = recoveryKey,
            workspaceMasterKey = masterKey,
        )
        val identity = identity("note-password-change", "device-a:1")
        val objectEnvelope = envelopeService.encrypt(request(identity, "保持不变的对象密文"), masterKey)
        val originalCiphertext = objectEnvelope.ciphertext.copyOf()

        val unlockedByOldPassword = slotService.unlockWithPassword(originalPasswordSlot, "old password")
        val newPasswordSlot = slotService.createPasswordSlot(
            workspaceId = workspaceId,
            keyId = keyId,
            slotId = "password-2",
            password = "new password",
            parameters = parameters.copy(salt = ByteArray(16) { index -> (index + 1).toByte() }),
            workspaceMasterKey = unlockedByOldPassword,
        )
        unlockedByOldPassword.destroy()

        assertCryptoFailure(CryptoFailureCode.AUTHENTICATION_FAILED) {
            slotService.unlockWithPassword(originalPasswordSlot, "wrong password")
        }
        val unlockedByNewPassword = slotService.unlockWithPassword(newPasswordSlot, "new password")
        val unlockedByRecovery = slotService.unlockWithRecovery(recoverySlot, recoveryKey)

        assertContentEquals(originalCiphertext, objectEnvelope.ciphertext)
        assertContentEquals(
            "保持不变的对象密文".encodeToByteArray(),
            envelopeService.decrypt(objectEnvelope, context(identity), unlockedByNewPassword),
        )
        assertContentEquals(
            "保持不变的对象密文".encodeToByteArray(),
            envelopeService.decrypt(objectEnvelope, context(identity), unlockedByRecovery),
        )

        unlockedByNewPassword.destroy()
        unlockedByRecovery.destroy()
        recoveryKey.destroy()
        masterKey.destroy()
    }

    /**
     * 创建并初始化被测密码提供者。
     *
     * @return 已可同步调用密码原语的提供者。
     */
    private suspend fun initializedProvider(): LibsodiumCryptoProvider =
        LibsodiumCryptoProvider().also { provider -> provider.initialize() }

    /**
     * 创建固定内容的测试工作区主密钥。
     *
     * @param offset 每个字节使用的偏移，便于构造错误密钥。
     * @return 可主动销毁的测试密钥。
     */
    private fun key(offset: Int): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.WORKSPACE_MASTER,
        ByteArray(32) { index -> (index + offset).toByte() },
    )

    /**
     * 创建测试信封身份。
     *
     * @param objectId 对象标识。
     * @param revision 修订文本。
     * @return 固定工作区下的身份。
     */
    private fun identity(
        objectId: String,
        revision: String,
    ): EnvelopeIdentity = EnvelopeIdentity(
        workspaceId = WorkspaceId("workspace-test"),
        objectType = "note-snapshot",
        objectId = objectId,
        revision = revision,
    )

    /**
     * 创建测试加密请求。
     *
     * @param identity 文件身份。
     * @param plaintext UTF-8 测试正文。
     * @return 使用 snapshot 包装域的请求。
     */
    private fun request(
        identity: EnvelopeIdentity,
        plaintext: String,
    ): EnvelopeEncryptionRequest = EnvelopeEncryptionRequest(
        identity = identity,
        keyId = KeyId("workspace-key-1"),
        wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
        plaintext = plaintext.encodeToByteArray(),
    )

    /**
     * 创建与测试文件路径一致的解密上下文。
     *
     * @param identity 调用方预期的文件身份。
     * @return snapshot 文件解密上下文。
     */
    private fun context(identity: EnvelopeIdentity): EnvelopeDecryptionContext = EnvelopeDecryptionContext(
        expectedIdentity = identity,
        expectedKeyId = KeyId("workspace-key-1"),
        expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
    )

    /**
     * 断言挂起密码操作以指定安全错误码失败。
     *
     * @param expected 预期错误码。
     * @param operation 被测挂起操作。
     */
    private suspend fun assertCryptoFailure(
        expected: CryptoFailureCode,
        operation: suspend () -> Unit,
    ) {
        val exception = assertFailsWith<CryptoException> { operation() }
        assertEquals(expected, exception.code)
    }

    /**
     * 将偶数字符十六进制文本解析为字节。
     *
     * @return 解析后的字节数组。
     */
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "十六进制文本长度必须为偶数" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * 将字节转换为小写无分隔十六进制文本。
     *
     * @return 固定两字符表示每个字节的文本。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
