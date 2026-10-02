package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.SyncDeviceDescription
import com.oruke.tagloom.core.sync.SyncRecoverySnapshot
import kotlinx.serialization.Serializable

/** 服务端认证状态的原子快照，仅包含公开身份和认证摘要。 */
@Serializable
internal data class DeviceRegistrySnapshot(
    /** 登记库格式版本，未知版本禁止启动。 */
    val version: Int = 1,
    /** 独立授权边界内的工作区登记。 */
    val workspaces: List<RegisteredSyncWorkspace> = emptyList(),
)

/** 工作区设备目录和最近一次已确认恢复副本的共同提交边界。 */
@Serializable
internal data class RegisteredSyncWorkspace(
    /** 与密文目录一致的随机工作区身份。 */
    val workspaceId: WorkspaceId,
    /** 包含撤销记录的设备公钥目录。 */
    val devices: List<RegisteredSyncDevice>,
    /** Recovery Key 派生的独立定位符；旧迁移工作区可以为空。 */
    val recoveryId: String? = null,
    /** 恢复访问凭据的摘要，不是 Recovery Key 或其解密用途派生值。 */
    val recoveryTokenSha256: String? = null,
    /** 最近一次完整密文归档；与恢复凭据共同原子发布。 */
    val snapshot: SyncRecoverySnapshot? = null,
    /** 服务端生成的归档文件标识，仅用于受控路径寻址。 */
    val archiveId: String? = null,
)

/** 服务端持久化的设备状态；撤销后移除访问摘要并保留签名公钥。 */
@Serializable
internal data class RegisteredSyncDevice(
    /** 随机设备身份。 */
    val actorId: ActorId,
    /** 活动访问凭据摘要；撤销后为空。 */
    val tokenSha256: String?,
    /** 稳定历史签名公钥，不随 Token 更新变化。 */
    val signingPublicKeyHex: String,
    /** 首次确认登记的 UTC 时间。 */
    val registeredAt: String,
    /** 撤销的 UTC 时间；为空时必须存在活动 Token 摘要。 */
    val revokedAt: String? = null,
) {
    /** @return 不泄漏访问摘要的公开设备描述。 */
    fun description(): SyncDeviceDescription = SyncDeviceDescription(actorId, signingPublicKeyHex, registeredAt, revokedAt)
}

/** 单次请求的认证材料，不使用 data class，避免自动生成包含 Token 的诊断文本。 */
internal class SyncDeviceAccess(
    /** 请求声明的工作区和设备身份。 */
    val identity: SyncDeviceIdentity,
    /** 当前请求的 Bearer Token，只在请求生命周期内使用。 */
    val token: String,
)
