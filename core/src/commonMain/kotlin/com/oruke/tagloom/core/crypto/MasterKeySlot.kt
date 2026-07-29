package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.Serializable

/**
 * 区分 manifest 中包装 Workspace Master Key 的解锁材料类型。
 */
@Serializable
enum class UnlockSlotType {
    /** 由用户密码和 Argon2id 参数派生的 KEK。 */
    PASSWORD,

    /** 由一次展示并离线保存的高熵 Recovery Key 提供。 */
    RECOVERY,
}

/**
 * 表示参与主密钥包 AEAD 认证的公开槽头。
 */
@Serializable
data class MasterKeySlotHeader(
    /** 固定文件魔数。 */
    val magic: String = CryptoProtocol.ENVELOPE_MAGIC,
    /** 主密钥槽协议版本。 */
    val envelopeVersion: Int = CryptoProtocol.ENVELOPE_VERSION,
    /** 明确记录的 AEAD 算法套件。 */
    val cipherSuite: EnvelopeCipherSuite = EnvelopeCipherSuite.XCHACHA20_POLY1305,
    /** 解锁槽所属工作区。 */
    val workspaceId: WorkspaceId,
    /** 被包装的 Workspace Master Key 版本。 */
    val keyId: KeyId,
    /** manifest 内稳定且不敏感的槽标识。 */
    val slotId: String,
    /** 槽使用密码还是 Recovery Key。 */
    val slotType: UnlockSlotType,
    /** 被包装主密钥的密文和标签总长度。 */
    val ciphertextLength: Int = CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES,
) {
    init {
        require(slotId.isNotBlank()) { "主密钥槽 ID 不能为空" }
        require(ciphertextLength == CryptoProtocol.KEY_BYTES + CryptoProtocol.TAG_BYTES) {
            "主密钥包长度必须为 48 字节"
        }
    }
}

/**
 * 表示 manifest 中一个可独立验证的 Workspace Master Key 解锁槽。
 */
@Serializable
data class MasterKeySlot(
    /** 参与 AEAD 认证的公开槽头。 */
    val header: MasterKeySlotHeader,
    /** PASSWORD 槽保存的 Argon2id 参数；RECOVERY 槽必须为空。 */
    val argon2idParameters: Argon2idParameters? = null,
    /** 包装 Workspace Master Key 使用的随机 24 字节 nonce。 */
    val nonce: ByteArray,
    /** Workspace Master Key 密文与 Poly1305 标签。 */
    val ciphertext: ByteArray,
) {
    init {
        require(
            (header.slotType == UnlockSlotType.PASSWORD && argon2idParameters != null) ||
                (header.slotType == UnlockSlotType.RECOVERY && argon2idParameters == null),
        ) { "主密钥槽类型与 KDF 参数不匹配" }
        require(nonce.size == CryptoProtocol.NONCE_BYTES) { "主密钥槽 nonce 必须为 24 字节" }
        require(ciphertext.size == header.ciphertextLength) { "主密钥槽密文长度与认证头不一致" }
    }
}

/**
 * 编排密码槽、Recovery Key 槽以及 Workspace Master Key 的包装和解包。
 */
