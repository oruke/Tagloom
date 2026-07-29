package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 保存创建或更新一个 SavedQuery 所需的完整事务上下文。
 */
data class SavedQuerySnapshotTransactionRequest(
    /** 稳定保存查询对象 ID。 */
    val objectId: ObjectId,
    /** 待确认查询 payload。 */
    val query: SavedQuery,
    /** 当前已确认快照；创建时为空。 */
    val previousSnapshot: ObjectSnapshot<SavedQuery>? = null,
    /** 当前写入 Actor。 */
    val actorId: ActorId,
    /** Actor 本地严格递增序号。 */
    val actorSequence: Long,
    /** 跨快照与 Change 的事务 ID。 */
    val transactionId: TransactionId,
    /** 因果排序和展示使用的 HLC。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** UTC ISO-8601 确认时间。 */
    val committedAt: String,
    /** 是否写为 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(actorSequence > 0L) { "SavedQuery Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "SavedQuery Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "SavedQuery 确认时间不能为空" }
    }
}

/**
 * 返回 SavedQuery 事务确认后的权威状态。
 */
data class SavedQuerySnapshotTransactionResult(
    /** 新确认查询快照。 */
    val snapshot: ObjectSnapshot<SavedQuery>,
    /** 与快照一同确认的 Change。 */
    val change: ObjectChange,
    /** 新加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 文件事务提交结果。 */
    val commit: TransactionCommitResult,
)
