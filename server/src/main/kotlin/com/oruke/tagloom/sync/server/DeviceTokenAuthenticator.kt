package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.crypto.DeviceSignatureProtocol
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 描述设备凭据在服务端的随机身份。 */
data class SyncDeviceIdentity(
    /** 随机工作区标识。 */
    val workspaceId: WorkspaceId,
    /** 随机设备或 Agent 标识。 */
    val actorId: ActorId,
)

/** 凭据文件中保存的设备 Token 摘要记录。 */
@Serializable
data class DeviceCredentialRecord(
    /** 随机工作区标识。 */
    val workspaceId: WorkspaceId,
    /** 随机设备或 Agent 标识。 */
    val actorId: ActorId,
    /** 至少 256 位高熵 Bearer Token 的 SHA-256。 */
    val tokenSha256: String,
    /** 设备 Ed25519 公钥的 32 字节小写十六进制文本。 */
    val signingPublicKeyHex: String,
) {
    init {
        require(TOKEN_HASH_PATTERN.matches(tokenSha256)) { "设备 Token SHA-256 格式无效" }
        require(SIGNING_PUBLIC_KEY_PATTERN.matches(signingPublicKeyHex)) { "设备签名公钥格式无效" }
    }
}

/** 抽象同步 HTTP 请求的设备身份认证。 */
interface SyncDeviceAuthenticator {
    /**
     * 判断设备是否仍允许提交新的密文载荷，撤销后历史公钥仍可存在。
     * @param identity 待查询的工作区设备身份。
     * @return 当前设备仍有效时为真。
     */
    fun isActive(identity: SyncDeviceIdentity): Boolean

    /**
     * 验证工作区、设备与 Bearer Token 是否匹配且未撤销。
     *
     * @param identity 请求声明的随机设备身份。
     * @param bearerToken 请求携带的高熵 Token。
     * @return 恒定时间摘要比较通过时为真。
     */
    fun authenticate(
        identity: SyncDeviceIdentity,
        bearerToken: String,
    ): Boolean

    /**
     * 返回已登记设备凭据的 Ed25519 公钥副本。
     *
     * Token 撤销后公钥仍可保留，用于验证该设备撤销前已产生的历史 artifact。
     *
     * @param identity 已通过 Token 认证的设备身份。
     * @return 32 字节公钥；设备从未登记时为空。
     */
    fun signingPublicKey(identity: SyncDeviceIdentity): ByteArray?
}

/**
 * 使用内存中的 SHA-256 摘要认证设备，并支持进程内即时撤销。
 *
 * @param tokenHashes 设备身份到 32 字节 Token 摘要的初始映射；构造时复制。
 */
class DeviceTokenAuthenticator(
    tokenHashes: Map<SyncDeviceIdentity, ByteArray>,
    signingPublicKeys: Map<SyncDeviceIdentity, ByteArray> = emptyMap(),
) : SyncDeviceAuthenticator {
    /**
     * 查询当前内存凭据是否仍有效。
     * @param identity 待查询设备身份。
     * @return 尚未撤销时为真。
     */
    override fun isActive(identity: SyncDeviceIdentity): Boolean = activeTokenHashes.containsKey(identity)

    /** 当前仍有效的设备 Token 摘要。 */
    private val activeTokenHashes: ConcurrentHashMap<SyncDeviceIdentity, ByteArray> = ConcurrentHashMap(
        tokenHashes.mapValues { (_, hash) ->
            require(hash.size == SHA256_BYTES) { "设备 Token 摘要必须为 32 字节" }
            hash.copyOf()
        },
    )

    /** 已登记设备的 Ed25519 公钥；撤销 Token 后仍保留，用于验证撤销前历史。 */
    private val activeSigningPublicKeys: ConcurrentHashMap<SyncDeviceIdentity, ByteArray> = ConcurrentHashMap(
        signingPublicKeys.mapValues { (_, publicKey) ->
            require(publicKey.size == DeviceSignatureProtocol.PUBLIC_KEY_BYTES) { "设备签名公钥必须为 32 字节" }
            publicKey.copyOf()
        },
    )

    init {
        require(activeSigningPublicKeys.keys.all(activeTokenHashes::containsKey)) { "签名公钥缺少对应 Token 凭据" }
    }

    /**
     * 对请求 Token 求摘要并做恒定时间比较。
     *
     * @param identity 请求设备身份。
     * @param bearerToken 不记录、不缓存的高熵 Token。
     * @return 凭据有效且未撤销时为真。
     */
    override fun authenticate(
        identity: SyncDeviceIdentity,
        bearerToken: String,
    ): Boolean {
        val expected = activeTokenHashes[identity] ?: return false
        val actual = MessageDigest.getInstance("SHA-256").digest(bearerToken.encodeToByteArray())
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
        }
    }

    /**
     * 返回设备凭据登记的公钥副本，防止调用方修改认证器内部状态。
     *
     * @param identity 已通过 Token 认证的设备身份。
     * @return 已登记设备的公钥副本；撤销 Token 不影响历史签名验证。
     */
    override fun signingPublicKey(identity: SyncDeviceIdentity): ByteArray? =
        activeSigningPublicKeys[identity]?.copyOf()

    /**
     * 立即撤销一个设备的后续同步请求。
     *
     * @param identity 待撤销设备身份。
     * @return 原先存在凭据时为真。
     */
    fun revoke(identity: SyncDeviceIdentity): Boolean {
        val removedHash = activeTokenHashes.remove(identity) ?: return false
        removedHash.fill(0)
        return true
    }

    companion object {
        /**
         * 从只含随机身份和 Token SHA-256 的 JSON 文件加载认证器。
         *
         * 文件权限由部署环境保护；服务不需要在磁盘保存明文 Token。
         *
         * @param path 凭据 JSON 数组文件。
         * @return 已加载全部设备摘要的认证器。
         */
        fun fromCredentialFile(path: Path): DeviceTokenAuthenticator {
            val records = CanonicalJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(DeviceCredentialRecord.serializer()),
                Files.readString(path),
            )
            val hashes = records.associate { record ->
                SyncDeviceIdentity(record.workspaceId, record.actorId) to record.tokenSha256.hexToBytes()
            }
            val publicKeys = records.associate { record ->
                SyncDeviceIdentity(record.workspaceId, record.actorId) to record.signingPublicKeyHex.hexToBytes()
            }
            require(hashes.size == records.size) { "设备凭据文件包含重复身份" }
            return DeviceTokenAuthenticator(hashes, publicKeys)
        }

        /**
         * 把 64 字符十六进制摘要解析为字节。
         *
         * @return 32 字节摘要。
         */
        private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

        /** SHA-256 固定字节数。 */
        private const val SHA256_BYTES: Int = 32
    }
}

/** SHA-256 小写十六进制格式。 */
private val TOKEN_HASH_PATTERN: Regex = Regex("[a-f0-9]{64}")

/** Ed25519 公钥的 32 字节小写十六进制格式。 */
private val SIGNING_PUBLIC_KEY_PATTERN: Regex = Regex("[a-f0-9]{64}")
