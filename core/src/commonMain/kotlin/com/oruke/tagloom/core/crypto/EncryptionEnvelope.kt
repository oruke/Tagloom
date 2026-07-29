package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import kotlinx.serialization.Serializable

/**
 * 定义信封明确记录的 AEAD 算法套件。
 */
@Serializable
enum class EnvelopeCipherSuite {
    /** 24 字节 nonce 的 XChaCha20-Poly1305-IETF。 */
    XCHACHA20_POLY1305,
}

/**
 * 区分由 Workspace Master Key 派生的包装密钥用途。
 */
@Serializable
enum class WrappingKeyPurpose(
    /** HKDF 使用的固定域分离字符串。 */
    val domain: String,
) {
    /** 工作区 manifest 的认证密文载荷。 */
    MANIFEST("tagloom/manifest-wrap/v1"),

    /** 权威对象快照。 */
    SNAPSHOT("tagloom/snapshot-wrap/v1"),

    /** 加密附件 Blob。 */
    BLOB("tagloom/blob-wrap/v1"),

    /** Change DAG 节点。 */
    CHANGE("tagloom/change-wrap/v1"),

    /** 审计与冲突历史。 */
    AUDIT("tagloom/audit-wrap/v1"),
}

/**
 * 描述文件路径和调用上下文必须共同确认的信封身份。
 */
@Serializable
data class EnvelopeIdentity(
    /** 信封所属工作区。 */
    val workspaceId: WorkspaceId,
    /** 业务对象类型，例如 `note-snapshot` 或 `blob`。 */
    val objectType: String,
    /** 与文件名对应的稳定不透明对象标识。 */
    val objectId: String,
    /** 对象修订文本，例如 `device-a:42`。 */
    val revision: String,
) {
    init {
        require(objectType.isNotBlank()) { "信封对象类型不能为空" }
        require(objectId.isNotBlank()) { "信封对象 ID 不能为空" }
        require(revision.isNotBlank()) { "信封 revision 不能为空" }
    }
}

/**
 * 表示参与 AEAD 认证但不加密的公开信封头。
 *
 * 该头只包含解锁、路由和防调包所需字段，不得加入标题、正文、标签名、属性值或原始文件名。
 */
@Serializable
data class EncryptionEnvelopeHeader(
    /** 固定文件魔数。 */
    val magic: String = CryptoProtocol.ENVELOPE_MAGIC,
    /** 信封协议版本。 */
    val envelopeVersion: Int = CryptoProtocol.ENVELOPE_VERSION,
    /** 当前文件明确使用的算法套件。 */
    val cipherSuite: EnvelopeCipherSuite = EnvelopeCipherSuite.XCHACHA20_POLY1305,
    /** 文件所属工作区。 */
    val workspaceId: WorkspaceId,
    /** 业务对象类型。 */
    val objectType: String,
    /** 稳定不透明对象标识。 */
    val objectId: String,
    /** 用于选择 Workspace Master Key 版本的标识。 */
    val keyId: KeyId,
    /** 对象修订文本。 */
    val revision: String,
    /** 包装 DEK 所使用的用途隔离域。 */
    val wrappingKeyPurpose: WrappingKeyPurpose,
    /** payload 密文与认证标签的总字节数。 */
    val ciphertextLength: Int,
) {
    init {
        require(objectType.isNotBlank()) { "信封对象类型不能为空" }
        require(objectId.isNotBlank()) { "信封对象 ID 不能为空" }
        require(revision.isNotBlank()) { "信封 revision 不能为空" }
        require(ciphertextLength >= CryptoProtocol.TAG_BYTES) { "信封密文长度无效" }
    }

    /**
     * 提取用于路径和调用上下文核对的文件身份。
     *
     * @return 不包含密钥选择信息的信封身份。
     */
    fun identity(): EnvelopeIdentity = EnvelopeIdentity(
        workspaceId = workspaceId,
        objectType = objectType,
        objectId = objectId,
        revision = revision,
    )
}

/**
 * 表示可独立传输和验证的版本化加密信封。
 */
@Serializable
data class EncryptionEnvelope(
    /** 参与两层 AEAD 认证的公开头。 */
    val header: EncryptionEnvelopeHeader,
    /** 由用途隔离 Wrapping Key 加密的随机 DEK。 */
    val wrappedDataKey: ByteArray,
    /** 包装 DEK 时使用的随机 nonce。 */
    val wrapNonce: ByteArray,
    /** 加密 payload 时使用的独立随机 nonce。 */
    val payloadNonce: ByteArray,
    /** payload 密文与 Poly1305 标签。 */
    val ciphertext: ByteArray,
)

/**
 * 描述创建加密信封所需的公开上下文与明文。
 */
data class EnvelopeEncryptionRequest(
    /** 必须绑定到文件路径的工作区、对象和修订身份。 */
    val identity: EnvelopeIdentity,
    /** 当前使用的 Workspace Master Key 版本。 */
    val keyId: KeyId,
    /** 按文件角色选择的包装密钥用途。 */
    val wrappingKeyPurpose: WrappingKeyPurpose,
    /** 只在受控存储服务内短暂存在的明文。 */
    val plaintext: ByteArray,
)

/**
 * 描述解密调用方从 manifest 与文件路径得到的预期上下文。
 */
data class EnvelopeDecryptionContext(
    /** 文件路径预期的身份，用于拒绝完整有效文件调包。 */
    val expectedIdentity: EnvelopeIdentity,
    /** manifest 为该文件选择的密钥版本。 */
    val expectedKeyId: KeyId,
    /** 文件目录对应的包装密钥用途。 */
    val expectedWrappingKeyPurpose: WrappingKeyPurpose,
)
