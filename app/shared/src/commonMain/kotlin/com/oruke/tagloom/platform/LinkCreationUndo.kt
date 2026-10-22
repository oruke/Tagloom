package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.*

/** 解锁会话内保存的链接创建撤销上下文，不落明文临时文件或系统 SavedState。 */
data class LinkCreationUndo(
    /** 新建或复用的链接身份。 */
    val linkId: ObjectId,
    /** 刚创建时的完整快照，用于保守判断之后是否发生用户编辑。 */
    val createdNote: Note,
    /** 创建时间，用于避免身份重用或恢复后误删对象。 */
    val createdAt: String,
    /** 是否为本次操作新建；复用的链接永不因撤销引用而删除。 */
    val created: Boolean,
    /** 来源笔记，独立创建时为空。 */
    val sourceId: ObjectId?,
    /** 本次插入引用的稳定 Block 身份。 */
    val referenceId: BlockId?,
)

/** 返回撤销后确认的来源，以及实际写成 tombstone 的链接身份。 */
data class LinkCreationUndoResult(
    /** 更新后的来源，来源已删除或引用已消失时为空。 */
    val source: WorkspaceNote?,
    /** 仅在未编辑且没有其他引用时删除刚建链接。 */
    val deletedLinkId: ObjectId?,
)
