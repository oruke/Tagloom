package com.oruke.tagloom.core.crypto

import com.oruke.tagloom.core.model.WorkspaceId

/**
 * 编排工作区密钥生成、密码派生和用途隔离子密钥派生。
 */
class WorkspaceKeyService(
    /** 提供实际密码原语的可替换适配器。 */
    private val cryptoProvider: CryptoProvider,
) {
    /**
     * 生成指定层级的随机 256 位密钥。
     *
     * @param kind 密钥在层级中的用途。
     * @return 可主动销毁的新密钥材料。
     */
    suspend fun generateRandomKey(kind: SecretKeyKind): SecretKeyMaterial {
        cryptoProvider.initialize()
        val bytes = cryptoProvider.secureRandom(CryptoProtocol.KEY_BYTES)
        return try {
            SecretKeyMaterial.from(kind, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 使用 manifest 参数从密码派生 Key Encryption Key。
     *
     * @param password 用户输入的密码；服务不缓存该字符串。
     * @param parameters 工作区保存的 Argon2id 参数。
     * @return 可用于包装 Workspace Master Key 的会话密钥。
     */
    suspend fun derivePasswordKey(
        password: String,
        parameters: Argon2idParameters,
    ): SecretKeyMaterial {
        cryptoProvider.initialize()
        val bytes = cryptoProvider.deriveArgon2id(password, parameters)
        return try {
            SecretKeyMaterial.from(SecretKeyKind.KEY_ENCRYPTION, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 从 Workspace Master Key 派生指定文件角色的 Wrapping Key。
     *
     * @param workspaceMasterKey 当前工作区主密钥。
     * @param workspaceId 工作区公开随机标识，用作 HKDF salt 域的一部分。
     * @param purpose 目标文件角色的固定域分离上下文。
     * @return 独立用途的 256 位包装密钥。
     */
    suspend fun deriveWrappingKey(
        workspaceMasterKey: SecretKeyMaterial,
        workspaceId: WorkspaceId,
        purpose: WrappingKeyPurpose,
    ): SecretKeyMaterial {
        cryptoProvider.initialize()
        workspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        val masterBytes = workspaceMasterKey.copyBytes()
        val salt = "tagloom/workspace-hkdf-salt/v1/${workspaceId.value}".encodeToByteArray()
        val info = purpose.domain.encodeToByteArray()
        val derived = try {
            cryptoProvider.hkdfSha256(
                inputKeyMaterial = masterBytes,
                salt = salt,
                info = info,
                outputLength = CryptoProtocol.KEY_BYTES,
            )
        } finally {
            masterBytes.fill(0)
        }
        return try {
            SecretKeyMaterial.from(SecretKeyKind.WRAPPING, derived)
        } finally {
            derived.fill(0)
        }
    }

    /**
     * 从 Workspace Master Key 派生仅供本地可重建索引使用的独立密钥。
     *
     * 索引密钥使用固定协议域与对象信封包装密钥隔离。删除索引不会影响权威快照，锁定工作区时必须销毁返回值。
     *
     * @param workspaceMasterKey 当前工作区主密钥。
     * @param workspaceId 工作区公开随机标识，用作 HKDF salt 域的一部分。
     * @return 仅用于加密 SQLite 索引的 256 位会话密钥。
     */
    suspend fun deriveIndexKey(
        workspaceMasterKey: SecretKeyMaterial,
        workspaceId: WorkspaceId,
    ): SecretKeyMaterial {
        cryptoProvider.initialize()
        workspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        val masterBytes = workspaceMasterKey.copyBytes()
        val salt = "tagloom/workspace-hkdf-salt/v1/${workspaceId.value}".encodeToByteArray()
        val info = INDEX_KEY_DOMAIN.encodeToByteArray()
        val derived = try {
            cryptoProvider.hkdfSha256(
                inputKeyMaterial = masterBytes,
                salt = salt,
                info = info,
                outputLength = CryptoProtocol.KEY_BYTES,
            )
        } finally {
            masterBytes.fill(0)
        }
        return try {
            SecretKeyMaterial.from(SecretKeyKind.INDEX, derived)
        } finally {
            derived.fill(0)
        }
    }

    private companion object {
        /** SQLite 索引密钥的 HKDF 域分离上下文。 */
        const val INDEX_KEY_DOMAIN: String = "tagloom/index-key/v1"
    }
}
