package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.ObjectChangeHeads
import com.oruke.tagloom.core.sync.SyncArtifactKind

/** 表示内容寻址盲存储的幂等写入结果。 */
enum class BlindSyncPutResult {
    /** artifactId 首次写入。 */
    STORED,

    /** artifactId 已存在且完整内容一致。 */
    DUPLICATE,
}

/** 抽象同步节点的密文内容寻址存储。 */
interface BlindSyncStore {
    /**
     * 幂等保存一个已经完成公开完整性校验的密文载荷。
     *
     * @param artifact 待保存密文载荷。
     * @return 首次写入或完全一致的重复。
     */
    suspend fun put(artifact: BlindSyncArtifact): BlindSyncPutResult

    /**
     * 列出工作区全部对象的 Change DAG heads。
     *
     * @param workspaceId 随机工作区标识。
     * @return 按对象 ID 排序的 heads。
     */
    suspend fun listChangeHeads(workspaceId: WorkspaceId): List<ObjectChangeHeads>

    /**
     * 按请求顺序读取存在的 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param artifactIds 待读取内容 ID。
     * @return 服务端实际存在的 Change。
     */
    suspend fun getChanges(
        workspaceId: WorkspaceId,
        artifactIds: List<String>,
    ): List<BlindSyncArtifact> = getArtifacts(workspaceId, SyncArtifactKind.CHANGE, artifactIds)

    /**
     * 列出工作区指定类型的全部内容 ID。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 密文载荷类型。
     * @return 按字典序稳定排列的内容 ID。
     */
    suspend fun listArtifactIds(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
    ): List<String>

    /**
     * 按请求顺序读取指定类型中实际存在的密文载荷。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 密文载荷类型。
     * @param artifactIds 待读取内容 ID。
     * @return 服务端实际存在且类型一致的密文载荷。
     */
    suspend fun getArtifacts(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
        artifactIds: List<String>,
    ): List<BlindSyncArtifact>
}

/** 表示相同内容 ID 被用于不同字节或元数据的安全冲突。 */
class BlindSyncContentConflictException : IllegalStateException("同步内容 ID 已存在但内容不一致")
