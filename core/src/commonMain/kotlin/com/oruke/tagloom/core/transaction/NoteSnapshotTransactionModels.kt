package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 描述创建、更新、删除或恢复一篇 Note 的完整事务输入。
 */
data class NoteSnapshotTransactionRequest(
    /** 待写入 Note 的稳定对象 ID。 */
    val objectId: ObjectId,
    /** 待规范化并写入的完整 Note payload。 */
    val note: Note,
    /** 当前已确认快照；创建新 Note 时为空。 */
    val previousSnapshot: ObjectSnapshot<Note>? = null,
    /** 产生本次 revision 的设备或 Agent。 */
    val actorId: ActorId,
    /** 当前 Actor 严格递增的对象序号。 */
    val actorSequence: Long,
    /** 跨对象事务 ID。 */
    val transactionId: TransactionId,
    /** 冲突排序和历史展示使用的 HLC。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** 本次确认时间，使用 UTC ISO-8601 文本。 */
    val committedAt: String,
    /** 是否把 Note 写为保留期内 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(actorSequence > 0L) { "Note revision 序号必须大于零" }
        require(committedAt.isNotBlank()) { "Note 确认时间不能为空" }
        require(actorId == hybridLogicalTime.actorId) { "Note revision Actor 必须与 HLC Actor 一致" }
        require(previousSnapshot == null || previousSnapshot.header.objectId == objectId) {
            "Note 旧快照对象身份不一致"
        }
    }
}

/**
 * 返回已由 manifest 确认的新快照、Change 和事务状态。
 */
data class NoteSnapshotTransactionResult(
    /** 新的权威 Note 快照。 */
    val snapshot: ObjectSnapshot<Note>,
    /** 与快照同时确认的 Change DAG 节点。 */
    val change: ObjectChange,
    /** 可用于刷新当前会话 manifest 的完整加密字节。 */
    val encryptedManifest: ByteArray,
    /** 原子事务提交结果。 */
    val commit: TransactionCommitResult,
)
