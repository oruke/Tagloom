package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableEditor
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableStructureEditor
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent

/**
 * 保存一次块级编辑后的完整集合与下一光标目标。
 */
internal data class BlockDocumentEdit(
    /** 包含 tombstone 的新 Block 集合。 */
    val blocks: List<Block>,
    /** 编辑完成后的焦点目标。 */
    val cursor: EditorCursorTarget,
)

/**
 * 实现连续文档所需的 Block 插入、拆分、合并、转换和光标导航规则。
 */
internal object BlockDocumentReducer {
    /**
     * 在指定 Block 后插入一个同级空 Block。
     *
     * @param blocks 当前全部 Block。
     * @param anchorId 插入锚点。
     * @param type 新 Block 编辑器类型。
     * @param idFactory 稳定 ID 生成函数。
     * @return 插入结果；锚点不存在时为空。
     */
    fun insertAfter(
        blocks: List<Block>,
        anchorId: BlockId,
        type: EditorBlockType,
        idFactory: (String) -> String,
    ): BlockDocumentEdit? {
        val anchor = blocks.singleOrNull { block -> block.id == anchorId && !block.deleted } ?: return null
        val inserted = createBlock(type, anchor.parentId, idFactory)
        return BlockDocumentEdit(
            blocks = insertSiblingAfter(blocks, anchor, inserted),
            cursor = EditorCursorTarget(inserted.id, 0),
        )
    }

    /**
     * 在光标处拆分文本 Block；选区存在时先删除选中文字。
     *
     * 空的非正文 Block 会先退回正文，避免连续产生空列表或空标题。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 待拆分 Block。
     * @param selectionStart 选区起点。
     * @param selectionEnd 选区终点。
     * @param idFactory 稳定 ID 生成函数。
     * @return 拆分或类型回退结果；不支持文本编辑时为空。
     */
    fun split(
        blocks: List<Block>,
        blockId: BlockId,
        selectionStart: Int,
        selectionEnd: Int,
        idFactory: (String) -> String,
    ): BlockDocumentEdit? {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return null
        val original = target.editableRichText() ?: return null
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, original.plainText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, original.plainText.length)
        val withoutSelection = RichTextDocumentReducer.removeRange(original, start, end)

        if (withoutSelection.plainText.isEmpty() && editorTypeOf(target) != EditorBlockType.TEXT) {
            val paragraph = convert(target.withEditableRichText(withoutSelection), EditorBlockType.TEXT)
            return BlockDocumentEdit(
                blocks = replace(blocks, paragraph),
                cursor = EditorCursorTarget(blockId, 0),
            )
        }