class MasterKeySlotService(
    /** 提供实际密码原语的可替换适配器。 */
    private val cryptoProvider: CryptoProvider,
    /** 负责密码派生与随机密钥生成。 */
    private val workspaceKeyService: WorkspaceKeyService = WorkspaceKeyService(cryptoProvider),
) {
    /**
     * 创建密码解锁槽，只包装 Workspace Master Key，不重新加密任何业务对象。
     *
     * @param workspaceId 槽所属工作区。
     * @param keyId 被包装主密钥的版本。
     * @param slotId manifest 内稳定槽标识。
     * @param password 用户密码，不缓存也不记录。
     * @param parameters 随槽保存的 Argon2id 参数。
     * @param workspaceMasterKey 待包装的工作区主密钥。
     * @return 可写入 manifest 公开头与密文载荷的密码槽。
     */
    suspend fun createPasswordSlot(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        slotId: String,
        password: String,
        parameters: Argon2idParameters,
        workspaceMasterKey: SecretKeyMaterial,
    ): MasterKeySlot {
        val passwordKey = workspaceKeyService.derivePasswordKey(password, parameters)
        return try {
            wrap(
                header = MasterKeySlotHeader(
                    workspaceId = workspaceId,
                    keyId = keyId,
                    slotId = slotId,
                    slotType = UnlockSlotType.PASSWORD,
                ),
                parameters = parameters,
                unlockKey = passwordKey,
                workspaceMasterKey = workspaceMasterKey,
            )
        } finally {
            passwordKey.destroy()
        }
    }

    /**
     * 使用密码槽恢复 Workspace Master Key。
     *
     * @param slot PASSWORD 类型主密钥槽。
     * @param password 用户本次输入的密码。
     * @return 认证通过后恢复的工作区主密钥。
     */
    suspend fun unlockWithPassword(
        slot: MasterKeySlot,
        password: String,
    ): SecretKeyMaterial {
        if (slot.header.slotType != UnlockSlotType.PASSWORD) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
        val parameters = slot.argon2idParameters
            ?: throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        val passwordKey = workspaceKeyService.derivePasswordKey(password, parameters)
        return try {
            unwrap(slot, passwordKey)
        } finally {
            passwordKey.destroy()
        }
    }

    /**
     * 生成一次工作区创建流程使用的高熵 Recovery Key。
     *
     * @return 需要只展示一次并由用户离线保存的密钥材料。
     */
    suspend fun generateRecoveryKey(): SecretKeyMaterial =
        workspaceKeyService.generateRandomKey(SecretKeyKind.RECOVERY)

    /**
     * 创建 Recovery Key 解锁槽。
     *
     * @param workspaceId 槽所属工作区。
     * @param keyId 被包装主密钥的版本。
     * @param slotId manifest 内稳定槽标识。
     * @param recoveryKey 用户离线保存的高熵恢复材料。
     * @param workspaceMasterKey 待包装的工作区主密钥。
     * @return 不包含 KDF 参数的恢复槽。
     */
    suspend fun createRecoverySlot(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        slotId: String,
        recoveryKey: SecretKeyMaterial,
        workspaceMasterKey: SecretKeyMaterial,
    ): MasterKeySlot = wrap(
        header = MasterKeySlotHeader(
            workspaceId = workspaceId,
            keyId = keyId,
            slotId = slotId,
            slotType = UnlockSlotType.RECOVERY,
        ),
        parameters = null,
        unlockKey = recoveryKey,
        workspaceMasterKey = workspaceMasterKey,
    )

    /**
     * 使用 Recovery Key 槽恢复 Workspace Master Key。
     *
     * @param slot RECOVERY 类型主密钥槽。
     * @param recoveryKey 用户提供的高熵恢复材料。
     * @return 认证通过后恢复的工作区主密钥。
     */
    suspend fun unlockWithRecovery(
        slot: MasterKeySlot,
        recoveryKey: SecretKeyMaterial,
    ): SecretKeyMaterial {
        if (slot.header.slotType != UnlockSlotType.RECOVERY) {
            throw CryptoException(CryptoFailureCode.INVALID_PARAMETER)
        }
        return unwrap(slot, recoveryKey)
    }

    /**
     * 使用指定解锁密钥包装 Workspace Master Key。
     *
     * @param header 完整公开认证头。
     * @param parameters 密码槽的 KDF 参数；恢复槽为空。
     * @param unlockKey KEK 或 Recovery Key。
     * @param workspaceMasterKey 待包装主密钥。
     * @return 包含随机 nonce 和认证密文的槽。
     */
    private suspend fun wrap(
        header: MasterKeySlotHeader,
        parameters: Argon2idParameters?,
        unlockKey: SecretKeyMaterial,
        workspaceMasterKey: SecretKeyMaterial,
    ): MasterKeySlot {
        cryptoProvider.initialize()
        requireUnlockKeyKind(header.slotType, unlockKey)
        workspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        val unlockBytes = unlockKey.copyBytes()
        val masterBytes = workspaceMasterKey.copyBytes()
        val nonce = cryptoProvider.secureRandom(CryptoProtocol.NONCE_BYTES)
        return try {
            MasterKeySlot(
                header = header,
                argon2idParameters = parameters,
                nonce = nonce,
                ciphertext = cryptoProvider.encryptXChaCha20Poly1305(
                    plaintext = masterBytes,
                    associatedData = associatedData(header),
                    nonce = nonce,
                    key = unlockBytes,
                ),
            )
        } finally {
            unlockBytes.fill(0)
            masterBytes.fill(0)
        }
    }

    /**
     * 认证主密钥槽并恢复 Workspace Master Key。
     *
     * @param slot 待解密槽。
     * @param unlockKey 与槽类型匹配的 KEK 或 Recovery Key。
     * @return 可主动销毁的工作区主密钥。
     */
    private suspend fun unwrap(
        slot: MasterKeySlot,
        unlockKey: SecretKeyMaterial,
    ): SecretKeyMaterial {
        cryptoProvider.initialize()
        validateHeader(slot.header)
        requireUnlockKeyKind(slot.header.slotType, unlockKey)
        val unlockBytes = unlockKey.copyBytes()
        val masterBytes = try {
            cryptoProvider.decryptXChaCha20Poly1305(
                ciphertextAndTag = slot.ciphertext,
                associatedData = associatedData(slot.header),
                nonce = slot.nonce,
                key = unlockBytes,
            )
        } finally {
            unlockBytes.fill(0)
        }
        return try {
            SecretKeyMaterial.from(SecretKeyKind.WORKSPACE_MASTER, masterBytes)
        } finally {
            masterBytes.fill(0)
        }
    }

    /**
     * 验证主密钥槽版本和算法套件受当前客户端支持。
     *
     * @param header 待验证公开头。
     */
    private fun validateHeader(header: MasterKeySlotHeader) {
        if (
            header.magic != CryptoProtocol.ENVELOPE_MAGIC ||
            header.envelopeVersion != CryptoProtocol.ENVELOPE_VERSION ||
            header.cipherSuite != EnvelopeCipherSuite.XCHACHA20_POLY1305
        ) {
            throw CryptoException(CryptoFailureCode.UNSUPPORTED_ENVELOPE)
        }
    }

    /**
     * 防止密码 KEK 和 Recovery Key 在错误槽类型中复用。
     *
     * @param slotType 主密钥槽类型。
     * @param unlockKey 调用方提供的解锁密钥。
     */
    private fun requireUnlockKeyKind(
        slotType: UnlockSlotType,
        unlockKey: SecretKeyMaterial,
    ) {
        val expected = when (slotType) {
            UnlockSlotType.PASSWORD -> SecretKeyKind.KEY_ENCRYPTION
            UnlockSlotType.RECOVERY -> SecretKeyKind.RECOVERY
        }
        unlockKey.requireKind(expected)
    }

    /**
     * 将规范化公开槽头与固定角色字符串组合为 AEAD Associated Data。
     *
     * @param header 参与认证的主密钥槽头。
     * @return 防止槽头篡改和跨协议密文复用的认证上下文。
     */
    private fun associatedData(header: MasterKeySlotHeader): ByteArray =
        (
            CanonicalJson.encodeToString(MasterKeySlotHeader.serializer(), header) +
                "\u0000workspace-master-key"
            ).encodeToByteArray()
}
