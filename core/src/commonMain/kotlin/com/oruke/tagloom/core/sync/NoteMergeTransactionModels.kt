package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.TransactionCommitResult
import kotlinx.serialization.Serializable

/** 描述把两个并发 Note heads 合并并原子确认所需的完整上下文。 */
data class NoteMergeTransactionRequest(
    /** 当前设备 manifest 已确认的权威 Note 快照。 */
    val currentSnapshot: ObjectSnapshot<Note>,
    /** 两个并发候选共同祖先的 Note payload。 */
    val baseNote: Note,
    /** 产生共同祖先的 Change；初始快照可以为空。 */
    val baseChangeId: ChangeId?,
    /** 与当前权威快照对应的本地 head Change。 */
    val localChange: ObjectChange,
    /** 已完成认证和重放的远端候选 Note 与 head Change。 */
    val remoteVersion: NoteMergeVersion,
    /** 包含共同祖先与两个 heads 的完整 Change 集合。 */
    val knownChanges: Collection<ObjectChange>,
    /** 产生 Merge Change 的当前设备或 Agent。 */
    val mergeActorId: ActorId,
    /** 合并设备本地严格递增的对象修订序号。 */
    val mergeSequence: Long,
    /** 本次 Merge Change 的 Hybrid Logical Clock。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** 原子确认快照、Change 与冲突记录的事务标识。 */
    val transactionId: TransactionId,
    /** 新快照和冲突记录使用的 UTC ISO-8601 时间。 */
    val mergedAt: String,
) {
    init {
        require(mergeSequence >= 0) { "合并修订序号不能为负数" }
        require(hybridLogicalTime.actorId == mergeActorId) { "合并 HLC 必须属于合并 Actor" }
        require(mergedAt.isNotBlank()) { "合并时间不能为空" }
    }
}

/** 描述已由 manifest 确认的 Note 合并事务结果。 */
data class NoteMergeTransactionResult(
    /** 新的权威 Note 快照。 */
    val snapshot: ObjectSnapshot<Note>,
    /** 连接两个并发 heads 的 Merge Change。 */
    val mergeChange: ObjectChange,
    /** 已与快照一起原子确认的冲突证据。 */
    val conflicts: List<ConflictRecord>,
    /** 底层事务及派生索引更新结果。 */
    val commit: TransactionCommitResult,
)

/** 保存到 Merge Change 操作中的可审计合并摘要。 */
@Serializable
data class NoteMergeOperationPayload(
    /** 共同祖先 Change；初始快照可以为空。 */
    val baseChangeId: ChangeId?,
    /** 合并后 Note payload 的 SHA-256。 */
    val mergedNoteSha256: String,
    /** 新权威快照 revision。 */
    val targetSnapshotRevision: String,
    /** 同一事务生成的全部不透明冲突 ID。 */
    val conflictIds: List<String>,
) {
    init {
        require(mergedNoteSha256.matches(Regex("[0-9a-f]{64}"))) { "合并 Note 摘要格式无效" }
        require(targetSnapshotRevision.isNotBlank()) { "目标快照 revision 不能为空" }
        require(conflictIds.distinct().size == conflictIds.size) { "合并冲突 ID 不能重复" }
    }
}
