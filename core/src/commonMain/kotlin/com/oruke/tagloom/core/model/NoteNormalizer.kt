package com.oruke.tagloom.core.model

/**
 * 将笔记及其全部 RichText 转换为确定性的持久化表示。
 *
 * 规范化必须发生在计算快照哈希和加密之前，否则语义相同但 Run 切分不同的笔记会产生不同密文与 Change。
 */
object NoteNormalizer {
    /**
     * 规范化笔记内全部支持 RichText 的 Block，并稳定化集合顺序。
     *
     * @param note 待写入权威快照的笔记。
     * @return 可用于确定性序列化、哈希与加密的规范化笔记。
     */
    fun normalize(note: Note): Note = note.copy(
        directTagIds = note.directTagIds.sortedBy(TagId::value),
        manualPropertyBindings = note.manualPropertyBindings.sortedBy { binding -> binding.propertyId.value },
        blocks = note.blocks.map(::normalizeBlock),
    )

    /**
     * 根据 Block payload 类型规范化其中承载的 RichText。
     *
     * @param block 待规范化的 Block。
     * @return 保持 Block 身份和树位置不变的规范化结果。
     */
    private fun normalizeBlock(block: Block): Block = block.copy(
        content = when (val content = block.content) {
            is RichTextBlockContent -> content.copy(
                richText = RichTextNormalizer.normalize(content.richText),
            )

            is HeadingBlockContent -> content.copy(
                richText = RichTextNormalizer.normalize(content.richText),
            )

            is TodoBlockContent -> content.copy(
                richText = RichTextNormalizer.normalize(content.richText),
            )

            is AttachmentBlockContent -> content.copy(
                caption = RichTextNormalizer.normalize(content.caption),
            )

            is CodeBlockContent,
            DividerBlockContent,
            is NoteReferenceBlockContent,
            is UnknownBlockContent,
            -> content
        },
    )
}
