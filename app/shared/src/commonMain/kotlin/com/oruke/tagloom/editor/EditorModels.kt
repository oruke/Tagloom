package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision

/**
 * 表示编辑器侧一篇拥有稳定 ID 的 Note 文档。
 */
data class EditorDocument(
    /** 文档稳定不透明 ID。 */
    val id: ObjectId,
    /** 当前内存中的 Note payload。 */
    val note: Note,
    /** 最近一次确认的权威 revision；草稿为空。 */
    val revision: ObjectRevision? = null,
    /** 首个权威快照的创建时间；未确认草稿使用内存创建时间。 */
    val createdAt: String = "",
    /** 最近权威快照或当前未确认编辑的更新时间。 */
    val updatedAt: String = "",
    /** 是否已经由工作区 manifest 确认。 */
    val persisted: Boolean = true,
    /** 当前内存内容是否晚于最后确认快照。 */
    val dirty: Boolean = false,
    /** 当前解锁会话内递增的草稿版本，仅用于区分连续编辑，不进入权威快照。 */
    val draftVersion: Long = 0L,
)

/**
 * 保存按文档顺序规范化的字符选区；跨块时 start/end 分别属于首尾块。
 */
data class EditorSelection(
    /** 按正文顺序排列的首块；尚未聚焦时为 null。 */
    val blockId: BlockId? = null,
    /** 首块内的选区起始字符偏移。 */
    val start: Int = 0,
    /** 尾块内的选区结束字符偏移；单块时仍属于首块。 */
    val end: Int = 0,
    /** 跨块选区包含的连续可编辑块；单块选区为空，保持原有调用兼容。 */
    val blockIds: List<BlockId> = emptyList(),
) {
    /** 是否跨越两个或更多可编辑块。 */
    val isCrossBlock: Boolean get() = blockIds.size > 1

    /** 选区涉及的全部块，按正文展示顺序排列。 */
    val ids: List<BlockId> get() = blockIds.ifEmpty { listOfNotNull(blockId) }

    /** 是否存在可应用样式或加注的非空选区。 */
    val isNotEmpty: Boolean
        get() = blockId != null && (isCrossBlock || start < end)
}
