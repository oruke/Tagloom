package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId

/** 抽象客户端本地加密 Change 副本。 */
interface LocalChangeReplicaPort {
    /**
     * 列出本地每个对象的 Change DAG heads。
     *
     * @return 按对象 ID 稳定排列的 heads。
     */
    suspend fun listChangeHeads(): List<ObjectChangeHeads>

    /**
     * 判断本地是否已有指定 Change。
     *
     * @param artifactId Change 内容 ID。
     * @return 已存在时为真。
     */
    suspend fun containsChange(artifactId: String): Boolean

    /**
     * 读取本地加密 Change。
     *
     * @param artifactId Change 内容 ID。
     * @return 不存在时为空。
     */
    suspend fun loadChange(artifactId: String): BlindSyncArtifact?

    /**
     * 保存已经完成公开完整性校验的远端加密 Change。
     *
     * 实现必须按 artifactId 幂等；应用到权威快照前仍要执行信封 AEAD、身份与领域 schema 校验。
     *
     * @param artifact 待保存 Change。
     */
    suspend fun saveRemoteChange(artifact: BlindSyncArtifact)
}

/** 抽象已认证的盲同步网络传输。 */
interface BlindSyncTransportPort {
    /**
     * 查询服务端工作区 Change heads。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @return 服务端对象 heads。
     */
    suspend fun listChangeHeads(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): SyncHeadsResponse

    /**
     * 幂等推送一批加密 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @param request 批量推送请求。
     * @return 首次存储与重复数量。
     */
    suspend fun pushChanges(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncPushRequest,
    ): SyncPushResponse

    /**
     * 按内容 ID 精确拉取一批加密 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @param request 精确拉取请求。
     * @return 找到的密文 Change。
     */
    suspend fun fetchChanges(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncFetchRequest,
    ): SyncFetchResponse
}

/** 抽象当前工作区仍受信任或仅用于验证历史的设备签名公钥目录。 */
fun interface SyncDevicePublicKeyResolver {
    /**
     * 返回指定 Actor 的 Ed25519 公钥副本。
     *
     * 撤销设备后仍应保留其历史公钥，但不得再向该设备签发 Token。
     *
     * @param workspaceId 当前工作区。
     * @param actorId artifact 声明的原始作者。
     * @return 32 字节公钥副本；未知作者为空。
     */
    fun resolve(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): ByteArray?
}

/**
 * 编排 AnySync 风格的 heads 交换与按缺失内容 ID 遍历，但不引入协作 CRDT。
 *
 * @param localReplica 当前设备的加密 Change 副本。
 * @param transport 已认证盲同步传输。
 * @param integrityService 客户端公开完整性校验服务。
 * @param signatureService 设备 Ed25519 作者签名验证服务。
 * @param publicKeyResolver 当前工作区设备公钥目录。
 */
