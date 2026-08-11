package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BlobId
import com.oruke.tagloom.core.model.BlockContent
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent

/**
 * 合并同一 Block 的多态内容，并把无法自动解释的重叠修改标记为冲突。
 *
 * 该对象只负责内容字段，不选择整个 Block 的临时胜者，也不创建持久化冲突记录。
 */
internal object BlockContentThreeWayMerge {
    /**
     * 合并支持 RichText 的 Block 内容；未知类型或重叠元数据修改保守地产生冲突。
     *
     * @param base 共同祖先内容。
     * @param local 当前设备内容。
     * @param remote 远端设备内容。
     * @return 可用内容及其是否仍需上层创建冲突记录。
     */
    fun merge(
        base: BlockContent,
        local: BlockContent,
        remote: BlockContent,
    ): BlockContentMerge {
        val simple = ThreeWayValueMerge.merge(base, local, remote)
        if (!simple.conflicted) return BlockContentMerge(simple.value, conflicted = false)

        return when {
            base is RichTextBlockContent && local is RichTextBlockContent && remote is RichTextBlockContent ->
                mergeRichTextContent(base.richText, local.richText, remote.richText) { richText ->
                    RichTextBlockContent(richText)
                }

            base is HeadingBlockContent && local is HeadingBlockContent && remote is HeadingBlockContent -> {
                val level = ThreeWayValueMerge.merge(base.level, local.level, remote.level)
                mergeRichTextContent(base.richText, local.richText, remote.richText) { richText ->
                    HeadingBlockContent(level.value, richText)
                }.withAdditionalConflict(level.conflicted)
            }

            base is TodoBlockContent && local is TodoBlockContent && remote is TodoBlockContent -> {
                val checked = ThreeWayValueMerge.merge(base.checked, local.checked, remote.checked)
                mergeRichTextContent(base.richText, local.richText, remote.richText) { richText ->
                    TodoBlockContent(checked.value, richText)
                }.withAdditionalConflict(checked.conflicted)
            }

            base is AttachmentBlockContent && local is AttachmentBlockContent && remote is AttachmentBlockContent ->
                mergeAttachment(base, local, remote)

            base is CodeBlockContent && local is CodeBlockContent && remote is CodeBlockContent ->
                mergeCode(base, local, remote)

            base is BaseTableBlockContent && local is BaseTableBlockContent && remote is BaseTableBlockContent ->
                BaseTableThreeWayMerge.merge(base, local, remote)

            else -> BlockContentMerge(simple.value, conflicted = true)
        }
    }

    /**
     * 分别合并附件元数据与 caption，避免依据对象相等性猜测元数据来自哪一侧。
     *
     * @param base 共同祖先附件。
     * @param local 当前设备附件。
     * @param remote 远端设备附件。
     * @return 附件内容合并结果。
     */
    private fun mergeAttachment(
        base: AttachmentBlockContent,
        local: AttachmentBlockContent,
        remote: AttachmentBlockContent,
    ): BlockContentMerge {
        val metadata = ThreeWayValueMerge.merge(
            base = AttachmentMetadata(base.blobId, base.fileName, base.mimeType),
            local = AttachmentMetadata(local.blobId, local.fileName, local.mimeType),
            remote = AttachmentMetadata(remote.blobId, remote.fileName, remote.mimeType),
        )
        val caption = RichTextThreeWayMerge.merge(base.caption, local.caption, remote.caption)
        return BlockContentMerge(
            value = AttachmentBlockContent(
                blobId = metadata.value.blobId,
                fileName = metadata.value.fileName,
                mimeType = metadata.value.mimeType,
                caption = caption.merged ?: local.caption,
            ),
            conflicted = metadata.conflicted || caption.conflicted,
        )
    }

    /**
     * 合并代码块语言与纯文本；文本复用带样式字符的区间算法但不引入样式。
     *
     * @param base 共同祖先代码块。
     * @param local 当前设备代码块。
     * @param remote 远端设备代码块。
     * @return 代码块内容合并结果。
     */
    private fun mergeCode(
        base: CodeBlockContent,
        local: CodeBlockContent,
        remote: CodeBlockContent,
    ): BlockContentMerge {
        val language = ThreeWayValueMerge.merge(base.language, local.language, remote.language)
        val text = RichTextThreeWayMerge.merge(
            base = RichText(listOf(InlineRun(base.text))),
            local = RichText(listOf(InlineRun(local.text))),
            remote = RichText(listOf(InlineRun(remote.text))),
        )
        return BlockContentMerge(
            value = CodeBlockContent(language.value, text.merged?.plainText ?: local.text),
            conflicted = language.conflicted || text.conflicted,
        )
    }

    /**
     * 合并 RichText 并重建具体 Block 内容。
     *
     * @param base 共同祖先 RichText。
     * @param local 当前设备 RichText。
     * @param remote 远端设备 RichText。
     * @param rebuild 用合并 RichText 重建内容的函数。
     * @return Block 内容合并结果。
     */
    private fun mergeRichTextContent(
        base: RichText,
        local: RichText,
        remote: RichText,
        rebuild: (RichText) -> BlockContent,
    ): BlockContentMerge {
        val outcome = RichTextThreeWayMerge.merge(base, local, remote)
        return BlockContentMerge(
            value = rebuild(outcome.merged ?: local),
            conflicted = outcome.conflicted,
        )
    }

    /**
     * 给内容合并结果叠加独立元数据冲突。
     *
     * @param additional 是否还存在元数据冲突。
     * @return 叠加冲突标记后的结果。
     */
    private fun BlockContentMerge.withAdditionalConflict(additional: Boolean): BlockContentMerge =
        copy(conflicted = conflicted || additional)

    /** 表示附件除 caption 外参与三方比较的元数据。 */
    private data class AttachmentMetadata(
        /** 加密 Blob ID。 */
        val blobId: BlobId,
        /** 原始文件名，只能位于加密 payload。 */
        val fileName: String,
        /** 可选 MIME。 */
        val mimeType: String?,
    )

}

/** 表示 Block 内容层合并结果。 */
internal data class BlockContentMerge(
    /** 自动合并内容或仅供上层判定冲突的候选内容。 */
    val value: BlockContent,
    /** 是否仍需上层保留完整 Base/Local/Remote 证据。 */
    val conflicted: Boolean,
)
