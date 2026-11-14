package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 保存创建、更新或软删除持久化 `AgentThread` 快照所需的完整事务上下文。
 */
data class AgentThreadSnapshotTransactionRequest(
    /** 会话稳定不透明对象 ID。 */
    val objectId: ObjectId,
    /** 待确认的完整会话载荷。 */
    val thread: AgentThread,
    /** 当前已确认快照；首次创建会话时为空。 */
    val previousSnapshot: ObjectSnapshot<AgentThread>? = null,
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
    /** 是否写为 tombstone（删除会话）。 */
    val deleted: Boolean = false,
) {
    init {
        require(actorSequence > 0L) { "AgentThread Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "AgentThread Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "AgentThread 确认时间不能为空" }
    }
}

/**
 * 返回 `AgentThread` 事务原子确认后的权威快照、逻辑历史节点与加密 manifest。
 */
data class AgentThreadSnapshotTransactionResult(
    /** 新确认的会话快照。 */
    val snapshot: ObjectSnapshot<AgentThread>,
    /** 与快照一同确认的逻辑 Change。 */
    val change: ObjectChange,
    /** 新加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 底层文件事务发布结果。 */
    val commit: TransactionCommitResult,
)
