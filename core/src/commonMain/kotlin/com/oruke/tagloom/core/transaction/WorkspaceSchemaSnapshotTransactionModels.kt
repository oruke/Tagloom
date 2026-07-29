package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 保存 Tag 与 PropertyDefinition 写入共享的事务身份和时间上下文。
 */
data class WorkspaceSchemaWriteContext(
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
) {
    init {
        require(actorSequence > 0L) { "Schema Actor 序号必须大于零" }
        require(actorId == hybridLogicalTime.actorId) { "Schema Actor 与 HLC 不一致" }
        require(committedAt.isNotBlank()) { "Schema 确认时间不能为空" }
    }
}

/**
 * 描述一次 TagDefinition 创建、更新或 tombstone 写入。
 */
data class TagDefinitionSnapshotTransactionRequest(
    /** 待确认的完整标签定义。 */
    val definition: TagDefinition,
    /** 当前已确认快照；创建时为空。 */
    val previousSnapshot: ObjectSnapshot<TagDefinition>? = null,
    /** 写入事务身份与时间。 */
    val context: WorkspaceSchemaWriteContext,
    /** 是否把标签写为 tombstone。 */
    val deleted: Boolean = false,
)

/**
 * 描述一次 PropertyDefinition 创建、更新或 tombstone 写入。
 */
data class PropertyDefinitionSnapshotTransactionRequest(
    /** 待确认的完整属性定义。 */
    val definition: PropertyDefinition,
    /** 当前已确认快照；创建时为空。 */
    val previousSnapshot: ObjectSnapshot<PropertyDefinition>? = null,
    /** 写入事务身份与时间。 */
    val context: WorkspaceSchemaWriteContext,
    /** 是否把属性定义写为 tombstone。 */
    val deleted: Boolean = false,
)

/**
 * 返回工作区 schema 对象事务确认后的权威状态。
 *
 * @param T TagDefinition 或 PropertyDefinition payload 类型。
 */
data class WorkspaceSchemaSnapshotTransactionResult<T>(
    /** 新确认 schema 快照。 */
    val snapshot: ObjectSnapshot<T>,
    /** 与快照一同确认的 Change。 */
    val change: ObjectChange,
    /** 新加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 文件事务提交结果。 */
    val commit: TransactionCommitResult,
)