class ChangeDagSyncService(
    /** 当前设备的加密 Change 副本。 */
    private val localReplica: LocalChangeReplicaPort,
    /** 已认证盲同步传输。 */
    private val transport: BlindSyncTransportPort,
    /** 客户端公开完整性校验服务。 */
    private val integrityService: SyncArtifactIntegrityService,
    /** 验证 artifact 原始 Actor 签名。 */
    private val signatureService: DeviceArtifactSignatureService,
    /** 按工作区和 Actor 解析可信历史公钥。 */
    private val publicKeyResolver: SyncDevicePublicKeyResolver,
) {
    /**
     * 执行一次先推后拉的幂等同步会话。
     *
     * 服务端仅路由密文 DAG；远端 Change 保存后仍由应用服务在本地解密、三方合并并生成新的权威快照。
     *
     * @param workspaceId 当前解锁工作区。
     * @param actorId 当前已认证设备。
     * @return 本次上传、重复与下载统计。
     */
    suspend fun synchronize(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): ChangeDagSyncReport {
        val initialRemoteHeads = transport.listChangeHeads(workspaceId, actorId)
        val uploadCandidates = collectUploadCandidates(workspaceId, initialRemoteHeads)
        var uploadedCount = 0
        var duplicateCount = 0
        uploadCandidates.chunked(MAX_SYNC_BATCH_SIZE).forEach { batch ->
            val response = transport.pushChanges(
                workspaceId = workspaceId,
                actorId = actorId,
                request = SyncPushRequest(batch),
            )
            uploadedCount += response.storedCount
            duplicateCount += response.duplicateCount
        }

        val finalRemoteHeads = transport.listChangeHeads(workspaceId, actorId)
        val downloadedCount = pullMissingChanges(workspaceId, actorId, finalRemoteHeads)
        return ChangeDagSyncReport(
            uploadedCount = uploadedCount,
            duplicateUploadCount = duplicateCount,
            downloadedCount = downloadedCount,
            remoteObjectCount = finalRemoteHeads.objects.size,
        )
    }

    /**
     * 从本地 heads 沿父边收集服务端可能缺少的 Change。
     *
     * 只把服务端当前 heads 当作已知截断点；分叉场景可能重传共同祖先，但服务端内容寻址去重可保证安全。
     *
     * @param workspaceId 当前工作区。
     * @param remoteHeads 会话开始时的服务端 heads。
     * @return 父节点优先、内容 ID 去重的上传列表。
     */
    private suspend fun collectUploadCandidates(
        workspaceId: WorkspaceId,
        remoteHeads: SyncHeadsResponse,
    ): List<BlindSyncArtifact> {
        val knownRemoteHeads = remoteHeads.objects.flatMap(ObjectChangeHeads::headArtifactIds).toSet()
        val pending = ArrayDeque<String>()
        localReplica.listChangeHeads().flatMap(ObjectChangeHeads::headArtifactIds).forEach(pending::addLast)
        val visited = mutableSetOf<String>()
        val collected = mutableMapOf<String, BlindSyncArtifact>()

        while (pending.isNotEmpty()) {
            val artifactId = pending.removeFirst()
            if (!visited.add(artifactId) || artifactId in knownRemoteHeads) {
                continue
            }
            val artifact = checkNotNull(localReplica.loadChange(artifactId)) {
                "本地 Change DAG 缺少节点 $artifactId"
            }
            check(artifact.descriptor.workspaceId == workspaceId) { "本地 Change 工作区身份不匹配" }
            require(artifact.descriptor.kind == SyncArtifactKind.CHANGE) { "本地 Change 副本混入非 Change 载荷" }
            check(integrityService.verify(artifact)) { "本地 Change 内容寻址完整性校验失败" }
            check(verifyAuthenticity(artifact)) { "本地 Change 设备签名校验失败" }
            collected[artifactId] = artifact
            artifact.descriptor.parentArtifactIds.forEach(pending::addLast)
        }
        return parentFirst(collected)
    }

    /**
     * 从远端 heads 沿父边逐批精确拉取全部本地缺失 Change。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param remoteHeads 推送完成后的服务端 heads。
     * @return 验证并首次保存的远端 Change 数量。
     */
    private suspend fun pullMissingChanges(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        remoteHeads: SyncHeadsResponse,
    ): Int {
        val pending = ArrayDeque<String>()
        remoteHeads.objects.flatMap(ObjectChangeHeads::headArtifactIds).forEach(pending::addLast)
        val visited = mutableSetOf<String>()
        var downloaded = 0

        while (pending.isNotEmpty()) {
            val requestIds = buildList {
                while (pending.isNotEmpty() && size < MAX_SYNC_BATCH_SIZE) {
                    val candidate = pending.removeFirst()
                    if (!visited.add(candidate)) {
                        continue
                    }
                    if (localReplica.containsChange(candidate)) {
                        val local = checkNotNull(localReplica.loadChange(candidate)) {
                            "本地 Change 存在性与读取结果不一致"
                        }
                        local.descriptor.parentArtifactIds.forEach(pending::addLast)
                    } else {
                        add(candidate)
                    }
                }
            }
            if (requestIds.isEmpty()) {
                continue
            }
            val response = transport.fetchChanges(workspaceId, actorId, SyncFetchRequest(requestIds))
            val byId = response.artifacts.associateBy(BlindSyncArtifact::artifactId)
            val missingIds = requestIds.filterNot(byId::containsKey)
            check(missingIds.isEmpty()) { "同步服务缺少其声明的 Change: ${missingIds.joinToString()}" }

            requestIds.forEach { artifactId ->
                val artifact = byId.getValue(artifactId)
                check(artifact.descriptor.workspaceId == workspaceId) { "远端 Change 工作区身份不匹配" }
                check(artifact.descriptor.kind == SyncArtifactKind.CHANGE) { "远端响应混入非 Change 载荷" }
                check(integrityService.verify(artifact)) { "远端 Change 内容寻址完整性校验失败" }
                check(verifyAuthenticity(artifact)) { "远端 Change 设备签名校验失败" }
                localReplica.saveRemoteChange(artifact)
                downloaded += 1
                artifact.descriptor.parentArtifactIds.forEach(pending::addLast)
            }
        }
        return downloaded
    }

    /**
     * 使用工作区设备目录验证 artifact 的原始作者签名。
     *
     * @param artifact 已通过内容寻址校验的密文 Change。
     * @return 作者已登记且签名有效时为真。
     */
    private suspend fun verifyAuthenticity(artifact: BlindSyncArtifact): Boolean {
        val descriptor = artifact.descriptor
        val publicKey = publicKeyResolver.resolve(descriptor.workspaceId, descriptor.actorId) ?: return false
        return try {
            signatureService.verify(artifact, publicKey)
        } finally {
            publicKey.fill(0)
        }
    }

    /**
     * 对待上传 Change 做稳定拓扑排序，尽量让父节点先于子节点到达服务端。
     *
     * @param artifacts 以内容 ID 索引的待上传 Change。
     * @return 父节点优先；结构异常时稳定失败而不上传部分 DAG。
     */
    private fun parentFirst(artifacts: Map<String, BlindSyncArtifact>): List<BlindSyncArtifact> {
        val indegree = artifacts.keys.associateWith { 0 }.toMutableMap()
        val children = mutableMapOf<String, MutableList<String>>()
        artifacts.values.forEach { artifact ->
            artifact.descriptor.parentArtifactIds.filter(artifacts::containsKey).forEach { parentId ->
                indegree[artifact.artifactId] = indegree.getValue(artifact.artifactId) + 1
                children.getOrPut(parentId) { mutableListOf() } += artifact.artifactId
            }
        }
        val ready = indegree.filterValues { it == 0 }.keys.sorted().toMutableList()
        val ordered = mutableListOf<BlindSyncArtifact>()
        while (ready.isNotEmpty()) {
            val next = ready.removeAt(0)
            ordered += artifacts.getValue(next)
            children[next].orEmpty().sorted().forEach { childId ->
                val remaining = indegree.getValue(childId) - 1
                indegree[childId] = remaining
                if (remaining == 0) {
                    ready += childId
                    ready.sort()
                }
            }
        }
        check(ordered.size == artifacts.size) { "本地同步 Change DAG 包含循环" }
        return ordered
    }
}
