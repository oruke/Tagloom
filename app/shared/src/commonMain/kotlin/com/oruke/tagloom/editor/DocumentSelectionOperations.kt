package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.*

/** 一个块内的半开字符区间，偏移使用原文 UTF-16 坐标。 */
internal data class SelectedBlockRange(
    /** 当前范围对应的活动块。 */
    val block: Block,
    /** 包含的第一个字符偏移。 */
    val start: Int,
    /** 不包含的终点字符偏移。 */
    val end: Int,
)

/**
 * 取得参与正文连续选择的原文，不把代码的显示高亮写入数据。
 * @return 富文本或代码原文；结构化容器为空。
 */
internal fun Block.selectionText(): String? = editableRichText()?.plainText ?: (content as? CodeBlockContent)?.text

/**
 * 找出与指定块连续的可编辑正文；结构化块及页签边界终止范围。
 * @param blockId 选区锚点。
 * @return 包含锚点的正文流，保持展示顺序。
 */
internal fun TagloomEditorState.selectionFlow(blockId: BlockId): List<Block> {
    val note = selectedDocument?.note ?: return emptyList()
    val visible = visibleEditorBlocks(note)
    val index = visible.indexOfFirst { it.id == blockId }
    if (index < 0 || visible[index].selectionText() == null) return emptyList()
    // 附件说明可单独编辑，但选中文字不能隐式删除附件本体。
    if (visible[index].content is AttachmentBlockContent) return listOf(visible[index])
    val tab = nearestTabAncestorId(visible[index], note)
    val before = visible.take(index).takeLastWhile {
        it.selectionText() != null && it.content !is AttachmentBlockContent && nearestTabAncestorId(
            it,
            note
        ) == tab
    }
    val after = visible.drop(index + 1).takeWhile {
        it.selectionText() != null && it.content !is AttachmentBlockContent && nearestTabAncestorId(
            it,
            note
        ) == tab
    }
    return before + visible[index] + after
}

/**
 * 从有方向的锚点和终点生成规范化选区，不越过结构化容器或隐藏页签。
 * @param anchor 开始选择时的光标。
 * @param focus 当前拖动或键盘延伸到的光标。
 */
internal fun TagloomEditorState.selectDocumentRange(anchor: EditorCursorTarget, focus: EditorCursorTarget) {
    val flow = selectionFlow(anchor.blockId)
    val a = flow.indexOfFirst { it.id == anchor.blockId }
    val b = flow.indexOfFirst { it.id == focus.blockId }
    if (a < 0 || b < 0) return
    val first = if (a < b || (a == b && anchor.offset <= focus.offset)) anchor else focus
    val last = if (first === anchor) focus else anchor
    val ids = flow.subList(minOf(a, b), maxOf(a, b) + 1).map(Block::id)
    selection = EditorSelection(
        first.blockId,
        first.offset.coerceIn(0, flow[minOf(a, b)].selectionText().orEmpty().length),
        last.offset.coerceIn(0, flow[maxOf(a, b)].selectionText().orEmpty().length),
        ids.takeIf { it.size > 1 }.orEmpty(),
    )
}

/**
 * 将会话选区解析为每块的实际范围，过滤失效或已删除块。
 * @return 包括空边界块在内的有序范围。
 */
internal fun TagloomEditorState.selectedBlockRanges(): List<SelectedBlockRange> {
    val active = selection
    val blocks = selectedDocument?.note?.blocks.orEmpty().filterNot(Block::deleted).associateBy(Block::id)
    return active.ids.mapIndexedNotNull { index, id ->
        val block = blocks[id] ?: return@mapIndexedNotNull null
        val length = block.selectionText()?.length ?: return@mapIndexedNotNull null
        SelectedBlockRange(
            block, if (index == 0) active.start.coerceIn(0, length) else 0,
            if (index == active.ids.lastIndex) active.end.coerceIn(0, length) else length
        )
    }
}

/** @return 当前选中的原文，块之间使用换行分隔。 */
internal fun TagloomEditorState.selectedPlainText(): String = selectedBlockRanges().joinToString("\n") {
    it.block.selectionText().orEmpty().substring(it.start, it.end)
}

/**
 * 一次替换整个正文选区，保留两端样式、稳定 ID、未选子块和 tombstone。
 * @param text 输入或剪贴板中的替换原文。
 * @return 替换后的光标；选区已失效时为空。
 */
internal fun TagloomEditorState.replaceDocumentSelection(text: String): EditorCursorTarget? {
    val ranges = selectedBlockRanges()
    val first = ranges.firstOrNull() ?: return null
    val last = ranges.last()
    val removed = ranges.drop(1).map { it.block.id }.toSet()
    val cursor = EditorCursorTarget(first.block.id, first.start + text.length)
    updateSelectedNote { note ->
        // 先拼接两端仍在选区外的内容，富文本的 Mark 和加注锚点由切片保留。
        val left = first.block.editableRichText() ?: RichTextDocumentReducer.fromPlainText(
            first.block.selectionText().orEmpty()
        )
        val right =
            last.block.editableRichText() ?: RichTextDocumentReducer.fromPlainText(last.block.selectionText().orEmpty())
        val joined = RichTextDocumentReducer.concat(
            RichTextDocumentReducer.concat(
                RichTextDocumentReducer.slice(left, 0, first.start),
                RichTextDocumentReducer.fromPlainText(text)
            ),
            RichTextDocumentReducer.slice(right, last.end, right.plainText.length),
        )
        val byId = note.blocks.associateBy(Block::id)
        val order = BlockTreeReducer.orderedActive(note.blocks)
            .mapIndexed { index, block -> block.id to index.toString().padStart(10, '0') }.toMap()
        val blocks = note.blocks.map { block ->
            when {
                block.id == first.block.id -> if (block.content is CodeBlockContent) {
                    block.copy(content = (block.content as CodeBlockContent).copy(text = joined.plainText))
                } else block.withEditableRichText(joined)

                block.id in removed -> block.copy(deleted = true)
                !block.deleted && block.parentId in removed -> {
                    // 只删除实际选中的块；未选后代接回最近的存活父级，避免连带删除或悬空父边。
                    var parent = block.parentId
                    val visited = mutableSetOf<BlockId>()
                    while (parent in removed && parent != null && visited.add(parent)) parent = byId[parent]?.parentId
                    block.copy(parentId = parent)
                }

                else -> block
            }
        }
            .map { block -> if (!block.deleted && removed.isNotEmpty()) block.copy(orderKey = order.getValue(block.id)) else block }
        MarkdownPasteAnnotationReducer.reconcileOrphans(note.copy(blocks = blocks), currentEditorTimestamp())
    }
    updateSelection(cursor.blockId, cursor.offset, cursor.offset)
    return cursor
}
