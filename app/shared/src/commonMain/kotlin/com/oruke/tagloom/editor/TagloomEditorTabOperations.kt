package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent

/**
 * 返回 Tabs 容器当前会话应展示的活动页签。
 *
 * 优先使用 UI 会话选择，其次使用 Note 显式默认页签，最后使用首个活动 tab；会话选择不写入快照。
 *
 * @param tabsId Tabs 容器 Block ID。
 * @return 活动 tab；容器不存在或无活动 tab 时为空。
 */
fun TagloomEditorState.activeTabId(tabsId: BlockId): BlockId? {
    val note = selectedDocument?.note ?: return null
    val tabs = activeTabs(note, tabsId)
    val selected = activeTabIds[tabsId]
    return when {
        selected in tabs -> selected
        note.defaultTabId in tabs -> note.defaultTabId
        else -> tabs.firstOrNull()
    }?.also { tabId -> activeTabIds[tabsId] = tabId }
}

/**
 * 只更新 UI 会话中的当前页签。
 *
 * @param tabsId Tabs 容器 ID。
 * @param tabId 目标活动 tab。
 */
fun TagloomEditorState.selectTab(
    tabsId: BlockId,
    tabId: BlockId,
) {
    val note = selectedDocument?.note ?: return
    require(tabId in activeTabs(note, tabsId)) { "目标 tab 不属于当前 Tabs" }
    activeTabIds[tabsId] = tabId
    clearSelection()
}

/**
 * 将一个 tab 设为 Note 显式默认页签，并同时切换当前会话。
 *
 * @param tabsId Tabs 容器 ID。
 * @param tabId 目标 tab。
 */
fun TagloomEditorState.setDefaultTab(
    tabsId: BlockId,
    tabId: BlockId,
) {
    val note = selectedDocument?.note ?: return
    require(tabId in activeTabs(note, tabsId)) { "默认 tab 不属于当前 Tabs" }
    updateSelectedNote { current -> current.copy(defaultTabId = tabId) }
    activeTabIds[tabsId] = tabId
}

/**
 * 更新 tab 标题 RichText，并保留标题已有行内样式。
 *
 * @param tabId 目标 tab Block ID。
 * @param title 用户输入纯文本。
 */
fun TagloomEditorState.renameTab(
    tabId: BlockId,
    title: String,
) {
    updateBlock(tabId) { block ->
        val content = block.content as? TabBlockContent ?: return@updateBlock block
        block.copy(
            content = content.copy(
                title = com.oruke.tagloom.core.model.RichTextEditor.replacePlainTextPreservingStyles(content.title, title),
            ),
        )
    }
}

/**
 * 在 Tabs 末尾创建一个带空正文的页签并立即选中。
 *
 * @param tabsId Tabs 容器 ID。
 * @return 新 tab ID；容器不存在时为空。
 */
fun TagloomEditorState.addTab(tabsId: BlockId): BlockId? {
    val note = selectedDocument?.note ?: return null
    if (note.blocks.none { block -> block.id == tabsId && !block.deleted && block.type == BlockTypes.TABS }) {
        return null
    }
    val tabs = activeTabs(note, tabsId)
    val tabId = BlockId(createEditorId("block"))
    val paragraphId = BlockId(createEditorId("block"))
    updateSelectedNote { current ->
        current.copy(
            blocks = current.blocks + listOf(
                Block(
                    id = tabId,
                    type = BlockTypes.TAB,
                    parentId = tabsId,
                    orderKey = EditorBlockFactory.orderKey(tabs.size),
                    content = TabBlockContent(RichTextDocumentReducer.fromPlainText("未命名")),
                ),
                Block(
                    id = paragraphId,
                    type = BlockTypes.PARAGRAPH,
                    parentId = tabId,
                    orderKey = EditorBlockFactory.orderKey(0),
                    content = RichTextBlockContent(RichText()),
                ),
            ),
        )
    }
    activeTabIds[tabsId] = tabId
    return tabId
}

