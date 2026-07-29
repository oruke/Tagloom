package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.RichText

/**
 * 保存把多行 Markdown 粘贴转换为 Block 后的文档与光标目标。
 */
internal data class MarkdownBlockPasteEdit(
    /** 替换完成并重新编号的全部 Block。 */
    val blocks: List<Block>,
    /** 转换后最后一个可编辑 Block 的光标位置。 */
    val cursor: EditorCursorTarget,
)

/**
 * 把块内选区替换为 Markdown 解析得到的一个或多个同级 Block。
 */
internal object MarkdownBlockPasteReducer {
    /**
     * 在指定 Block 的字符区间插入已解析 Block，并保留区间前后 RichText。
     *
     * @param blocks 当前 Note 全部 Block。
     * @param blockId 接收粘贴的文本 Block。
     * @param selectionStart 被替换选区起点。
     * @param selectionEnd 被替换选区终点。
     * @param importedBlocks Markdown 解析器生成的活动 Block。
     * @param idFactory 新尾部段落所需稳定 ID 工厂。
     * @return 成功替换结果；目标不可编辑时为空。
     */
    fun replace(
        blocks: List<Block>,
        blockId: BlockId,
        selectionStart: Int,
        selectionEnd: Int,
        importedBlocks: List<Block>,
        idFactory: (String) -> String,
    ): MarkdownBlockPasteEdit? {
        val targetIndex = blocks.indexOfFirst { block -> block.id == blockId && !block.deleted }
        if (targetIndex < 0) {
            return null
        }
        val target = blocks[targetIndex]
        val original = target.editableRichText() ?: return null
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, original.plainText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, original.plainText.length)
        val prefix = RichTextDocumentReducer.slice(original, 0, start)
        val suffix = RichTextDocumentReducer.slice(original, end, original.plainText.length)
        val imported = importedBlocks.filterNot(Block::deleted).map { block ->
            block.copy(parentId = target.parentId)
        }
        val replacements = buildReplacements(target, prefix, suffix, imported, idFactory)
        val replaced = blocks.take(targetIndex) + replacements + blocks.drop(targetIndex + 1)
        val reindexed = reindexSiblings(replaced, target.parentId)
        val cursorBlock = replacements.lastOrNull { block -> editableLength(block) != null } ?: target
        return MarkdownBlockPasteEdit(
            blocks = reindexed,
            cursor = EditorCursorTarget(cursorBlock.id, editableLength(cursorBlock).orZero()),
        )
    }

    /**
     * 根据前缀、导入块和后缀构造最小替换序列。
     *
     * @param target 原始 Block。
     * @param prefix 粘贴点之前的富文本。
     * @param suffix 粘贴点之后的富文本。
     * @param imported 已转换为同级的 Markdown Block。
     * @param idFactory 必要时创建尾部段落的 ID 工厂。
     * @return 至少包含一个 Block 的替换序列。
     */
    private fun buildReplacements(
        target: Block,
        prefix: RichText,
        suffix: RichText,
        imported: List<Block>,
        idFactory: (String) -> String,
    ): List<Block> {
        if (imported.isEmpty()) {
            return listOf(target.withEditableRichText(RichTextDocumentReducer.concat(prefix, suffix)))
        }
        val replacements = mutableListOf<Block>()
        if (prefix.plainText.isNotEmpty()) {
            replacements += target.withEditableRichText(prefix)
            replacements += imported
        } else {
            val first = imported.first().copy(
                id = target.id,
                parentId = target.parentId,
                orderKey = target.orderKey,
            )
            replacements += first
            replacements += imported.drop(1)
        }
        if (suffix.plainText.isNotEmpty()) {
            val lastIndex = replacements.lastIndex
            val last = replacements[lastIndex]
            val lastRichText = last.editableRichText()
            if (lastRichText != null) {
                replacements[lastIndex] = last.withEditableRichText(
                    RichTextDocumentReducer.concat(lastRichText, suffix),
                )
            } else {
                replacements += EditorBlockFactory.create(BlockTypes.PARAGRAPH, replacements.size, idFactory)
                    .copy(parentId = target.parentId)
                    .withEditableRichText(suffix)
            }
        }
        return replacements
    }

    /**
     * 重建目标父节点下的连续稳定顺序键。
     *
     * @param blocks 替换后的全部 Block。
     * @param parentId 被粘贴 Block 的父节点。
     * @return 只更新目标同级 Block 顺序键的新集合。
     */
    private fun reindexSiblings(
        blocks: List<Block>,
        parentId: BlockId?,
    ): List<Block> {
        val orderById = blocks
            .filter { block -> !block.deleted && block.parentId == parentId }
            .mapIndexed { index, block -> block.id to EditorBlockFactory.orderKey(index) }
            .toMap()
        return blocks.map { block ->
            orderById[block.id]?.let { orderKey -> block.copy(orderKey = orderKey) } ?: block
        }
    }

    /**
     * 返回编辑器可放置光标的文本长度。
     *
     * @param block 候选 Block。
     * @return RichText 或代码长度；不可编辑内容为空。
     */
    private fun editableLength(block: Block): Int? = when (val content = block.content) {
        is CodeBlockContent -> content.text.length
        else -> block.editableRichText()?.plainText?.length
    }

    /**
     * 把可空整数转换为安全光标偏移。
     *
     * @return 当前值或零。
     */
    private fun Int?.orZero(): Int = this ?: 0
}
