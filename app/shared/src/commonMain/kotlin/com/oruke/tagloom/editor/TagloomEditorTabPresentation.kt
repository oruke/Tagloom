package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.Note

/**
 * 返回不属于任何 Tab 正文的当前可见 Block。
 *
 * Tabs 容器本身仍在根正文中；其活动页正文由 Tabs 组件在自身边框内继续渲染。
 *
 * @param note 当前 Note。
 * @return 可由根编辑区直接渲染的 Block。
 */
fun TagloomEditorState.visibleRootEditorBlocks(note: Note): List<Block> =
    visibleEditorBlocks(note).filter { block -> nearestTabAncestorId(block, note) == null }

/**
 * 返回一个 Tabs 容器当前活动页内、应由该容器直接渲染的 Block。
 *
 * 嵌套 Tabs 的活动正文会由嵌套容器自行渲染，因此这里只保留最近 Tab 祖先就是当前活动页的内容。
 *
 * @param tabsId Tabs 容器 ID。
 * @param note 当前 Note。
 * @return 当前活动页内的可见 Block。
 */
fun TagloomEditorState.visibleBlocksInTab(
    tabsId: BlockId,
    note: Note,
): List<Block> {
    val activeTabId = activeTabId(tabsId) ?: return emptyList()
    return visibleEditorBlocks(note).filter { block -> nearestTabAncestorId(block, note) == activeTabId }
}

/**
 * 查找一个 Block 最近的 Tab 祖先。
 *
 * @param block 当前 Block。
 * @param note 当前 Note。
 * @return 最近 Tab Block ID；根正文返回空。
 */
internal fun nearestTabAncestorId(
    block: Block,
    note: Note,
): BlockId? {
    val byId = note.blocks.associateBy(Block::id)
    var parentId = block.parentId
    val visited = mutableSetOf<BlockId>()
    while (parentId != null && visited.add(parentId)) {
        val parent = byId[parentId] ?: return null
        if (!parent.deleted && parent.type == BlockTypes.TAB) {
            return parent.id
        }
        parentId = parent.parentId
    }
    return null
}