/**
 * 复制一个页签及其完整正文子树，并把副本插入到来源页签之后。
 *
 * 新页签和全部后代都生成新稳定 ID，避免两个分支共享可写 Block 身份。
 *
 * @param tabsId Tabs 容器 ID。
 * @param tabId 来源页签 ID。
 * @return 新页签 ID；来源不存在时为空。
 */
fun TagloomEditorState.duplicateTab(
    tabsId: BlockId,
    tabId: BlockId,
): BlockId? {
    val note = selectedDocument?.note ?: return null
    val source = note.blocks.singleOrNull { block ->
        block.id == tabId && !block.deleted && block.parentId == tabsId && block.type == BlockTypes.TAB
    } ?: return null
    val descendants = BlockTreeReducer.orderedActive(note.blocks).filter { block ->
        block.id == tabId || hasAncestor(note.blocks, block, tabId)
    }
    val idMap = descendants.associate { block -> block.id to BlockId(createEditorId("block")) }
    val duplicateTabId = idMap.getValue(tabId)
    val clones = descendants.map { block ->
        val clonedContent = if (block.id == source.id) {
            val tabContent = block.content as TabBlockContent
            tabContent.copy(title = RichTextDocumentReducer.fromPlainText("${tabContent.title.plainText} 副本"))
        } else {
            block.content
        }
        block.copy(
            id = idMap.getValue(block.id),
            parentId = if (block.id == source.id) tabsId else block.parentId?.let(idMap::getValue),
            content = clonedContent,
            deleted = false,
        )
    }
    updateSelectedNote { current ->
        val withClones = current.blocks + clones
        val tabs = withClones
            .filter { block -> !block.deleted && block.parentId == tabsId && block.type == BlockTypes.TAB }
            .sortedWith(BLOCK_ORDER)
            .toMutableList()
        tabs.removeAll { block -> block.id == duplicateTabId }
        val sourceIndex = tabs.indexOfFirst { block -> block.id == tabId }
        tabs.add((sourceIndex + 1).coerceAtMost(tabs.size), clones.first())
        val orderById = tabs.mapIndexed { index, block -> block.id to EditorBlockFactory.orderKey(index) }.toMap()
        current.copy(
            blocks = withClones.map { block ->
                orderById[block.id]?.let { orderKey -> block.copy(orderKey = orderKey) } ?: block
            },
        )
    }
    activeTabIds[tabsId] = duplicateTabId
    clearSelection()
    return duplicateTabId
}

/**
 * 把页签向左或向右移动一个位置，同时保持页签及正文 Block ID 不变。
 *
 * @param tabsId Tabs 容器 ID。
 * @param tabId 目标页签 ID。
 * @param direction 左移使用 UP，右移使用 DOWN。
 */
fun TagloomEditorState.moveTab(
    tabsId: BlockId,
    tabId: BlockId,
    direction: BlockMoveDirection,
) {
    val note = selectedDocument?.note ?: return
    require(tabId in activeTabs(note, tabsId)) { "待移动 tab 不属于当前 Tabs" }
    updateSelectedNote { current -> current.copy(blocks = BlockTreeReducer.move(current.blocks, tabId, direction)) }
}

/**
 * 删除一个 tab，并按用户选择解包内容或级联生成 tombstone。
 *
 * 默认解包会把 tab 的直接子 Block 提升到 Tabs 所在层级并重新排序；只有 cascade=true 才删除内容。
 *
 * @param tabsId Tabs 容器 ID。
 * @param tabId 目标 tab。
 * @param cascade 是否连同内容删除。
 */
