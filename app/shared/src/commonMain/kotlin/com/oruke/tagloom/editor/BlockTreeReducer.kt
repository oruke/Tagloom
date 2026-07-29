package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId

/**
 * 区分同级 Block 向上或向下移动。
 */
enum class BlockMoveDirection {
    /** 向前一个同级位置移动。 */
    UP,

    /** 向后一个同级位置移动。 */
    DOWN,
}

/**
 * 以纯函数维护 Block 父边、同级顺序、树形展示和 tombstone 级联。
 */
internal object BlockTreeReducer {
    /**
     * 返回父节点在前、同级按 orderKey/ID 稳定排列的活动 Block。
     *
     * @param blocks Note 中全部 Block，包括 tombstone。
     * @return 活动 Block 的树形先序列表。
     */
    fun orderedActive(blocks: List<Block>): List<Block> {
        val active = blocks.filterNot(Block::deleted)
        val byParent = active.groupBy(Block::parentId)
        val result = mutableListOf<Block>()
        val visited = mutableSetOf<BlockId>()

        /** 深度优先加入一个节点及其子树，同时防御损坏快照中的循环。 */
        fun visit(block: Block) {
            if (!visited.add(block.id)) {
                return
            }
            result += block
            byParent[block.id].orEmpty().sortedWith(BLOCK_ORDER).forEach(::visit)
        }

        byParent[null].orEmpty().sortedWith(BLOCK_ORDER).forEach(::visit)
        active.filterNot { block -> block.id in visited }.sortedWith(BLOCK_ORDER).forEach(::visit)
        return result
    }

    /**
     * 交换目标 Block 与相邻同级的 orderKey，保持稳定 ID 和父边不变。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @param direction 移动方向。
     * @return 不存在相邻同级时返回原集合。
     */
    fun move(
        blocks: List<Block>,
        blockId: BlockId,
        direction: BlockMoveDirection,
    ): List<Block> {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return blocks
        val siblings = blocks
            .filter { block -> !block.deleted && block.parentId == target.parentId }
            .sortedWith(BLOCK_ORDER)
        val index = siblings.indexOfFirst { block -> block.id == blockId }
        val neighborIndex = when (direction) {
            BlockMoveDirection.UP -> index - 1
            BlockMoveDirection.DOWN -> index + 1
        }
        val neighbor = siblings.getOrNull(neighborIndex) ?: return blocks
        return blocks.map { block ->
            when (block.id) {
                target.id -> block.copy(orderKey = neighbor.orderKey)
                neighbor.id -> block.copy(orderKey = target.orderKey)
                else -> block
            }
        }
    }

    /**
     * 把目标 Block 缩进为前一个同级 Block 的最后一个子节点。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @return 没有前一个同级时返回原集合。
     */
    fun indent(
        blocks: List<Block>,
        blockId: BlockId,
    ): List<Block> {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return blocks
        val siblings = blocks
            .filter { block -> !block.deleted && block.parentId == target.parentId }
            .sortedWith(BLOCK_ORDER)
        val index = siblings.indexOfFirst { block -> block.id == blockId }
        val newParent = siblings.getOrNull(index - 1) ?: return blocks
        val children = blocks
            .filter { block -> !block.deleted && block.parentId == newParent.id }
            .sortedWith(BLOCK_ORDER)
        val newOrderKey = orderKey(children.size)
        return blocks.map { block ->
            if (block.id == blockId) block.copy(parentId = newParent.id, orderKey = newOrderKey) else block
        }
    }

    /**
     * 把目标 Block 提升到其父节点之后，成为父节点的同级。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @return 顶层 Block 返回原集合。
     */
    fun outdent(
        blocks: List<Block>,
        blockId: BlockId,
    ): List<Block> {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return blocks
        val parentId = target.parentId ?: return blocks
        val parent = blocks.singleOrNull { block -> block.id == parentId && !block.deleted } ?: return blocks
        val newParentId = parent.parentId
        val destination = blocks
            .filter { block -> !block.deleted && block.parentId == newParentId && block.id != target.id }
            .sortedWith(BLOCK_ORDER)
            .toMutableList()
        val parentIndex = destination.indexOfFirst { block -> block.id == parent.id }
        destination.add((parentIndex + 1).coerceAtLeast(0), target.copy(parentId = newParentId))
        val orderById = destination.mapIndexed { index, block -> block.id to orderKey(index) }.toMap()
        return blocks.map { block ->
            if (block.id == target.id) {
                block.copy(parentId = newParentId, orderKey = orderById.getValue(block.id))
            } else if (block.id in orderById) {
                block.copy(orderKey = orderById.getValue(block.id))
            } else {
                block
            }
        }
    }

    /**
     * 把目标 Block 及其全部后代标记为 tombstone，而不销毁内容或稳定 ID。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @return 带级联 tombstone 的 Block 集合。
     */
    fun tombstone(
        blocks: List<Block>,
        blockId: BlockId,
    ): List<Block> {
        if (blocks.none { block -> block.id == blockId && !block.deleted }) {
            return blocks
        }
        val descendants = mutableSetOf(blockId)
        var changed: Boolean
        do {
            val before = descendants.size
            blocks.filter { block -> block.parentId in descendants }.forEach { block -> descendants += block.id }
            changed = descendants.size != before
        } while (changed)
        return blocks.map { block -> if (block.id in descendants) block.copy(deleted = true) else block }
    }

    /**
     * 计算活动 Block 的树深度，供编辑器缩进展示。
     *
     * @param block 目标 Block。
     * @param blocks 当前全部 Block。
     * @return 从零开始且最大为 12 的安全深度。
     */
    fun depth(
        block: Block,
        blocks: List<Block>,
    ): Int {
        val byId = blocks.associateBy(Block::id)
        val visited = mutableSetOf<BlockId>()
        var current = block.parentId
        var depth = 0
        while (current != null && visited.add(current) && depth < MAX_RENDER_DEPTH) {
            depth += 1
            current = byId[current]?.parentId
        }
        return depth
    }

    /**
     * 生成固定宽度、词法可排序的同级顺序键。
     *
     * @param index 同级序号。
     * @return 十位十进制顺序键。
     */
    private fun orderKey(index: Int): String = index.toString().padStart(10, '0')

    /** 同级稳定顺序，ID 只在旧数据 orderKey 重复时提供确定性回退。 */
    private val BLOCK_ORDER: Comparator<Block> = compareBy(Block::orderKey, { block -> block.id.value })

    /** 防止损坏树导致 UI 生成无界缩进。 */
    private const val MAX_RENDER_DEPTH: Int = 12
}
