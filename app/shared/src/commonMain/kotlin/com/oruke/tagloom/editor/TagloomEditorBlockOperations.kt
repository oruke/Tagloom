package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent

/**
 * 在指定 Block 后插入一个同级空 Block，并返回下一光标目标。
 *
 * @param blockId 插入锚点。
 * @param type 新 Block 类型。
 * @return 插入成功后的光标目标；锚点不存在时为空。
 */
fun TagloomEditorState.insertBlockAfter(
    blockId: BlockId,
    type: EditorBlockType = EditorBlockType.TEXT,
): EditorCursorTarget? = applyDocumentEdit { blocks ->
    BlockDocumentReducer.insertAfter(blocks, blockId, type, ::createEditorId)
}

/**
 * 在光标或选区处拆分当前文本 Block。
 *
 * @param blockId 当前 Block。
 * @param selectionStart 选区起点。
 * @param selectionEnd 选区终点。
 * @return 新 Block 或类型回退后的光标目标。
 */
fun TagloomEditorState.splitBlock(
    blockId: BlockId,
    selectionStart: Int,
    selectionEnd: Int,
): EditorCursorTarget? = applyDocumentEdit { blocks ->
    BlockDocumentReducer.split(blocks, blockId, selectionStart, selectionEnd, ::createEditorId)
}

/**
 * 在行首退格时合并前一 Block，嵌套内容则优先提升一级。
 *
 * @param blockId 当前 Block。
 * @return 合并或提升后的光标目标。
 */
fun TagloomEditorState.mergeBlockWithPrevious(blockId: BlockId): EditorCursorTarget? = applyDocumentEdit { blocks ->
    BlockDocumentReducer.mergeWithPrevious(blocks, blockId)
}

/**
 * 把当前 Block 转换为编辑器支持的另一类型。
 *
 * @param blockId 目标 Block。
 * @param type 新类型。
 * @return 转换后的光标目标。
 */
fun TagloomEditorState.turnBlockInto(
    blockId: BlockId,
    type: EditorBlockType,
): EditorCursorTarget? = applyDocumentEdit { blocks ->
    BlockDocumentReducer.turnInto(blocks, blockId, type, ::createEditorId, contentNames)
}

/**
 * 把当前 Block 转换为用户指定初始尺寸的正文表格。
 *
 * @param blockId 目标 Block。
 * @param rowCount 初始活动行数。
 * @param columnCount 初始活动列数。
 * @return 转换后的光标目标。
 */
fun TagloomEditorState.turnBlockIntoBaseTable(
    blockId: BlockId,
    rowCount: Int,
    columnCount: Int,
): EditorCursorTarget? = applyDocumentEdit { blocks ->
    BlockDocumentReducer.turnIntoBaseTable(blocks, blockId, rowCount, columnCount, ::createEditorId, contentNames)
}

/**
 * 识别行首 Markdown 前缀并转换整个空白命令 Block。
 *
 * @param blockId 当前 Block。
 * @param text 输入后的完整纯文本。
 * @return 成功识别时的光标目标，否则为空。
 */
fun TagloomEditorState.applyMarkdownShortcut(
    blockId: BlockId,
    text: String,
): EditorCursorTarget? {
    val type = MARKDOWN_BLOCK_SHORTCUTS[text] ?: return null
    updateBlockText(blockId, "")
    return turnBlockInto(blockId, type)
}

/**
 * 返回键盘跨 Block 导航的相邻光标目标。
 *
 * @param blockId 当前 Block。
 * @param direction 向前或向后。
 * @return 相邻可编辑 Block 的首尾位置。
 */
fun TagloomEditorState.adjacentCursor(
    blockId: BlockId,
    direction: BlockMoveDirection,
): EditorCursorTarget? = selectedDocument?.note?.blocks?.let { blocks ->
    BlockDocumentReducer.adjacentCursor(blocks, blockId, direction)
}

/**
 * 计算一个有序列表项在连续同级列表中的展示序号。
 *
 * @param blockId 有序列表 Block。
 * @return 从 1 开始的序号；Block 不存在或类型不符时返回 1。
 */