fun TagloomEditorState.deleteTab(
    tabsId: BlockId,
    tabId: BlockId,
    cascade: Boolean = false,
) {
    val note = selectedDocument?.note ?: return
    require(tabId in activeTabs(note, tabsId)) { "待删除 tab 不属于当前 Tabs" }
    updateSelectedNote { current ->
        val tabsBlock = current.blocks.single { block -> block.id == tabsId && !block.deleted }
        val afterTabDelete = if (cascade) {
            BlockTreeReducer.tombstone(current.blocks, tabId)
        } else {
            unpackTab(current.blocks, tabsBlock, tabId)
        }
        val remainingTabs = afterTabDelete.filter { block ->
            !block.deleted && block.parentId == tabsId && block.type == BlockTypes.TAB
        }
        val finalBlocks = if (remainingTabs.isEmpty()) {
            val withoutContainer = afterTabDelete.map { block ->
                if (block.id == tabsId) block.copy(deleted = true) else block
            }
            if (cascade) {
                withoutContainer + Block(
                    id = BlockId(createEditorId("block")),
                    type = BlockTypes.PARAGRAPH,
                    parentId = tabsBlock.parentId,
                    orderKey = tabsBlock.orderKey,
                    content = RichTextBlockContent(RichText()),
                )
            } else {
                withoutContainer
            }
        } else {
            afterTabDelete
        }
        current.copy(
            blocks = finalBlocks,
            defaultTabId = if (current.defaultTabId == tabId) remainingTabs.firstOrNull()?.id else current.defaultTabId,
        )
    }
    activeTabIds.remove(tabsId)
    activeTabId(tabsId)
}

/**
 * 将同一 Note 内一个普通 Block 移入目标 tab，并保持稳定 Block ID。
 *
 * @param blockId 待移动 Block。
 * @param tabId 目标 tab。
 */
fun TagloomEditorState.moveBlockToTab(
    blockId: BlockId,
    tabId: BlockId,
) {
    val note = selectedDocument?.note ?: return
    val target = note.blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return
    val tab = note.blocks.singleOrNull { block -> block.id == tabId && !block.deleted && block.type == BlockTypes.TAB }
        ?: throw IllegalArgumentException("目标 tab 不存在")
    require(target.type != BlockTypes.TAB && target.type != BlockTypes.TABS) { "不能把 Tabs 结构本身移动到 tab" }
    require(!isAncestor(note.blocks, target.id, tab.id)) { "不能把 Block 移入自己的后代" }
    val destinationCount = note.blocks.count { block -> !block.deleted && block.parentId == tab.id }
    updateSelectedNote { current ->
        current.copy(
            blocks = current.blocks.map { block ->
                if (block.id == blockId) {
                    block.copy(parentId = tab.id, orderKey = EditorBlockFactory.orderKey(destinationCount))
                } else {
                    block
                }
            },
        )
    }
}

/**
 * 返回编辑器当前会话真正可见的 Block 列表。
 *
 * tab 标题由 Tabs 容器专用 UI 渲染；正文只遍历活动 tab 的后代，非活动页签内容仍保留在 Note 快照中。
 *
 * @param note 当前 Note。
 * @return 父节点在前的可见 Block。
 */
fun TagloomEditorState.visibleEditorBlocks(note: Note): List<Block> {
    val active = note.blocks.filterNot(Block::deleted)
    val children = active.groupBy(Block::parentId)
    val result = mutableListOf<Block>()

    /**
     * 递归加入普通 Block 或 Tabs 当前活动页签内容。
     *
     * @param block 当前 Block。
     */
    fun visit(block: Block) {
        if (block.type == BlockTypes.TAB) {
            return
        }
        result += block
        if (block.type == BlockTypes.TABS) {
            activeTabId(block.id)?.let { tabId ->
                children[tabId].orEmpty().sortedWith(BLOCK_ORDER).forEach(::visit)
            }
        } else {
            children[block.id].orEmpty().sortedWith(BLOCK_ORDER).forEach(::visit)
        }
    }

    children[null].orEmpty().sortedWith(BLOCK_ORDER).forEach(::visit)
    return result
}

/**
 * 返回去除 Tabs/Tab 结构层后的正文展示深度。
 *
 * @param block 当前可见 Block。
 * @param note 当前 Note。
 * @return 用于正文缩进的非负深度。
 */
