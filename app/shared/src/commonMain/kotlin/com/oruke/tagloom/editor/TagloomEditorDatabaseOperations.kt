package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.ObjectId

/**
 * 把指定文档中的临时文本 Block 绑定为 Database 引用 Block。
 *
 * 创建 Database 涉及异步加密事务，因此必须按文档 ID 定位原始命令 Block，不能依赖完成时仍被选中的笔记。
 *
 * @param documentId 发起 Slash 命令时所在的文档 ID。
 * @param blockId 待转换的稳定 Block ID。
 * @param databaseId 已经确认写入的 Database ID。
 * @return 找到且成功转换目标 Block 时为 true。
 */
internal fun TagloomEditorState.bindDatabaseBlock(
    documentId: ObjectId,
    blockId: BlockId,
    databaseId: DatabaseId,
): Boolean {
    val documentIndex = documents.indexOfFirst { document -> document.id == documentId }
    if (documentIndex < 0) return false
    val document = documents[documentIndex]
    val blockIndex = document.note.blocks.indexOfFirst { block -> block.id == blockId && !block.deleted }
    if (blockIndex < 0) return false
    val blocks = document.note.blocks.toMutableList()
    blocks[blockIndex] = blocks[blockIndex].copy(
        type = BlockTypes.DATABASE,
        content = DatabaseBlockContent(databaseId),
    )
    documents[documentIndex] = document.copy(
        note = document.note.copy(blocks = blocks),
        updatedAt = currentEditorTimestamp(),
        dirty = true,
        draftVersion = document.draftVersion + 1L,
    )
    if (selectedDocumentId == documentId) clearSelection()
    return true
}
