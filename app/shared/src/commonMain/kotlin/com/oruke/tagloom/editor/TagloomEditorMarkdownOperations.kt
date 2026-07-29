package com.oruke.tagloom.editor

import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownImportOptions
import com.oruke.tagloom.core.model.BlockId

/**
 * 把刚闭合的 Markdown 行内语法写为结构化 Mark。
 *
 * @param blockId 当前输入 Block。
 * @param caretOffset 当前折叠光标位置。
 * @return 转换后的光标目标；没有可转换 token 时为空。
 */
fun TagloomEditorState.applyInlineMarkdownShortcut(
    blockId: BlockId,
    caretOffset: Int,
): EditorCursorTarget? {
    var cursor: EditorCursorTarget? = null
    updateBlock(blockId) { block ->
        val richText = block.editableRichText() ?: return@updateBlock block
        val edit = InlineMarkdownShortcutReducer.apply(richText, caretOffset) ?: return@updateBlock block
        cursor = EditorCursorTarget(blockId, edit.cursorOffset)
        block.withEditableRichText(edit.richText)
    }
    cursor?.let { target -> updateSelection(target.blockId, target.offset, target.offset) }
    return cursor
}

/**
 * 把编辑器内一次多行粘贴解析为 Block 并替换当前字符选区。
 *
 * @param blockId 接收粘贴的文本 Block。
 * @param selectionStart 被替换旧区间起点。
 * @param selectionEnd 被替换旧区间终点。
 * @param markdown 剪贴板插入的原始 Markdown 文本。
 * @return 转换后的光标目标；目标不可编辑时为空。
 */
fun TagloomEditorState.pasteMarkdownAtBlock(
    blockId: BlockId,
    selectionStart: Int,
    selectionEnd: Int,
    markdown: String,
): EditorCursorTarget? {
    if ('\n' !in markdown && '\r' !in markdown) {
        return null
    }
    var blockCounter = 0
    val imported = MarkdownCodec.import(
        markdown = markdown,
        options = MarkdownImportOptions(
            defaultTitle = "粘贴内容",
            firstHeadingAsTitle = false,
            importedAt = currentEditorTimestamp(),
            blockIdFactory = {
                blockCounter += 1
                BlockId(createEditorId("block"))
            },
        ),
    )
    var cursor: EditorCursorTarget? = null
    updateSelectedNote { note ->
        val edit = MarkdownBlockPasteReducer.replace(
            blocks = note.blocks,
            blockId = blockId,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            importedBlocks = imported.note.blocks,
            idFactory = ::createEditorId,
        ) ?: return@updateSelectedNote note
        cursor = edit.cursor
        note.copy(
            blocks = edit.blocks,
            annotations = note.annotations + imported.note.annotations,
        )
    }
    cursor?.let { target -> updateSelection(target.blockId, target.offset, target.offset) }
    return cursor
}