        val before = RichTextDocumentReducer.slice(withoutSelection, 0, start)
        val after = RichTextDocumentReducer.slice(withoutSelection, start, withoutSelection.plainText.length)
        val updatedTarget = target.withEditableRichText(before)
        val continuationType = continuationType(target)
        val continuation = createBlock(continuationType, target.parentId, idFactory)
            .withEditableRichText(after)
        val withUpdatedTarget = replace(blocks, updatedTarget)
        return BlockDocumentEdit(
            blocks = insertSiblingAfter(withUpdatedTarget, updatedTarget, continuation),
            cursor = EditorCursorTarget(continuation.id, 0),
        )
    }

    /**
     * 把当前 Block 合并到前一个可编辑同级 Block。
     *
     * 嵌套 Block 在行首退格时先提升一级；这是块编辑器中比跨层级吞并更可预测的行为。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 当前光标所在 Block。
     * @return 合并、删除分隔线或提升层级后的结果；没有可执行动作时为空。
     */
    fun mergeWithPrevious(
        blocks: List<Block>,
        blockId: BlockId,
    ): BlockDocumentEdit? {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return null
        if (target.parentId != null) {
            val outdented = BlockTreeReducer.outdent(blocks, blockId)
            return BlockDocumentEdit(outdented, EditorCursorTarget(blockId, 0))
        }
        val ordered = BlockTreeReducer.orderedActive(blocks)
        val index = ordered.indexOfFirst { block -> block.id == blockId }
        val previous = ordered.getOrNull(index - 1) ?: return null
        if (previous.parentId != target.parentId) {
            return null
        }
        if (previous.content == DividerBlockContent) {
            val removedDivider = replace(blocks, previous.copy(deleted = true))
            return BlockDocumentEdit(reindexSiblings(removedDivider, target.parentId), EditorCursorTarget(blockId, 0))
        }

        val previousText = editableText(previous) ?: return null
        val targetText = editableText(target) ?: return null
        val merged = mergeContents(previous, target, previousText, targetText)
        val updated = blocks.map { block ->
            when {
                block.id == previous.id -> merged
                block.id == target.id -> block.copy(deleted = true)
                block.parentId == target.id -> block.copy(parentId = previous.id)
                else -> block
            }
        }
        return BlockDocumentEdit(
            blocks = reindexSiblings(updated, target.parentId),
            cursor = EditorCursorTarget(previous.id, previousText.length),
        )
    }

    /**
     * 把一个可编辑 Block 转换为目标类型并保留其可表示文本。
     *
     * 分隔线没有光标位置，因此转换后自动补一个正文 Block 并把焦点移过去。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @param type 新类型。
     * @param idFactory 稳定 ID 生成函数。
     * @param names 调用方按当前语言解析的新内容命名规则。
     * @return 转换结果；未知或附件 Block 返回空。
     */
    fun turnInto(
        blocks: List<Block>,
        blockId: BlockId,
        type: EditorBlockType,
        idFactory: (String) -> String,
        names: EditorContentNames = EditorContentNames(),
    ): BlockDocumentEdit? {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return null
        if (editableText(target) == null && target.content != DividerBlockContent) {
            return null
        }
        if (type == EditorBlockType.BASE_TABLE) {
            val content = BaseTableEditor.createDefault(
                rowIdFactory = { BaseTableRowId(idFactory("table-row")) },
                columnIdFactory = { BaseTableColumnId(idFactory("table-column")) },
                columnName = names::columnName,
            )
            val converted = target.copy(type = BlockTypes.BASE_TABLE, content = content)
            return BlockDocumentEdit(replace(blocks, converted), EditorCursorTarget(converted.id, 0))
        }
        if (type == EditorBlockType.TABS) {
            return createTabs(blocks, target, idFactory, names)
        }
        val converted = convert(target, type)
        val replaced = replace(blocks, converted)
        if (type != EditorBlockType.DIVIDER) {
            val cursor = editableText(converted)?.length ?: 0
            return BlockDocumentEdit(replaced, EditorCursorTarget(blockId, cursor))
        }
        val continuation = createBlock(EditorBlockType.TEXT, target.parentId, idFactory)
        return BlockDocumentEdit(
            blocks = insertSiblingAfter(replaced, converted, continuation),
            cursor = EditorCursorTarget(continuation.id, 0),
        )
    }

    /**
     * 把当前可编辑 Block 转换为指定初始尺寸的正文表格。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 目标 Block。
     * @param rowCount 初始活动行数。
     * @param columnCount 初始活动列数。
     * @param idFactory 稳定 ID 生成函数。
     * @param names 调用方按当前语言解析的新内容命名规则。
     * @return 转换结果；目标不存在或不可转换时为空。
     */
    fun turnIntoBaseTable(
        blocks: List<Block>,
        blockId: BlockId,
        rowCount: Int,
        columnCount: Int,
        idFactory: (String) -> String,
        names: EditorContentNames = EditorContentNames(),
    ): BlockDocumentEdit? {
        val target = blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return null
        if (editableText(target) == null && target.content != DividerBlockContent) {
            return null
        }
        val content = BaseTableStructureEditor.create(
            rowCount = rowCount,
            columnCount = columnCount,
            rowIdFactory = { BaseTableRowId(idFactory("table-row")) },
            columnIdFactory = { BaseTableColumnId(idFactory("table-column")) },
            columnName = names::columnName,
        )
        val converted = target.copy(type = BlockTypes.BASE_TABLE, content = content)
        return BlockDocumentEdit(replace(blocks, converted), EditorCursorTarget(converted.id, 0))
    }

    /**
     * 将当前 Block 包装进新 Tabs 的首个页签，并创建第二个空页签。
     *
     * 原 Block 保留稳定 ID 和可表示正文，只更新 parentId/orderKey；Tabs 与 tab 使用新的稳定 ID。
     *
     * @param blocks 当前全部 Block。
     * @param target 被包装的当前 Block。
     * @param idFactory 稳定 ID 工厂。
     * @param names 调用方按当前语言解析的新内容命名规则。
     * @return 结构化 Tabs 编辑结果。
     */
    private fun createTabs(
        blocks: List<Block>,
        target: Block,
        idFactory: (String) -> String,
        names: EditorContentNames = EditorContentNames(),
    ): BlockDocumentEdit {
        val tabsId = BlockId(idFactory("block"))
        val firstTabId = BlockId(idFactory("block"))
        val secondTabId = BlockId(idFactory("block"))
        val secondParagraphId = BlockId(idFactory("block"))
        val tabs = Block(
            id = tabsId,
            type = BlockTypes.TABS,
            parentId = target.parentId,
            orderKey = target.orderKey,
            content = TabsBlockContent,
        )
        val firstTab = Block(
            id = firstTabId,
            type = BlockTypes.TAB,
            parentId = tabsId,
            orderKey = EditorBlockFactory.orderKey(0),
            content = TabBlockContent(RichTextDocumentReducer.fromPlainText(names.tabName(1))),
        )
        val secondTab = Block(
            id = secondTabId,
            type = BlockTypes.TAB,
            parentId = tabsId,
            orderKey = EditorBlockFactory.orderKey(1),
            content = TabBlockContent(RichTextDocumentReducer.fromPlainText(names.tabName(2))),
        )
        val firstContent = target.copy(parentId = firstTabId, orderKey = EditorBlockFactory.orderKey(0))
        val secondContent = Block(
            id = secondParagraphId,
            type = BlockTypes.PARAGRAPH,
            parentId = secondTabId,
            orderKey = EditorBlockFactory.orderKey(0),
            content = RichTextBlockContent(RichText()),
        )
        return BlockDocumentEdit(
            blocks = blocks.filterNot { block -> block.id == target.id } +
                listOf(tabs, firstTab, firstContent, secondTab, secondContent),
            cursor = EditorCursorTarget(firstContent.id, editableText(firstContent).orEmpty().length),
        )
    }

    /**
     * 返回相邻可聚焦 Block 的首尾光标目标。
     *
     * @param blocks 当前全部 Block。
     * @param blockId 当前 Block。
     * @param direction 查找方向。
     * @return 可编辑 RichText 或代码 Block 的光标目标。
     */
    fun adjacentCursor(
        blocks: List<Block>,
        blockId: BlockId,
        direction: BlockMoveDirection,
    ): EditorCursorTarget? {
        val editable = BlockTreeReducer.orderedActive(blocks).filter { block -> editableText(block) != null }
        val index = editable.indexOfFirst { block -> block.id == blockId }
        val adjacent = when (direction) {
            BlockMoveDirection.UP -> editable.getOrNull(index - 1)
            BlockMoveDirection.DOWN -> editable.getOrNull(index + 1)
        } ?: return null
        val offset = if (direction == BlockMoveDirection.UP) editableText(adjacent).orEmpty().length else 0
        return EditorCursorTarget(adjacent.id, offset)
    }

    /**
     * 根据当前 Block 类型决定 Enter 后续块类型。
     *
     * @param block 当前 Block。
     * @return 列表与 Todo 延续原类型；标题和引用回到正文。
     */
    private fun continuationType(block: Block): EditorBlockType = when (block.type) {
        BlockTypes.BULLETED_LIST -> EditorBlockType.BULLETED_LIST
        BlockTypes.NUMBERED_LIST -> EditorBlockType.NUMBERED_LIST
        BlockTypes.TODO -> EditorBlockType.TODO
        else -> EditorBlockType.TEXT
    }

    /**
     * 把协议 Block 映射为最接近的编辑器类型。
     *
     * @param block 当前 Block。
     * @return 已知编辑器类型；未知文本类型回退正文。
     */
    private fun editorTypeOf(block: Block): EditorBlockType = when (block.type) {
        BlockTypes.HEADING -> when ((block.content as? HeadingBlockContent)?.level) {
            1 -> EditorBlockType.HEADING_ONE
            3 -> EditorBlockType.HEADING_THREE
            else -> EditorBlockType.HEADING_TWO
        }
        BlockTypes.BULLETED_LIST -> EditorBlockType.BULLETED_LIST
        BlockTypes.NUMBERED_LIST -> EditorBlockType.NUMBERED_LIST
        BlockTypes.TODO -> EditorBlockType.TODO
        BlockTypes.QUOTE -> EditorBlockType.QUOTE
        BlockTypes.CODE -> EditorBlockType.CODE
        BlockTypes.DIVIDER -> EditorBlockType.DIVIDER
        else -> EditorBlockType.TEXT
    }

    /**
     * 构造目标类型的空 Block。
     *
     * @param type 编辑器类型。
     * @param parentId 父 Block；顶层为空。
     * @param idFactory 稳定 ID 生成函数。
     * @return 带初始内容的新 Block。
     */
    private fun createBlock(
        type: EditorBlockType,
        parentId: BlockId?,
        idFactory: (String) -> String,
    ): Block = convert(
        block = Block(
            id = BlockId(idFactory("block")),
            type = BlockTypes.PARAGRAPH,
            parentId = parentId,
            orderKey = EditorBlockFactory.orderKey(0),
            content = RichTextBlockContent(RichText()),
        ),
        type = type,
    )

    /**
     * 转换 Block 内容并保留可表示文本。
     *
     * @param block 原 Block。
     * @param type 目标类型。
     * @return 保持 ID、父边、顺序和属性的新 Block。
     */
    private fun convert(
        block: Block,
        type: EditorBlockType,
    ): Block {
        val text = editableText(block).orEmpty()
        val richText = block.editableRichText() ?: RichTextDocumentReducer.fromPlainText(text)
        val content = when (type) {
            EditorBlockType.TEXT,
            EditorBlockType.BULLETED_LIST,
            EditorBlockType.NUMBERED_LIST,
            EditorBlockType.QUOTE,
            -> RichTextBlockContent(richText)
            EditorBlockType.HEADING_ONE,
            EditorBlockType.HEADING_TWO,
            EditorBlockType.HEADING_THREE,
            -> HeadingBlockContent(requireNotNull(type.headingLevel), richText)
            EditorBlockType.TODO -> TodoBlockContent(
                checked = (block.content as? TodoBlockContent)?.checked ?: false,
                richText = richText,
            )
            EditorBlockType.CODE -> CodeBlockContent(language = null, text = text)
            EditorBlockType.DIVIDER -> DividerBlockContent
            EditorBlockType.BASE_TABLE,
            EditorBlockType.DATABASE,
            EditorBlockType.TABS,
            -> error("结构化 Block 必须走专用创建流程")
        }
        return block.copy(type = type.protocolType, content = content)
    }

    /**
     * 提取编辑器可合并的 RichText 或代码纯文本。
     *
     * @param block 目标 Block。
     * @return 文本；分隔线和未知内容为空。
     */
    private fun editableText(block: Block): String? = when (val content = block.content) {
        is CodeBlockContent -> content.text
        else -> block.editableRichText()?.plainText
    }

    /**
     * 把当前内容追加到前一 Block，并保持前一 Block 的类型语义。
     *
     * @param previous 前一 Block。
     * @param target 当前 Block。
     * @param previousText 前一 Block 纯文本。
     * @param targetText 当前 Block 纯文本。
     * @return 合并后的前一 Block。
     */
    private fun mergeContents(
        previous: Block,
        target: Block,
        previousText: String,
        targetText: String,
    ): Block = when (val content = previous.content) {
        is CodeBlockContent -> previous.copy(content = content.copy(text = previousText + targetText))
        else -> {
            val left = requireNotNull(previous.editableRichText())
            val right = target.editableRichText() ?: RichTextDocumentReducer.fromPlainText(targetText)
            previous.withEditableRichText(
                RichTextDocumentReducer.concat(left, right),
            )
        }
    }

    /**
     * 替换同 ID Block。
     *
     * @param blocks 当前集合。
     * @param replacement 替换值。
     * @return 保持原集合位置的新列表。
     */
    private fun replace(
        blocks: List<Block>,
        replacement: Block,
    ): List<Block> = blocks.map { block -> if (block.id == replacement.id) replacement else block }

    /**
     * 插入同级 Block，并重新生成该父节点下的连续顺序键。
     *
     * @param blocks 当前集合。
     * @param anchor 插入锚点。
     * @param inserted 新 Block。
     * @return 包含新 Block 的稳定集合。
     */
    private fun insertSiblingAfter(
        blocks: List<Block>,
        anchor: Block,
        inserted: Block,
    ): List<Block> {
        val siblings = blocks
            .filter { block -> !block.deleted && block.parentId == anchor.parentId }
            .sortedWith(compareBy(Block::orderKey, { block -> block.id.value }))
            .toMutableList()
        val anchorIndex = siblings.indexOfFirst { block -> block.id == anchor.id }
        siblings.add((anchorIndex + 1).coerceAtLeast(0), inserted)
        val orderById = siblings.mapIndexed { index, block -> block.id to EditorBlockFactory.orderKey(index) }.toMap()
        return (blocks + inserted).map { block ->
            orderById[block.id]?.let { orderKey -> block.copy(orderKey = orderKey) } ?: block
        }
    }

    /**
     * 在删除或合并后重建一个父节点下的活动同级顺序。
     *
     * @param blocks 当前集合。
     * @param parentId 目标父节点。
     * @return 顺序键连续且其他 Block 不变的集合。
     */
    private fun reindexSiblings(
        blocks: List<Block>,
        parentId: BlockId?,
    ): List<Block> {
        val orderById = blocks
            .filter { block -> !block.deleted && block.parentId == parentId }
            .sortedWith(compareBy(Block::orderKey, { block -> block.id.value }))
            .mapIndexed { index, block -> block.id to EditorBlockFactory.orderKey(index) }
            .toMap()
        return blocks.map { block -> orderById[block.id]?.let { key -> block.copy(orderKey = key) } ?: block }
    }
}