fun TagloomEditorState.numberedListOrdinal(blockId: BlockId): Int {
    val note = selectedDocument?.note ?: return 1
    val target = note.blocks.singleOrNull { block -> block.id == blockId && !block.deleted } ?: return 1
    val siblings = note.blocks
        .filter { block -> !block.deleted && block.parentId == target.parentId }
        .sortedWith(compareBy(Block::orderKey, { block -> block.id.value }))
    val targetIndex = siblings.indexOfFirst { block -> block.id == blockId }
    if (targetIndex < 0 || target.type != BlockTypes.NUMBERED_LIST) {
        return 1
    }
    var ordinal = 1
    var index = targetIndex - 1
    while (index >= 0 && siblings[index].type == BlockTypes.NUMBERED_LIST) {
        ordinal += 1
        index -= 1
    }
    // 剪贴板中从列表中段复制的首项可保留原编号；连续后继依然按顺序递增。
    val start = (siblings[index + 1].attributes[NUMBERED_LIST_START_ATTRIBUTE] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1
    return start + ordinal - 1
}

/**
 * 删除一个 Block 或其子树，并把光标移动到仍然存在的最近 Block。
 *
 * 页面只剩一个 Block 时不会制造不可写空页，而是把该 Block 重置为空正文。
 *
 * @param blockId 待删除 Block。
 * @return 删除后的光标目标。
 */
fun TagloomEditorState.deleteBlockAndFocus(blockId: BlockId): EditorCursorTarget? {
    val note = selectedDocument?.note ?: return null
    val orderedBefore = BlockTreeReducer.orderedActive(note.blocks)
    val targetIndex = orderedBefore.indexOfFirst { block -> block.id == blockId }
    if (targetIndex < 0) {
        return null
    }
    if (orderedBefore.size == 1) {
        updateBlockText(blockId, "")
        return turnBlockInto(blockId, EditorBlockType.TEXT)
    }

    var cursor: EditorCursorTarget? = null
    updateSelectedNote { current ->
        val deleted = BlockTreeReducer.tombstone(current.blocks, blockId)
        val remaining = BlockTreeReducer.orderedActive(deleted).filter { block -> blockEditorTextLength(block) != null }
        val remainingIds = remaining.map(Block::id).toSet()
        val previous = orderedBefore
            .take(targetIndex)
            .asReversed()
            .firstOrNull { block -> block.id in remainingIds }
        val next = orderedBefore
            .drop(targetIndex + 1)
            .firstOrNull { block -> block.id in remainingIds }
        cursor = previous?.let { block -> EditorCursorTarget(block.id, blockEditorTextLength(block) ?: 0) }
            ?: next?.let { block -> EditorCursorTarget(block.id, 0) }
        current.copy(blocks = deleted)
    }
    cursor?.let { target -> updateSelection(target.blockId, target.offset, target.offset) }
    return cursor
}

/**
 * 应用一个纯 Block 文档变换，并同步下一光标选区。
 *
 * @param transform 接收当前 Block 集合的纯变换函数。
 * @return 变换结果中的光标目标；没有动作时为空。
 */
private fun TagloomEditorState.applyDocumentEdit(
    transform: (List<Block>) -> BlockDocumentEdit?,
): EditorCursorTarget? {
    var cursor: EditorCursorTarget? = null
    updateSelectedNote { note ->
        val edit = transform(note.blocks) ?: return@updateSelectedNote note
        cursor = edit.cursor
        note.copy(blocks = edit.blocks)
    }
    cursor?.let { target -> updateSelection(target.blockId, target.offset, target.offset) }
    return cursor
}

/**
 * 返回 Block 可放置编辑光标的文本长度。
 *
 * @param block 当前 Block。
 * @return RichText 或代码长度；不可编辑类型为空。
 */
private fun blockEditorTextLength(block: Block): Int? = when (val content = block.content) {
    is CodeBlockContent -> content.text.length
    else -> block.editableRichText()?.plainText?.length
}

/** 行首输入完成后立即生效的 Notion 风格 Markdown Block 快捷方式。 */
private val MARKDOWN_BLOCK_SHORTCUTS: Map<String, EditorBlockType> = mapOf(
    "# " to EditorBlockType.HEADING_ONE,
    "## " to EditorBlockType.HEADING_TWO,
    "### " to EditorBlockType.HEADING_THREE,
    "- " to EditorBlockType.BULLETED_LIST,
    "* " to EditorBlockType.BULLETED_LIST,
    "+ " to EditorBlockType.BULLETED_LIST,
    "1. " to EditorBlockType.NUMBERED_LIST,
    "[] " to EditorBlockType.TODO,
    "[ ] " to EditorBlockType.TODO,
    "> " to EditorBlockType.QUOTE,
    "\" " to EditorBlockType.QUOTE,
    "```" to EditorBlockType.CODE,
    "---" to EditorBlockType.DIVIDER,
)
