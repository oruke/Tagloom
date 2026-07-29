package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 描述权威对象快照支持的业务对象类型。
 */
@Serializable
enum class ObjectType {
    /** 笔记对象。 */
    NOTE,

    /** 超级标签对象。 */
    TAG,

    /** 属性定义对象。 */
    PROPERTY,

    /** 保存查询对象。 */
    SAVED_QUERY,

    /** 工作区配置对象。 */
    WORKSPACE_CONFIG,
}

/**
 * 描述某个参与者为对象生成的单调递增修订号。
 */
@Serializable
data class ObjectRevision(
    /** 生成此修订的设备或 Agent。 */
    val actorId: ActorId,
    /** 参与者本地严格递增的序号。 */
    val sequence: Long,
) {
    init {
        require(sequence >= 0) { "对象修订序号不能为负数" }
    }
}

/**
 * 描述所有权威对象快照共享的协议头。
 *
 * 快照头参与加密信封认证，用于识别对象身份、schema 和历史连续性。
 */
@Serializable
data class SnapshotHeader(
    /** 对象 payload 的 schema 版本。 */
    val schemaVersion: Int,
    /** 工作区内稳定且不透明的对象标识。 */
    val objectId: ObjectId,
    /** 对象的业务类型。 */
    val objectType: ObjectType,
    /** 当前快照的对象修订号。 */
    val revision: ObjectRevision,
    /** 产生当前快照的最后一个 Change；初始快照可以为空。 */
    val lastChangeId: ChangeId? = null,
    /** 上一个已确认快照的认证哈希；初始快照可以为空。 */
    val previousSnapshotHash: String? = null,
    /** 对象创建时间，使用 UTC ISO-8601 文本。 */
    val createdAt: String,
    /** 当前快照确认时间，使用 UTC ISO-8601 文本。 */
    val updatedAt: String,
    /** 对象是否为等待保留期清理的 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion 必须大于 0" }
        require(createdAt.isNotBlank()) { "createdAt 不能为空" }
        require(updatedAt.isNotBlank()) { "updatedAt 不能为空" }
    }
}

/**
 * 将通用快照头与具体业务 payload 组合为权威对象快照。
 *
 * @param T 具体对象 payload 的类型。
 */
@Serializable
data class ObjectSnapshot<T>(
    /** 通用快照协议头。 */
    val header: SnapshotHeader,
    /** 对象当前已确认的业务载荷。 */
    val payload: T,
)
