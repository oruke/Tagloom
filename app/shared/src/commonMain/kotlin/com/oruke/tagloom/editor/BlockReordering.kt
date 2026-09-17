package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId

/**
 * 松开拖动后一次性确认同级顺序；预览和取消均不修改笔记。
 * @param blockId 被拖动的 Block，子树随父边保持完整。
 * @param anchorId 落点相邻的同级 Block。
 * @param after 是否放在锚点及其子树之后。
 */
internal fun TagloomEditorState.reorderBlock(blockId: BlockId, anchorId: BlockId, after: Boolean) {
    updateSelectedNote { note -> note.copy(blocks = reorderSiblingBlocks(note.blocks, blockId, anchorId, after)) }
}

/**
 * 重排目标同级集合，保留内容、父边、稳定 ID 和 tombstone。
 * @param blocks 当前笔记全部 Block。
 * @param blockId 要移动的活动 Block。
 * @param anchorId 相邻锚点，必须与目标拥有同一父节点。
 * @param after 是否插入锚点后。
 * @return 新顺序；过期或跨父级落点返回原集合。
 */
internal fun reorderSiblingBlocks(
    blocks: List<Block>,
    blockId: BlockId,
    anchorId: BlockId,
    after: Boolean
): List<Block> {
    val source = blocks.singleOrNull { it.id == blockId && !it.deleted } ?: return blocks
    val anchor = blocks.singleOrNull { it.id == anchorId && !it.deleted } ?: return blocks
    if (source.id == anchor.id || source.parentId != anchor.parentId) return blocks
    val siblings = blocks.filter { !it.deleted && it.parentId == source.parentId }
        .sortedWith(compareBy(Block::orderKey, { it.id.value }))
    val reordered = siblings.filterNot { it.id == blockId }.toMutableList()
    reordered.add(reordered.indexOfFirst { it.id == anchorId } + if (after) 1 else 0, source)
    if (siblings == reordered) return blocks
    // 重建同级顺序也能处理旧快照中的重复 orderKey，避免交换相同键后看似拖动却没有变化。
    val order = reordered.mapIndexed { index, block -> block.id to index.toString().padStart(10, '0') }.toMap()
    return blocks.map { block -> order[block.id]?.let { block.copy(orderKey = it) } ?: block }
}
