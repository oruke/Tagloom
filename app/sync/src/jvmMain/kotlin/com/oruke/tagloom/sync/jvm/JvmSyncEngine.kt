package com.oruke.tagloom.sync.jvm

import com.oruke.tagloom.core.sync.BlindArtifactSyncService
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.ChangeDagSyncService
import com.oruke.tagloom.sync.SyncEngine
import com.oruke.tagloom.sync.SyncRequest
import com.oruke.tagloom.sync.SyncResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 组合 JVM Change DAG 与非 Change 密文清单同步能力。
 *
 * 同步服务只交换并验证密文，不负责把远端 Snapshot 直接写成权威工作区状态；合并和冲突解释由上层应用服务继续完成。
 *
 * @param changeSyncService Change DAG heads 交换服务。
 * @param artifactSyncService Snapshot、Blob、Manifest 清单同步服务。
 * @param configuredWorkspaceId 该实例绑定的工作区身份。
 * @param cryptoProvider 同步公开完整性校验所需的密码学运行时。
 * @param configured 当前实例是否已经提供真实工作区同步配置。
 */
class JvmSyncEngine private constructor(
    private val changeSyncService: ChangeDagSyncService?,
    private val artifactSyncService: BlindArtifactSyncService?,
    private val configuredWorkspaceId: WorkspaceId?,
    private val cryptoProvider: CryptoProvider?,
    private val configured: Boolean,
) : SyncEngine {
    /** 关闭后拒绝继续使用当前同步会话。 */
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /**
     * 执行一次 Change DAG 与非 Change 密文清单同步。
     *
     * 两类同步都先在本地完成公开完整性和设备签名验证；本方法只汇总传输统计，不把远端内容自动应用到权威快照。
     *
     * @param request 当前工作区和设备身份。
     * @return 上传、下载和待后续本地合并的冲突统计。
     */
    override suspend fun synchronize(request: SyncRequest): SyncResult {
        check(!closed.get()) { "JVM 同步会话已经关闭" }
        check(configured) { "JVM 同步引擎缺少工作区配置，请调用 createJvmSyncEngine" }
        check(request.workspaceId == configuredWorkspaceId) { "同步请求工作区与引擎配置不一致" }
        // 同步副本可能是 JVM 进程首次触碰密码学能力的入口，不能要求调用方先行初始化。
        cryptoProvider?.initialize()
        val changeReport = requireNotNull(changeSyncService).synchronize(request.workspaceId, request.actorId)
        val artifactReport = requireNotNull(artifactSyncService).synchronize(request.workspaceId, request.actorId)
        // 当前两项 core 服务只交换密文，尚未执行对象合并，因此冲突数量由后续本地合并服务计算。
        return SyncResult(
            uploaded = changeReport.uploadedCount + artifactReport.uploadedCount,
            downloaded = changeReport.downloadedCount + artifactReport.downloadedCount,
            conflicts = 0,
        )
    }

    /**
     * 关闭当前同步会话。
     *
     * 当前传输与文件副本不持有独立密钥；标记关闭后会使旧请求立即失效。
     */
    override suspend fun close() {
        closed.set(true)
    }

    companion object {
        /**
         * 创建已经装配真实依赖的 JVM 同步引擎。
         *
         * @param changeSyncService Change DAG 同步服务。
         * @param artifactSyncService 非 Change 密文同步服务。
         * @param workspaceId 当前引擎绑定的工作区身份。
         * @param cryptoProvider 同步完整性校验所需的密码学实现。
         * @return 可执行同步会话。
         */
        fun create(
            changeSyncService: ChangeDagSyncService,
            artifactSyncService: BlindArtifactSyncService,
            workspaceId: WorkspaceId,
            cryptoProvider: CryptoProvider,
        ): JvmSyncEngine = JvmSyncEngine(
            changeSyncService = changeSyncService,
            artifactSyncService = artifactSyncService,
            configuredWorkspaceId = workspaceId,
            cryptoProvider = cryptoProvider,
            configured = true,
        )

        /**
         * 创建未配置实例供无上下文的 JVM expect 工厂返回。
         *
         * 该实例不会猜测工作区目录、密钥或服务端凭据；调用同步时会给出稳定配置错误。
         *
         * @return 需要显式配置后才能执行的同步能力。
         */
        fun unconfigured(): JvmSyncEngine = JvmSyncEngine(
            changeSyncService = null,
            artifactSyncService = null,
            configuredWorkspaceId = null,
            cryptoProvider = null,
            configured = false,
        )
    }
}
