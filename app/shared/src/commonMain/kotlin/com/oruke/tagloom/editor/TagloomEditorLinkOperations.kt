package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.*

/**
 * 为链接命令准备真实空段落，已有内容或子树保留在原位置，不用空引用占据草稿。
 * @param blockId 命令发生的 Block。
 * @return 接受网址录入的空段落身份；原块已删除时为空。
 */
internal fun TagloomEditorState.prepareLinkInsertion(blockId: BlockId): BlockId? {
    val note = selectedDocument?.note ?: return null
    val block = note.blocks.firstOrNull { it.id == blockId && !it.deleted } ?: return null
    val empty =
        block.editableRichText()?.plainText?.isBlank() == true && note.blocks.none { !it.deleted && it.parentId == blockId }
    return if (empty) {
        if (block.type != BlockTypes.PARAGRAPH) turnBlockInto(blockId, EditorBlockType.TEXT)
        blockId
    } else insertBlockAfter(blockId)?.blockId
}

/**
 * 展开引用所在页签并找到可滚动的根 Block，支持嵌套块和 Tabs 内的反链定位。
 * @param blockId 目标引用或录入段落身份。
 * @return 当前页签中应滚动到的根块；目标不可用时为空。
 */
internal fun TagloomEditorState.revealLinkBlock(blockId: BlockId): BlockId? {
    val note = selectedDocument?.note ?: return null
    val blocks = note.blocks.filterNot { it.deleted }.associateBy { it.id }
    var current = blocks[blockId] ?: return null
    val seen = mutableSetOf<BlockId>()
    while (seen.add(current.id)) {
        val parent = blocks[current.parentId] ?: return current.id
        if (current.content is TabBlockContent && parent.content == TabsBlockContent) selectTab(parent.id, current.id)
        current = parent
    }
    return null
}