fun TagloomEditorState.visibleBlockDepth(
    block: Block,
    note: Note,
): Int {
    val byId = note.blocks.associateBy(Block::id)
    var depth = blockDepth(block, note)
    var parentId = block.parentId
    while (parentId != null) {
        val parent = byId[parentId] ?: break
        if (parent.type == BlockTypes.TAB) {
            depth = (depth - 2).coerceAtLeast(0)
        }
        parentId = parent.parentId
    }
    return depth
}

/**
 * 返回一个 Tabs 容器的活动 tab ID 列表。
 *
 * @param note 当前 Note。
 * @param tabsId Tabs 容器 ID。
 * @return 按 orderKey/ID 稳定排序的 tab ID。
 */
private fun activeTabs(
    note: Note,
    tabsId: BlockId,
): List<BlockId> = note.blocks
    .filter { block -> !block.deleted && block.parentId == tabsId && block.type == BlockTypes.TAB }
    .sortedWith(BLOCK_ORDER)
    .map(Block::id)

/**
 * 解包 tab 内容并重新生成目标层级排序键。
 *
 * @param blocks 当前全部 Block。
 * @param tabsBlock Tabs 容器。
 * @param tabId 待删除 tab。
 * @return 保留内容、tombstone tab 的 Block 集合。
 */
private fun unpackTab(
    blocks: List<Block>,
    tabsBlock: Block,
    tabId: BlockId,
): List<Block> {
    val promoted = blocks.filter { block -> !block.deleted && block.parentId == tabId }.sortedWith(BLOCK_ORDER)
    val destination = blocks
        .filter { block -> !block.deleted && block.parentId == tabsBlock.parentId && block.id !in promoted.map(Block::id) }
        .sortedWith(BLOCK_ORDER)
        .toMutableList()
    val tabsIndex = destination.indexOfFirst { block -> block.id == tabsBlock.id }.coerceAtLeast(destination.lastIndex)
    destination.addAll((tabsIndex + 1).coerceAtMost(destination.size), promoted)
    val orderById = destination.mapIndexed { index, block -> block.id to EditorBlockFactory.orderKey(index) }.toMap()
    return blocks.map { block ->
        when {
            block.id == tabId -> block.copy(deleted = true)
            block.id in promoted.map(Block::id) -> block.copy(
                parentId = tabsBlock.parentId,
                orderKey = orderById.getValue(block.id),
            )
            block.id in orderById -> block.copy(orderKey = orderById.getValue(block.id))
            else -> block
        }
    }
}

/**
 * 判断 candidate 是否位于 ancestor 子树内。
 *
 * @param blocks 当前全部 Block。
 * @param ancestorId 可能的祖先。
 * @param candidateId 候选后代。
 * @return 沿父边可到达祖先时为 true。
 */
private fun isAncestor(
    blocks: List<Block>,
    ancestorId: BlockId,
    candidateId: BlockId,
): Boolean {
    val byId = blocks.associateBy(Block::id)
    var current = byId[candidateId]?.parentId
    val visited = mutableSetOf<BlockId>()
    while (current != null && visited.add(current)) {
        if (current == ancestorId) {
            return true
        }
        current = byId[current]?.parentId
    }
    return false
}

/**
 * 判断一个 Block 是否位于指定祖先的活动子树中。
 *
 * @param blocks 当前全部 Block。
 * @param block 待判断 Block。
 * @param ancestorId 目标祖先 ID。
 * @return 沿父边可到达目标祖先时为 true。
 */
private fun hasAncestor(
    blocks: List<Block>,
    block: Block,
    ancestorId: BlockId,
): Boolean {
    val byId = blocks.associateBy(Block::id)
    var current = block.parentId
    val visited = mutableSetOf<BlockId>()
    while (current != null && visited.add(current)) {
        if (current == ancestorId) {
            return true
        }
        current = byId[current]?.parentId
    }
    return false
}

/** Block 稳定排序器。 */
private val BLOCK_ORDER: Comparator<Block> = compareBy(Block::orderKey, { block -> block.id.value })
