package com.oruke.tagloom.platform

import kotlinx.serialization.Serializable

/** 当前设备对工作区的存储策略；两种模式均以本地加密快照为权威。 */
@Serializable
enum class WorkspaceStorageMode {
    /** 仅在本地保存，不向服务器传输。 */
    LOCAL,
    /** 自动合并其他设备确认内容并发布服务器密文副本。 */
    SERVER,
}

/** 客户端可展示的远端恢复状态，不携带设备 Token、恢复凭据或本机路径。 */
data class WorkspaceServerRecoveryStatus(
    /** 用户配置的 HTTPS 服务器地址。 */
    val serverUrl: String,
    /** 最近成功上传或下载的确认时间；尚未发布时为空。 */
    val uploadedAt: String?,
    /** 尚未完成设备登记或当前主密钥已轮换，需要输入 Recovery Key。 */
    val requiresRecoveryKey: Boolean,
    /** 此设备当前启用的模式，恢复和其他设备的模式互不影响。 */
    val mode: WorkspaceStorageMode = WorkspaceStorageMode.LOCAL,
    /** 存在尚未上传的本地确认版本。 */
    val pendingChanges: Boolean = false,
    /** 服务器已确认此设备登记，不再需要登记访问码。 */
    val registered: Boolean = false,
    /** 已加密保留、尚未由用户处理的同步冲突数量。 */
    val conflictsCount: Int = 0,
)

/** 把服务器备份传输与本机认证流程封装为共享 UI 可调用的能力。 */
interface WorkspaceServerRecoveryGateway {
    /** @return 当前会话认证的完整冲突历史，锁定后拒绝读取。 */
    suspend fun conflicts(): List<WorkspaceSyncConflict> = emptyList()

    /**
     * 将所选冲突内容作为新业务变更恢复，并原子标记所处理字段。
     * @param conflictId 待处理冲突的不透明身份。
     * @param useRemote 是否使用第二份版本；否则使用第一份版本。
     * @param expectedRevision 审阅时的当前对象 revision，变化后拒绝旧选择。
     * @param fieldId 仅处理此字段；为空时处理全部未处理字段，旧历史则恢复完整版本。
     */
    suspend fun resolveConflict(
        conflictId: String,
        useRemote: Boolean,
        expectedRevision: com.oruke.tagloom.core.model.ObjectRevision,
        fieldId: String? = null,
    ) {
        error("sync_conflict_unavailable")
    }
    /**
     * 在应用私有目录准备服务器模式的新工作区，等待用户确认保存恢复密钥。
     * @param serverUrl 用于提前校验的服务器地址，此阶段不发送请求。
     * @param pin 当前设备的可选 PIN。
     * @return 尚未落盘的一次性恢复密钥。
     */
    suspend fun prepareCreation(serverUrl: String, pin: String?): WorkspacePreparedCreation

    /** @return 当前已解锁工作区的服务器恢复配置；尚未配置时为空。 */
    suspend fun status(): WorkspaceServerRecoveryStatus?

    /**
     * 配置并上传当前已确认的完整密文副本，失败不得覆盖远端确认点。
     * @param serverUrl 服务器 HTTPS 地址。
     * @param bootstrapToken 首次登记工作区时所需的部署者访问码。
     * @param recoveryKeyText 首次启用或主密钥轮换后的 Recovery Key。
     * @return 新确认的上传状态。
     */
    suspend fun publish(serverUrl: String, bootstrapToken: String, recoveryKeyText: String): WorkspaceServerRecoveryStatus

    /** @return 服务器模式检查并合并双方确认版本，按需发布；本地模式无网络，未配置时为空。 */
    suspend fun updateIfChanged(): WorkspaceServerRecoveryStatus?

    /** @return 取消传输并持久化本地模式后的状态；未配置服务器时为空。 */
    suspend fun useLocalMode(): WorkspaceServerRecoveryStatus?

    /**
     * 通过原 Recovery Key 从服务器下载，恢复到应用管理的新目录并启用服务器模式。
     * @param serverUrl 服务器 HTTPS 地址。
     * @param recoveryKeyText 用户离线保存的 Recovery Key。
     * @param pin 新设备的可选本机 PIN。
     * @return 已认证工作区。
     */
    suspend fun restore(serverUrl: String, recoveryKeyText: String, pin: String?): WorkspaceOpenResult?
}
