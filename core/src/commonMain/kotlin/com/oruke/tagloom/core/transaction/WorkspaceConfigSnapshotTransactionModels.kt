package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 保存创建或更新工作区单例 `WorkspaceConfig` 快照所需的完整事务上下文。
 */
data class WorkspaceConfigSnapshotTransactionRequest(
    /** 固定工作区配置对象 ID（默认且必须为 [WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID]）。 */
    val objectId: ObjectId = WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID,
    /** 待确认的完整配置载荷。 */
    val config: WorkspaceConfig,
    /** 当前已确认快照；首次创建配置时为空。 */
    val previousSnapshot: ObjectSnapshot<WorkspaceConfig>? = null,
    /** 当前写入 Actor。 */
    val actorId: ActorId,
    /** Actor 本地严格递增序号。 */
    val actorSequence: Long,
    /** 跨快照与 Change 的事务 ID。 */
    val transactionId: TransactionId,
    /** 因果排序与冲突裁决使用的 HLC。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** UTC ISO-8601 确认时间。 */
    val committedAt: String,
    /** 是否写为 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(objectId == WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID) { "WorkspaceConfig 对象 ID 必须使用内置单例标识" }
        require(actorSequence > 0L) { "WorkspaceConfig Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "WorkspaceConfig Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "WorkspaceConfig 确认时间不能为空" }
    }
}

/**
 * 返回 `WorkspaceConfig` 事务原子确认后的权威快照、逻辑历史节点与加密 manifest。
 */
data class WorkspaceConfigSnapshotTransactionResult(
    /** 新确认的工作区配置快照。 */
    val snapshot: ObjectSnapshot<WorkspaceConfig>,
    /** 与快照一同确认的逻辑 Change。 */
    val change: ObjectChange,
    /** 新加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 底层文件事务发布结果。 */
    val commit: TransactionCommitResult,
)
