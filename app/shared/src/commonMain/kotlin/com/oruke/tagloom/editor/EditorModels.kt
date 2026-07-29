package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId

/**
 * 表示编辑器侧一篇拥有稳定 ID 的 Note 文档。
 */
data class EditorDocument(
    /** 文档稳定不透明 ID。 */
    val id: ObjectId,
    /** 当前内存中的 Note payload。 */
    val note: Note,
    /** 首个权威快照的创建时间；未确认草稿使用内存创建时间。 */
    val createdAt: String = "",
    /** 最近权威快照或当前未确认编辑的更新时间。 */
    val updatedAt: String = "",
    /** 是否已经由工作区 manifest 确认。 */
    val persisted: Boolean = true,
    /** 当前内存内容是否晚于最后确认快照。 */
    val dirty: Boolean = false,
)

/**
 * 保存当前活动 Block 的字符选区，供顶部样式和加注命令使用。
 */
data class EditorSelection(
    /** 活动 Block；尚未聚焦时为 null。 */
    val blockId: BlockId? = null,
    /** 选区起始字符偏移。 */
    val start: Int = 0,
    /** 选区结束字符偏移。 */
    val end: Int = 0,
) {
    /** 是否存在可应用样式或加注的非空选区。 */
    val isNotEmpty: Boolean
        get() = blockId != null && start < end
}
