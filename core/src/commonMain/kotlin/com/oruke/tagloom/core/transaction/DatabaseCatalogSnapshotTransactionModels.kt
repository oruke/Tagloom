package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 保存 Database/View 写入共享的事务身份、连续 Actor 序号和确认时间。
 */
data class WorkspaceDatabaseWriteContext(
    /** 当前写入 Actor。 */
    val actorId: ActorId,
    /** 本事务消耗的首个 Actor 序号。 */
    val actorSequenceStart: Long,
    /** 跨全部对象快照与 Change 的事务 ID。 */
    val transactionId: TransactionId,
    /** 因果排序和展示使用的 HLC。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** UTC ISO-8601 确认时间。 */
    val committedAt: String,
) {
    init {
        require(actorSequenceStart > 0L) { "Database Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "Database Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "Database 确认时间不能为空" }
    }
}

/**
 * 描述 Database 与首个 View 的原子创建。
 */
data class DatabaseCatalogCreateRequest(
    /** 待创建 Database 完整定义。 */
    val database: DatabaseDefinition,
    /** 与 Database 同时确认的首个 View。 */
    val initialView: ViewDefinition,
    /** 写入事务身份与时间。 */
    val context: WorkspaceDatabaseWriteContext,
)

/**
 * 描述一个 DatabaseDefinition 的更新或 tombstone 写入。
 */
data class DatabaseDefinitionSnapshotTransactionRequest(
    /** 待确认 Database 完整定义。 */
    val database: DatabaseDefinition,
    /** 当前已确认 Database 快照。 */
    val previousSnapshot: ObjectSnapshot<DatabaseDefinition>,
    /** 当前 Database 的全部活动 View，用于验证默认视图。 */
    val views: List<ViewDefinition>,
    /** 写入事务身份与时间。 */
    val context: WorkspaceDatabaseWriteContext,
    /** 是否写为 tombstone。 */
    val deleted: Boolean = false,
)

/**
 * 描述一个 ViewDefinition 的创建、更新或 tombstone 写入。
 */
data class ViewDefinitionSnapshotTransactionRequest(
    /** 待确认 View 完整定义。 */
    val view: ViewDefinition,
    /** 当前已确认 View 快照；创建新视图时为空。 */
    val previousSnapshot: ObjectSnapshot<ViewDefinition>? = null,
    /** 写入事务身份与时间。 */
    val context: WorkspaceDatabaseWriteContext,
    /** 是否写为 tombstone。 */
    val deleted: Boolean = false,
)

/**
 * 返回 Database/View 事务确认后的权威快照、历史节点和 manifest。
 */
data class DatabaseCatalogSnapshotTransactionResult(
    /** 本事务确认的 Database 快照；仅更新 View 时为空。 */
    val databaseSnapshot: ObjectSnapshot<DatabaseDefinition>? = null,
    /** 本事务确认的 View 快照；仅更新 Database 时为空。 */
    val viewSnapshot: ObjectSnapshot<ViewDefinition>? = null,
    /** 与快照同时确认的 Change，顺序与 Actor 序号一致。 */
    val changes: List<ObjectChange>,
    /** 新加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 文件事务提交结果。 */
    val commit: TransactionCommitResult,
    /** 本事务实际消耗的连续 Actor 序号数量。 */
    val consumedActorSequences: Long,
)
