package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 编排随机 DEK、用途隔离 Wrapping Key 与双层 AEAD，创建和打开加密文件信封。
 */
class EnvelopeCryptoService(
    /** 提供实际密码原语的可替换适配器。 */
    private val cryptoProvider: CryptoProvider,
    /** 负责工作区密钥层级派生。 */
    private val workspaceKeyService: WorkspaceKeyService = WorkspaceKeyService(cryptoProvider),
) {
    /**
     * 为一次对象写入生成随机 DEK、两个独立 nonce 和完整认证信封。
     *
     * @param request 公开文件身份、密钥版本、用途与明文。
     * @param workspaceMasterKey 当前 `keyId` 对应的工作区主密钥。
     * @return 可安全写入 staging 或最终对象路径的密文信封。
     */
    suspend fun encrypt(
        request: EnvelopeEncryptionRequest,
        workspaceMasterKey: SecretKeyMaterial,
    ): EncryptionEnvelope {
        cryptoProvider.initialize()
        workspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        val wrappingKey = workspaceKeyService.deriveWrappingKey(
            workspaceMasterKey = workspaceMasterKey,
            workspaceId = request.identity.workspaceId,
            purpose = request.wrappingKeyPurpose,
        )
        val dataKey = workspaceKeyService.generateRandomKey(SecretKeyKind.DATA_ENCRYPTION)
        val wrapNonce = cryptoProvider.secureRandom(CryptoProtocol.NONCE_BYTES)
        val payloadNonce = cryptoProvider.secureRandom(CryptoProtocol.NONCE_BYTES)
        val header = EncryptionEnvelopeHeader(
            workspaceId = request.identity.workspaceId,
            objectType = request.identity.objectType,
            objectId = request.identity.objectId,
            keyId = request.keyId,
            revision = request.identity.revision,
            wrappingKeyPurpose = request.wrappingKeyPurpose,
            ciphertextLength = request.plaintext.size + CryptoProtocol.TAG_BYTES,
        )
        val wrappingBytes = wrappingKey.copyBytes()
        val dataBytes = dataKey.copyBytes()

        return try {
            val wrappedDataKey = cryptoProvider.encryptXChaCha20Poly1305(
                plaintext = dataBytes,
                associatedData = associatedData(header, AssociatedDataRole.WRAPPED_DATA_KEY),
                nonce = wrapNonce,
                key = wrappingBytes,
            )
            val ciphertext = cryptoProvider.encryptXChaCha20Poly1305(
                plaintext = request.plaintext,
                associatedData = associatedData(header, AssociatedDataRole.PAYLOAD),
                nonce = payloadNonce,
                key = dataBytes,
            )
            check(ciphertext.size == header.ciphertextLength) { "密码提供者返回了错误的密文长度" }
            EncryptionEnvelope(
                header = header,
                wrappedDataKey = wrappedDataKey,
                wrapNonce = wrapNonce,
                payloadNonce = payloadNonce,
                ciphertext = ciphertext,
            )
        } finally {
            wrappingBytes.fill(0)
            dataBytes.fill(0)
            wrappingKey.destroy()
            dataKey.destroy()
        }
    }

    /**
     * 核对文件身份和信封协议，认证两层密文后返回明文。
     *
     * @param envelope 从文件完整读取的加密信封。
     * @param context 从路径和 manifest 得到的预期身份、密钥版本与用途。
     * @param workspaceMasterKey `expectedKeyId` 对应的工作区主密钥。
     * @return 认证通过的明文字节；调用方负责缩短其生命周期并尽力清零。
     * @throws CryptoException 文件调包、头部篡改、密文损坏或密钥错误时抛出安全异常。
     */
    suspend fun decrypt(
        envelope: EncryptionEnvelope,
        context: EnvelopeDecryptionContext,
        workspaceMasterKey: SecretKeyMaterial,
    ): ByteArray {
        cryptoProvider.initialize()
        validateEnvelope(envelope, context)
        workspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        val wrappingKey = workspaceKeyService.deriveWrappingKey(
            workspaceMasterKey = workspaceMasterKey,
            workspaceId = envelope.header.workspaceId,
            purpose = envelope.header.wrappingKeyPurpose,
        )
        val wrappingBytes = wrappingKey.copyBytes()
        var dataBytes: ByteArray? = null

        return try {
            dataBytes = cryptoProvider.decryptXChaCha20Poly1305(
                ciphertextAndTag = envelope.wrappedDataKey,
                associatedData = associatedData(envelope.header, AssociatedDataRole.WRAPPED_DATA_KEY),
                nonce = envelope.wrapNonce,
                key = wrappingBytes,
            )
            if (dataBytes.size != CryptoProtocol.KEY_BYTES) {
                throw CryptoException(CryptoFailureCode.AUTHENTICATION_FAILED)
            }
            cryptoProvider.decryptXChaCha20Poly1305(
                ciphertextAndTag = envelope.ciphertext,
                associatedData = associatedData(envelope.header, AssociatedDataRole.PAYLOAD),
                nonce = envelope.payloadNonce,
                key = dataBytes,
            )
        } finally {
            wrappingBytes.fill(0)
            dataBytes?.fill(0)
            wrappingKey.destroy()
        }
    }

    /**
     * 在任何解密前验证版本、尺寸以及调用方预期的文件身份。
     *
     * @param envelope 待读取的信封。
     * @param context 路径与 manifest 提供的可信上下文。
     * @throws CryptoException 协议不支持、尺寸异常或文件调包时抛出。
     */
    private fun validateEnvelope(
        envelope: EncryptionEnvelope,
        context: EnvelopeDecryptionContext,
    ) {
        val header = envelope.header
        if (
            header.magic != CryptoProtocol.ENVELOPE_MAGIC ||
            header.envelopeVersion != CryptoProtocol.ENVELOPE_VERSION ||
            header.cipherSuite != EnvelopeCipherSuite.XCHACHA20_POLY1305
        ) {
            throw CryptoException(CryptoFailureCode.UNSUPPORTED_ENVELOPE)
        }
        if (
            header.identity() != context.expectedIdentity ||
            header.keyId != context.expectedKeyId ||
            header.wrappingKeyPurpose != context.expectedWrappingKeyPurpose
        ) {
            throw CryptoException(CryptoFailureCode.IDENTITY_MISMATCH)
        }
        if (
            envelope.wrapNonce.size != CryptoProtocol.NONCE_BYTES ||
            envelope.payloadNonce.size != CryptoProtocol.NONCE_BYTES ||
            envelope.wrappedDataKey.size != CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES ||
            envelope.ciphertext.size != header.ciphertextLength
        ) {
            throw CryptoException(CryptoFailureCode.AUTHENTICATION_FAILED)
        }
    }

    /**
     * 将规范化公开头与密文角色组合为无歧义的 AEAD Associated Data。
     *
     * @param header 参与认证的公开信封头。
     * @param role 当前密文是被包装 DEK 还是业务 payload。
     * @return UTF-8 编码且带零字节分隔的稳定认证上下文。
     */
    private fun associatedData(
        header: EncryptionEnvelopeHeader,
        role: AssociatedDataRole,
    ): ByteArray = buildList {
        addAll(CanonicalJson.encodeToString(EncryptionEnvelopeHeader.serializer(), header).encodeToByteArray().asList())
        add(0)
        addAll(role.value.encodeToByteArray().asList())
    }.toByteArray()

    /**
     * 区分同一公开头下的 DEK 包装密文与业务 payload，阻止两者被互换。
     */
    private enum class AssociatedDataRole(
        /** 进入认证上下文的固定角色字符串。 */
        val value: String,
    ) {
        /** 被用途隔离 Wrapping Key 加密的随机 DEK。 */
        WRAPPED_DATA_KEY("wrapped-data-key"),

        /** 由随机 DEK 加密的业务 payload。 */
        PAYLOAD("payload"),
    }
}
