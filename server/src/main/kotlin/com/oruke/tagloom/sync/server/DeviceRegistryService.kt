package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.*
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** 串行处理授权、设备生命周期和恢复确认点；所有状态变化先落盘再更新会话。 */
internal class DeviceRegistryService(
    /** 持有独占写入租约的登记存储。 */
    private val store: DeviceRegistryStore,
    /** 部署者配置的首次登记凭据摘要；为空时禁止首次登记。 */
    private val bootstrapTokenSha256: String?,
    /** 首次创建登记库时可导入的旧凭据文件。 */
    private val legacyFile: Path? = null,
) : SyncDeviceAuthenticator, AutoCloseable {
    /** 已确认的完整状态，仅在同步锁内访问。 */
    private var state = store.load(legacyFile)

    init {
        bootstrapTokenSha256?.let(::requireSyncDigest)
        require(state.workspaces.map { it.workspaceId }.distinct().size == state.workspaces.size) { "登记库工作区重复" }
        require(state.workspaces.mapNotNull { it.recoveryId }.distinct().size == state.workspaces.count { it.recoveryId != null }) { "登记库恢复身份重复" }
        state.workspaces.forEach { workspace ->
            requireSyncIdentity(workspace.workspaceId.value)
            require(workspace.devices.isNotEmpty()) { "登记库工作区缺少设备历史" }
            require(workspace.devices.map { it.actorId }.distinct().size == workspace.devices.size) { "登记库设备重复" }
            workspace.devices.forEach {
                requireSyncIdentity(it.actorId.value)
                requireSyncDigest(it.signingPublicKeyHex)
                it.tokenSha256?.let(::requireSyncDigest)
                require((it.tokenSha256 == null) == (it.revokedAt != null)) { "登记库设备状态不一致" }
                Instant.parse(it.registeredAt)
                it.revokedAt?.let(Instant::parse)
            }
            require(listOf(workspace.recoveryId, workspace.recoveryTokenSha256, workspace.snapshot, workspace.archiveId).all { it == null } ||
                listOf(workspace.recoveryId, workspace.recoveryTokenSha256, workspace.snapshot, workspace.archiveId).all { it != null }) { "登记库恢复记录不完整" }
            workspace.recoveryId?.let(::requireSyncDigest)
            workspace.recoveryTokenSha256?.let(::requireSyncDigest)
            workspace.archiveId?.let(::requireSyncIdentity)
            workspace.snapshot?.let {
                require(it.workspaceId == workspace.workspaceId && it.revision > 0 && it.size > 0) { "恢复副本记录无效" }
                requireSyncDigest(it.sha256)
                Instant.parse(it.createdAt)
            }
        }
    }

    /**
     * 校验活动设备凭据，撤销与更新在同一锁内立即对后续请求生效。
     * @param identity 当前请求身份。
     * @param bearerToken 当前访问凭据。
     * @return 是否通过认证。
     */
    @Synchronized
    override fun authenticate(identity: SyncDeviceIdentity, bearerToken: String): Boolean {
        store.checkAvailable()
        return matchesSyncToken(bearerToken, deviceOrNull(identity)?.tokenSha256)
    }

    /**
     * 返回历史设备公钥，撤销不删除已确认密文的验证材料。
     * @param identity 密文原作者身份。
     * @return 已登记的公钥副本。
     */
    @Synchronized
    override fun signingPublicKey(identity: SyncDeviceIdentity): ByteArray? {
        store.checkAvailable()
        return deviceOrNull(identity)?.signingPublicKeyHex?.syncHexBytes()
    }

    /**
     * 判断原作者是否仍允许产生新的服务端载荷。
     * @param identity 原作者身份。
     * @return 未撤销且已登记时为真。
     */
    @Synchronized
    override fun isActive(identity: SyncDeviceIdentity): Boolean {
        store.checkAvailable()
        return deviceOrNull(identity)?.tokenSha256 != null
    }

    /**
     * 部署者首次登记工作区，相同请求重试不覆盖已存在状态。
     * @param token 部署者引导凭据。
     * @param workspaceId 新工作区随机身份。
     * @param request 首台设备材料。
     * @return 已确认的首台设备描述。
     */
    @Synchronized
    fun createWorkspace(token: String, workspaceId: WorkspaceId, request: SyncWorkspaceRegistration): SyncDeviceDescription {
        store.checkAvailable()
        if (!matchesSyncToken(token, bootstrapTokenSha256)) rejectSync(HttpStatusCode.Unauthorized, "bootstrap_authentication_failed")
        requireSyncIdentity(workspaceId.value)
        val registration = SyncDeviceRegistration(request.tokenSha256, request.signingPublicKeyHex)
        val existing = state.workspaces.firstOrNull { it.workspaceId == workspaceId }
        if (existing != null) {
            val first = existing.devices.singleOrNull()
            if (first?.actorId == request.actorId && first.tokenSha256 == request.tokenSha256 && first.signingPublicKeyHex == request.signingPublicKeyHex)
                return first.description()
            rejectSync(HttpStatusCode.Conflict, "workspace_already_registered")
        }
        val device = newDevice(request.actorId, registration)
        commit(RegisteredSyncWorkspace(workspaceId, listOf(device)))
        return device.description()
    }

    /**
     * 由同工作区活动设备授权新设备，已撤销身份绝不重新启用。
     * @param access 当前请求凭据。
     * @param actorId 新设备身份。
     * @param request 新设备材料。
     * @return 已持久化设备描述。
     */
    @Synchronized
    fun register(access: SyncDeviceAccess, actorId: ActorId, request: SyncDeviceRegistration): SyncDeviceDescription =
        enroll(authorized(access), actorId, request)

    /**
     * 分页读取当前工作区的公钥目录，包含撤销记录且不暴露认证摘要。
     * @param access 当前请求凭据。
     * @param limit 每页上限，范围为 1 到 100。
     * @param cursor 上次响应提供的不透明游标。
     * @return 设备页及后续游标。
     */
    @Synchronized
    fun list(access: SyncDeviceAccess, limit: Int, cursor: String?): SyncDevicePage {
        val workspace = authorized(access)
        require(limit in 1..100) { "设备分页大小无效" }
        val after = cursor?.let {
            require(it.length <= 512) { "设备游标无效" }
            val decoded = Base64.getUrlDecoder().decode(it).decodeToString().split('|')
            require(decoded.size == 2 && decoded[0] == workspace.workspaceId.value) { "设备游标不属于当前工作区" }
            decoded[1].also(::requireSyncIdentity)
        }
        val candidates = workspace.devices.sortedBy { it.actorId.value }.filter { after == null || it.actorId.value > after }
        val page = candidates.take(limit)
        val next = if (candidates.size > limit) Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${workspace.workspaceId.value}|${page.last().actorId.value}".encodeToByteArray()) else null
        return SyncDevicePage(page.map { it.description() }, next)
    }

    /**
     * 使用摘要前置条件更新访问凭据，公钥和设备身份保持稳定。
     * @param access 当前请求凭据。
     * @param actorId 目标设备身份。
     * @param update 旧摘要前置条件及新摘要。
     * @return 更新后的公开设备描述。
     */
    @Synchronized
    fun rotate(access: SyncDeviceAccess, actorId: ActorId, update: SyncCredentialUpdate): SyncDeviceDescription {
        val workspace = authorized(access)
        requireSyncDigest(update.expectedTokenSha256); requireSyncDigest(update.tokenSha256)
        val device = workspace.devices.firstOrNull { it.actorId == actorId } ?: rejectSync(HttpStatusCode.NotFound, "device_not_found")
        if (device.revokedAt != null) rejectSync(HttpStatusCode.Conflict, "device_revoked")
        if (device.tokenSha256 == update.tokenSha256) return device.description()
        if (device.tokenSha256 != update.expectedTokenSha256) rejectSync(HttpStatusCode.Conflict, "credential_changed")
        val changed = device.copy(tokenSha256 = update.tokenSha256)
        commit(workspace.copy(devices = workspace.devices.map { if (it.actorId == actorId) changed else it }))
        return changed.description()
    }

    /**
     * 撤销设备并清除访问摘要；没有恢复副本时保留最后一个管理入口。
     * @param access 当前请求凭据。
     * @param actorId 目标设备身份。
     */
    @Synchronized
    fun revoke(access: SyncDeviceAccess, actorId: ActorId) {
        val workspace = authorized(access)
        val device = workspace.devices.firstOrNull { it.actorId == actorId } ?: rejectSync(HttpStatusCode.NotFound, "device_not_found")
        if (device.revokedAt != null) return
        if (workspace.snapshot == null && workspace.devices.count { it.revokedAt == null } == 1)
            rejectSync(HttpStatusCode.Conflict, "last_active_device")
        commit(workspace.copy(devices = workspace.devices.map {
            if (it.actorId == actorId) it.copy(tokenSha256 = null, revokedAt = Instant.now().toString()) else it
        }))
    }

    /**
     * 原子确认完整恢复副本和对应恢复凭据，拒绝过期设备覆盖新版本。
     * @param access 上传设备凭据，上传结束后重新验证。
     * @param expectedRevision 上传者已知的服务端版本，首次为零。
     * @param recoveryId Recovery Key 派生的定位符。
     * @param recoveryTokenSha256 恢复访问凭据摘要。
     * @param archive 已经完整落盘的密文归档。
     * @return 已确认的副本元数据。
     */
    @Synchronized
    fun publishRecovery(access: SyncDeviceAccess, expectedRevision: Long, recoveryId: String,
                        recoveryTokenSha256: String, archive: StoredRecoveryArchive): SyncRecoverySnapshot {
        val workspace = authorized(access)
        requireSyncDigest(recoveryId); requireSyncDigest(recoveryTokenSha256)
        workspace.snapshot?.let { existing ->
            // 上传成功但响应丢失时允许原请求重试；不同内容即使版本相邻也不能覆盖。
            if (expectedRevision < Long.MAX_VALUE && existing.revision == expectedRevision + 1 &&
                existing.sha256 == archive.sha256 && existing.size == archive.size && workspace.recoveryId == recoveryId &&
                workspace.recoveryTokenSha256 == recoveryTokenSha256) return existing
        }
        if ((workspace.snapshot?.revision ?: 0L) != expectedRevision) rejectSync(HttpStatusCode.Conflict, "recovery_revision_changed")
        if (state.workspaces.any { it.workspaceId != workspace.workspaceId && it.recoveryId == recoveryId })
            rejectSync(HttpStatusCode.Conflict, "recovery_identity_conflict")
        val snapshot = SyncRecoverySnapshot(workspace.workspaceId, Math.addExact(expectedRevision, 1), archive.sha256, archive.size, Instant.now().toString())
        commit(workspace.copy(recoveryId = recoveryId, recoveryTokenSha256 = recoveryTokenSha256, snapshot = snapshot, archiveId = archive.id))
        return snapshot
    }

    /**
     * 凭恢复用途的独立认证材料定位原工作区并登记新设备；不会接收 Recovery Key。
     * @param recoveryId 恢复定位符。
     * @param token 恢复访问凭据。
     * @param actorId 当前新设备随机身份。
     * @param request 当前新设备材料。
     * @return 原工作区和可下载恢复副本。
     */
    @Synchronized
    fun recover(recoveryId: String, token: String, actorId: ActorId, request: SyncDeviceRegistration): SyncRecoveredWorkspace {
        store.checkAvailable()
        requireSyncDigest(recoveryId)
        val workspace = state.workspaces.firstOrNull { it.recoveryId == recoveryId }
        if (workspace == null || !matchesSyncToken(token, workspace.recoveryTokenSha256))
            rejectSync(HttpStatusCode.Unauthorized, "recovery_authentication_failed")
        val snapshot = workspace.snapshot ?: rejectSync(HttpStatusCode.Conflict, "recovery_not_ready")
        return SyncRecoveredWorkspace(workspace.workspaceId, enroll(workspace, actorId, request), snapshot)
    }

    /**
     * 取得已认证设备可下载的恢复记录。
     * @param access 当前设备请求凭据。
     * @return 当前完整副本所在的工作区记录。
     */
    @Synchronized
    fun recoverySnapshot(access: SyncDeviceAccess): RegisteredSyncWorkspace = authorized(access).also {
        if (it.snapshot == null) rejectSync(HttpStatusCode.NotFound, "recovery_not_ready")
    }

    /**
     * 在执行管理操作的同一临界区内复核当前凭据，防止认证后被并发撤销。
     * @param access 单次请求凭据。
     * @return 已授权的工作区记录。
     */
    private fun authorized(access: SyncDeviceAccess): RegisteredSyncWorkspace {
        if (!authenticate(access.identity, access.token)) rejectSync(HttpStatusCode.Unauthorized, "authentication_failed")
        return state.workspaces.first { it.workspaceId == access.identity.workspaceId }
    }

    /**
     * 生成经过协议校验的新设备记录。
     * @param actorId 新设备身份。
     * @param request 新设备公钥和凭据摘要。
     * @return 尚未持久化的活动设备记录。
     */
    private fun newDevice(actorId: ActorId, request: SyncDeviceRegistration): RegisteredSyncDevice {
        requireSyncIdentity(actorId.value); requireSyncDigest(request.tokenSha256); requireSyncDigest(request.signingPublicKeyHex)
        return RegisteredSyncDevice(actorId, request.tokenSha256, request.signingPublicKeyHex, Instant.now().toString())
    }

    /**
     * 将新身份追加到已授权目录，相同重试幂等，冲突身份拒绝覆盖。
     * @param workspace 已授权工作区。
     * @param actorId 新设备身份。
     * @param request 公开登记材料。
     * @return 已确认设备描述。
     */
    private fun enroll(workspace: RegisteredSyncWorkspace, actorId: ActorId, request: SyncDeviceRegistration): SyncDeviceDescription {
        val device = newDevice(actorId, request)
        workspace.devices.firstOrNull { it.actorId == actorId }?.let {
            if (it.revokedAt == null && it.tokenSha256 == request.tokenSha256 && it.signingPublicKeyHex == request.signingPublicKeyHex) return it.description()
            rejectSync(HttpStatusCode.Conflict, "device_already_registered")
        }
        commit(workspace.copy(devices = workspace.devices + device))
        return device.description()
    }

    /**
     * 发布新工作区状态；写入失败时不会把未确认状态当成有效认证信息。
     * @param workspace 待确认的完整工作区登记。
     */
    private fun commit(workspace: RegisteredSyncWorkspace) {
        val next = state.copy(workspaces = state.workspaces.filterNot { it.workspaceId == workspace.workspaceId } + workspace)
        store.save(next)
        state = next
    }

    /**
     * 查找设备生命周期记录，调用方必须持有会话锁。
     * @param identity 完整工作区设备身份。
     * @return 对应记录或空。
     */
    private fun deviceOrNull(identity: SyncDeviceIdentity): RegisteredSyncDevice? = state.workspaces
        .firstOrNull { it.workspaceId == identity.workspaceId }?.devices?.firstOrNull { it.actorId == identity.actorId }

    /** 终止认证会话并释放持久化租约。 */
    @Synchronized
    override fun close() = store.close()
}
