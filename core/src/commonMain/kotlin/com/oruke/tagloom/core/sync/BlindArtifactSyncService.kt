package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId

/** 抽象本地 Snapshot、Blob 与 Manifest 密文同步副本。 */
interface LocalBlindArtifactReplicaPort {
    /**
     * 列出指定类型的本地内容 ID。
     *
     * @param kind 非 Change 密文载荷类型。
     * @return 按字典序稳定排列的内容 ID。
     */
    suspend fun listArtifactIds(kind: SyncArtifactKind): List<String>

    /**
     * 读取本地密文载荷。
     *
     * @param kind 非 Change 密文载荷类型。
     * @param artifactId 内容 ID。
     * @return 不存在时为空。
     */
    suspend fun loadArtifact(
        kind: SyncArtifactKind,
        artifactId: String,
    ): BlindSyncArtifact?

    /**
     * 幂等保存已完成内容寻址和设备签名校验的远端密文。
     *
     * 应用到工作区前仍必须执行信封 AEAD、身份与 schema 校验；Snapshot 不能绕过事务直接成为权威状态。
     *
     * @param artifact 待保存密文载荷。
     */
    suspend fun saveRemoteArtifact(artifact: BlindSyncArtifact)
}

/** 抽象非 Change 密文载荷的已认证网络传输。 */
interface BlindArtifactTransportPort {
    /**
     * 获取服务端 Snapshot、Blob 与 Manifest 内容清单。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @return 按类型分组的服务端内容 ID。
     */
    suspend fun listArtifactInventory(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): SyncArtifactInventoryResponse

    /**
     * 幂等推送一批同类型或混合类型的非 Change 密文。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param request 推送批次。
     * @return 首次存储与重复数量。
     */
    suspend fun pushArtifacts(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncPushRequest,
    ): SyncPushResponse

    /**
     * 按类型和内容 ID 精确拉取密文。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param request 精确拉取请求。
     * @return 服务端找到的密文载荷。
     */
    suspend fun fetchArtifacts(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncArtifactFetchRequest,
    ): SyncArtifactFetchResponse
}

/**
 * 使用内容清单差集同步 Snapshot、Blob 与 Manifest 密文。
 *
 * Change 需要父边与 heads，因此必须继续使用 [ChangeDagSyncService]；这里的 Snapshot 只是可选远端副本，下载后不能直接覆盖本地权威快照。
 */
class BlindArtifactSyncService(
    /** 当前设备的非 Change 密文副本。 */
    private val localReplica: LocalBlindArtifactReplicaPort,
    /** 已认证盲同步传输。 */
    private val transport: BlindArtifactTransportPort,
    /** 内容寻址与密文摘要校验服务。 */
    private val integrityService: SyncArtifactIntegrityService,
    /** 设备 Ed25519 作者签名验证服务。 */
    private val signatureService: DeviceArtifactSignatureService,
    /** 当前工作区设备历史公钥目录。 */
    private val publicKeyResolver: SyncDevicePublicKeyResolver,
) {
    /**
     * 对三种非 Change 密文类型执行先推后拉的幂等差集同步。
     *
     * @param workspaceId 当前解锁工作区。
     * @param actorId 当前已认证设备。
     * @return 上传、重复与下载统计。
     */
    suspend fun synchronize(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): BlindArtifactSyncReport {
        val inventory = transport.listArtifactInventory(workspaceId, actorId)
        val remoteIdsByKind = inventory.entries.associate { entry -> entry.kind to entry.artifactIds.toSet() }
        var uploaded = 0
        var duplicates = 0
        var downloaded = 0

        // 步骤 1：按类型上传本地有而服务端清单没有的密文，服务端仍以内容 ID 最终幂等去重。
        NON_CHANGE_SYNC_KINDS.forEach { kind ->
            val localIds = localReplica.listArtifactIds(kind).toSet()
            val uploadIds = (localIds - remoteIdsByKind[kind].orEmpty()).sorted()
            uploadIds.chunked(MAX_SYNC_BATCH_SIZE).forEach { batchIds ->
                val batch = batchIds.map { artifactId ->
                    requireNotNull(localReplica.loadArtifact(kind, artifactId)) { "本地同步清单与载荷不一致" }
                }
                batch.forEach { artifact -> requireValidArtifact(workspaceId, kind, artifact) }
                val response = transport.pushArtifacts(workspaceId, actorId, SyncPushRequest(batch))
                uploaded += response.storedCount
                duplicates += response.duplicateCount
            }

            // 步骤 2：只拉取初始远端清单中本地缺少的密文，并在落盘前验证内容寻址与原始 Actor 签名。
            val downloadIds = (remoteIdsByKind[kind].orEmpty() - localIds).sorted()
            downloadIds.chunked(MAX_SYNC_BATCH_SIZE).forEach { batchIds ->
                val response = transport.fetchArtifacts(
                    workspaceId,
                    actorId,
                    SyncArtifactFetchRequest(kind, batchIds),
                )
                val byId = response.artifacts.associateBy(BlindSyncArtifact::artifactId)
                check(batchIds.all(byId::containsKey)) { "同步服务缺少清单中声明的密文载荷" }
                batchIds.forEach { artifactId ->
                    val artifact = byId.getValue(artifactId)
                    requireValidArtifact(workspaceId, kind, artifact)
                    localReplica.saveRemoteArtifact(artifact)
                    downloaded += 1
                }
            }
        }
        return BlindArtifactSyncReport(uploaded, duplicates, downloaded)
    }

    /**
     * 验证载荷身份、内容寻址和原始设备签名。
     *
     * @param workspaceId 当前工作区。
     * @param expectedKind 当前同步类型。
     * @param artifact 待验证载荷。
     */
    private suspend fun requireValidArtifact(
        workspaceId: WorkspaceId,
        expectedKind: SyncArtifactKind,
        artifact: BlindSyncArtifact,
    ) {
        require(artifact.descriptor.workspaceId == workspaceId) { "同步 artifact 工作区身份不匹配" }
        require(artifact.descriptor.kind == expectedKind) { "同步 artifact 类型不匹配" }
        check(integrityService.verify(artifact)) { "同步 artifact 内容寻址完整性校验失败" }
        val publicKey = publicKeyResolver.resolve(workspaceId, artifact.descriptor.actorId)
            ?: throw IllegalStateException("同步 artifact 作者未知")
        try {
            check(signatureService.verify(artifact, publicKey)) { "同步 artifact 设备签名校验失败" }
        } finally {
            publicKey.fill(0)
        }
    }

    private companion object {
        /** 使用内容清单差集而不是 DAG heads 的密文类型。 */
        val NON_CHANGE_SYNC_KINDS: List<SyncArtifactKind> = listOf(
            SyncArtifactKind.SNAPSHOT,
            SyncArtifactKind.BLOB,
            SyncArtifactKind.MANIFEST,
        )
    }
}
