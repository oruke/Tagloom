package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import kotlinx.serialization.Serializable

/** 首次登记工作区；只传递随机身份、访问摘要和设备公钥。 */
@Serializable
data class SyncWorkspaceRegistration(
    /** 首台设备的随机身份。 */
    val actorId: ActorId,
    /** 客户端安全生成的访问 Token 的 SHA-256。 */
    val tokenSha256: String,
    /** Ed25519 公钥的十六进制表示。 */
    val signingPublicKeyHex: String,
)

/** 已授权设备登记新设备时提供的公开材料。 */
@Serializable
data class SyncDeviceRegistration(
    /** 客户端安全生成的访问 Token 的 SHA-256。 */
    val tokenSha256: String,
    /** Ed25519 公钥的十六进制表示；同一身份不允许换公钥。 */
    val signingPublicKeyHex: String,
)

/** 通过旧摘要前置条件保护并发凭据更新。 */
@Serializable
data class SyncCredentialUpdate(
    /** 调用方已知的当前访问 Token 摘要。 */
    val expectedTokenSha256: String,
    /** 新访问 Token 摘要。 */
    val tokenSha256: String,
)

/** 可返回给同工作区设备的公钥目录条目，不包含认证摘要。 */
@Serializable
data class SyncDeviceDescription(
    /** 设备的随机身份。 */
    val actorId: ActorId,
    /** 用于验证历史密文的 Ed25519 公钥。 */
    val signingPublicKeyHex: String,
    /** 服务端确认登记的 UTC 时间。 */
    val registeredAt: String,
    /** 撤销时间；为空表示仍可认证。 */
    val revokedAt: String? = null,
)

/** 按随机设备身份排序的游标分页结果。 */
@Serializable
data class SyncDevicePage(
    /** 当前页的设备与历史公钥。 */
    val devices: List<SyncDeviceDescription>,
    /** 下一页的不透明游标；为空表示已到末尾。 */
    val nextCursor: String? = null,
)

/** 一个已经完整上传并确认的密文恢复副本，不代表客户端已执行双向合并。 */
@Serializable
data class SyncRecoverySnapshot(
    /** 恢复副本所属的稳定工作区。 */
    val workspaceId: WorkspaceId,
    /** 服务端单调递增的副本版本，用于拒绝过期覆盖。 */
    val revision: Long,
    /** 完整密文归档的 SHA-256；客户端下载后仍须验证 AEAD 和 manifest。 */
    val sha256: String,
    /** 完整密文归档的字节数。 */
    val size: Long,
    /** 服务端确认副本的 UTC 时间。 */
    val createdAt: String,
)

/** Recovery Key 派生凭据授权新设备后返回的恢复定位信息。 */
@Serializable
data class SyncRecoveredWorkspace(
    /** 找回的原工作区身份；不得生成新身份冒充恢复。 */
    val workspaceId: WorkspaceId,
    /** 已登记的新设备。 */
    val device: SyncDeviceDescription,
    /** 可以下载的已确认完整密文副本。 */
    val snapshot: SyncRecoverySnapshot,
)
