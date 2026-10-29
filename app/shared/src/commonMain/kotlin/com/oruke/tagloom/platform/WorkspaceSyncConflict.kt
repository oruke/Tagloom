package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision

/** 当前解锁会话可查看的同步双版本，来源为工作区加密历史，不进入日志或本机设置。 */
data class WorkspaceSyncConflict(
    /** 冲突历史的不透明稳定身份。 */
    val id: String,
    /** 冲突对象稳定身份。 */
    val objectId: ObjectId,
    /** 对象类别的协议名称，UI 使用资源映射。 */
    val kind: String,
    /** 当前确认快照的 revision，恢复时必须再次匹配。 */
    val currentRevision: ObjectRevision,
    /** 第一份保留版本的可读名称。 */
    val localTitle: String,
    /** 第一份保留版本的完整可读内容。 */
    val localContent: String,
    /** 第一份版本是否为删除状态。 */
    val localDeleted: Boolean,
    /** 第二份保留版本的可读名称。 */
    val remoteTitle: String,
    /** 第二份保留版本的完整可读内容。 */
    val remoteContent: String,
    /** 第二份版本是否为删除状态。 */
    val remoteDeleted: Boolean,
    /** 冲突确认时间，使用统一日期展示设置。 */
    val createdAt: String,
    /** 是否已经通过正式事务处理；历史版本仍保留。 */
    val resolved: Boolean,
    /** 可以独立确认的字段或 Block；旧历史及整体约束冲突为空。 */
    val fields: List<WorkspaceSyncConflictField> = emptyList(),
)

/** 在解锁会话内展示一个可独立选择的冲突字段，不向 UI 暴露协议路径或 JSON。 */
data class WorkspaceSyncConflictField(
    /** 仅供确认请求定位字段的稳定身份，不作为界面文案。 */
    val id: String,
    /** 由 UI 映射资源的有限业务字段类别。 */
    val labelKey: String,
    /** 属性名称、选项名称或 Block 位置等补充说明，不包含协议路径。 */
    val detailLabel: String? = null,
    /** 第一份保留版本的可读字段内容。 */
    val localContent: String,
    /** 第二份保留版本的可读字段内容。 */
    val remoteContent: String,
    /** 第一份版本不存在此字段或成员，与显式空值区分。 */
    val localMissing: Boolean,
    /** 第二份版本不存在此字段或成员，与显式空值区分。 */
    val remoteMissing: Boolean,
    /** 此字段是否已通过正式事务确认处理。 */
    val resolved: Boolean,
    /** 第一份字段版本是待清理的 Block tombstone。 */
    val localDeleted: Boolean = false,
    /** 第二份字段版本是待清理的 Block tombstone。 */
    val remoteDeleted: Boolean = false,
    /** 第一份完整 Block 的可读结构；普通字段为空。 */
    val localBlock: WorkspaceSyncConflictBlock? = null,
    /** 第二份完整 Block 的可读结构；普通字段为空。 */
    val remoteBlock: WorkspaceSyncConflictBlock? = null,
)

/** 提供 Block 的类型、可读位置和格式差异，避免历史选择仅显示相同正文。 */
data class WorkspaceSyncConflictBlock(
    /** 由 UI 统一映射为本地化名称的 Block 类型。 */
    val type: String,
    /** 该版本中同一父节点下的一起始顺序，包含当前待删除块。 */
    val position: Int,
    /** 父块在该版本正文中的一起始位置；顶层为空。 */
    val parentPosition: Int? = null,
    /** 父块已有的可读正文或页签名称；无文字时使用父块序号。 */
    val parentTitle: String? = null,
    /** 本版本与另一版本的 Block 扩展格式是否不同。 */
    val formattingDiffers: Boolean = false,
    /** 代码块是否自动换行；其它类型为空。 */
    val codeWrap: Boolean? = null,
    /** 有序列表的起始序号；其它类型为空。 */
    val numberedListStart: Int? = null,
    /** 其它扩展格式的可读值；不包含内部字段名或稳定身份。 */
    val otherFormattingValues: List<String> = emptyList(),
)
