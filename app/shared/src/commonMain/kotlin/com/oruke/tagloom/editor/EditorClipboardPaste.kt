package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把剪贴板替换到当前选区；代码输入只接收纯文本，正文优先恢复结构化片段。
 * @param content 经平台端口读取的内容。
 * @param plainOnly 用户是否要求不带格式粘贴。
 * @return 完成后的光标目标；选区失效时为空。
 */
internal fun TagloomEditorState.pasteClipboardContent(
    content: EditorClipboardContent,
    plainOnly: Boolean = false,
): EditorCursorTarget? {
    val first = selectedBlockRanges().firstOrNull() ?: return null
    if (plainOnly || first.block.content is CodeBlockContent || first.block.content is AttachmentBlockContent)
        return replaceDocumentSelection(content.text)
    val fragment = content.fragment
    if (fragment == null) {
        if ('\n' !in content.text && '\r' !in content.text) return replaceDocumentSelection(content.text)
        val target = replaceDocumentSelection("") ?: return null
        return pasteMarkdownAtBlock(target.blockId, target.offset, target.offset, content.text)
            ?: replaceDocumentSelection(content.text)
    }
    // 先收拢跨块范围，复用 tombstone 和未选后代保护；再把新身份片段插入同一个选区位置。
    val target = if (selection.isCrossBlock) replaceDocumentSelection("") ?: return null
    else EditorCursorTarget(first.block.id, first.start)
    var cursor: EditorCursorTarget? = null
    updateSelectedNote { note ->
        val edit = EditorClipboardPasteReducer.replace(
            note.blocks, target.blockId, selection.start, selection.end,
            fragment, ::createEditorId
        ) ?: return@updateSelectedNote note
        cursor = edit.cursor
        MarkdownPasteAnnotationReducer.reconcileOrphans(note.copy(blocks = edit.blocks), currentEditorTimestamp())
    }
    cursor?.let { updateSelection(it.blockId, it.offset, it.offset) }
    return cursor
}

/** 插入内部正文片段，重新分配块身份并保留父子结构和原文两侧样式。 */
internal object EditorClipboardPasteReducer {
    /**
     * 替换单个目标块中的选区；调用方负责提前收拢跨块选择。
     * @param blocks 笔记全部活动块及 tombstone。
     * @param blockId 目标块身份。
     * @param start 规范化后的字符起点。
     * @param end 规范化后的字符终点。
     * @param fragment 已校验且已裁剪的剪贴板片段。
     * @param idFactory 新块身份工厂。
     * @return 新块集合和插入末尾光标，不包含原后缀长度。
     */
    fun replace(
        blocks: List<Block>, blockId: BlockId, start: Int, end: Int,
        fragment: EditorClipboardFragment, idFactory: (String) -> String
    ): MarkdownBlockPasteEdit? {
        val ordered = BlockTreeReducer.orderedActive(blocks)
        val targetIndex = ordered.indexOfFirst { it.id == blockId }
        if (targetIndex < 0) return null
        val target = ordered[targetIndex]
        val original = target.editableRichText() ?: return null
        val prefix = RichTextDocumentReducer.slice(original, 0, start)
        val suffix = RichTextDocumentReducer.slice(original, end, original.plainText.length)
        val single = fragment.blocks.singleOrNull()
        if (single != null && (fragment.inline || single.type == BlockTypes.PARAGRAPH)) {
            val joined = RichTextDocumentReducer.concat(RichTextDocumentReducer.concat(prefix, single.richText), suffix)
            return MarkdownBlockPasteEdit(
                blocks.map { if (it.id == blockId) it.withEditableRichText(joined) else it },
                EditorCursorTarget(blockId, prefix.plainText.length + single.richText.plainText.length)
            )
        }
        val preserveTarget = prefix.plainText.isNotEmpty() || blocks.any { !it.deleted && it.parentId == blockId }
        val ids = fragment.blocks.mapIndexed { index, _ ->
            if (index == 0 && !preserveTarget) blockId else BlockId(idFactory("block"))
        }
        val imported = fragment.blocks.mapIndexed { index, source ->
            val content = when (source.type) {
                BlockTypes.HEADING -> HeadingBlockContent(source.level, source.richText)
                BlockTypes.TODO -> TodoBlockContent(source.checked, source.richText)
                BlockTypes.CODE -> CodeBlockContent(source.language, source.richText.plainText)
                else -> RichTextBlockContent(source.richText)
            }
            Block(ids[index], source.type, parentId = source.parentIndex?.let { ids[it] } ?: target.parentId,
                orderKey = EditorBlockFactory.orderKey(index), content = content,
                attributes = buildJsonObject {
                    if (source.type == BlockTypes.NUMBERED_LIST && source.ordinal != 1)
                        put(NUMBERED_LIST_START_ATTRIBUTE, source.ordinal)
                })
        }.toMutableList()
        val last = imported.last()
        val cursor = EditorCursorTarget(last.id, last.selectionText().orEmpty().length)
        if (suffix.plainText.isNotEmpty()) {
            val richText = last.editableRichText()
            if (richText != null) imported[imported.lastIndex] =
                last.withEditableRichText(RichTextDocumentReducer.concat(richText, suffix))
            else imported += EditorBlockFactory.create(BlockTypes.PARAGRAPH, imported.size, idFactory)
                .copy(parentId = target.parentId).withEditableRichText(suffix)
        }
        val replacements = (if (preserveTarget) listOf(target.withEditableRichText(prefix)) else emptyList()) + imported
        val active = ordered.take(targetIndex) + replacements + ordered.drop(targetIndex + 1)
        val reindexed = active.mapIndexed { index, block -> block.copy(orderKey = EditorBlockFactory.orderKey(index)) }
        return MarkdownBlockPasteEdit(reindexed + blocks.filter(Block::deleted), cursor)
    }
}

/** 连续有序列表首项的起始编号；中间项仍按同级顺序自动递增。 */
internal const val NUMBERED_LIST_START_ATTRIBUTE = "numbered-list-start"
