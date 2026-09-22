package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 剪贴板中的最小正文片段，不包含笔记身份、属性、附件或未选中的加注正文。 */
@Serializable
internal data class EditorClipboardFragment(
    /** 私有剪贴板协议版本。 */
    val version: Int = 1,
    /** 单块局部选区按行内内容插入，不改变目标块类型。 */
    val inline: Boolean,
    /** 按可见正文顺序排列的已裁剪内容。 */
    val blocks: List<EditorClipboardBlock>,
)

/** 可重新分配身份的选中块；父边只引用片段内的前序下标。 */
@Serializable
internal data class EditorClipboardBlock(
    /** 可移植的正文块类型。 */
    val type: String,
    /** 已选中父块在片段中的下标；不存在时作为根块。 */
    val parentIndex: Int? = null,
    /** 复制时界面显示的有序列表编号。 */
    val ordinal: Int = 1,
    /** 标题层级。 */
    val level: Int = 1,
    /** 待办完成状态。 */
    val checked: Boolean = false,
    /** 代码语言标识。 */
    val language: String? = null,
    /** 选中的文字和行内 Mark；代码仅保存原文，不保存语法高亮。 */
    val richText: RichText,
)

/** 系统剪贴板的多格式载荷；外部纯文本仍能读到列表标记。 */
internal data class EditorClipboardContent(
    /** 通用纯文本表示。 */
    val text: String,
    /** 支持富文本的目标应用使用的 HTML。 */
    val html: String? = null,
    /** 笔记之间无损恢复正文结构所需的最小片段。 */
    val fragment: EditorClipboardFragment? = null,
)

/** 私有片段使用独立 MIME，避免把内部协议文本粘贴到普通编辑器。 */
internal const val EDITOR_CLIPBOARD_MIME = "application/vnd.tagloom.fragment+json"

/** 只容忍新增字段；非法内容不会进入编辑器写入流程。 */
private val clipboardJson = Json { ignoreUnknownKeys = true }

/** @return 当前片段的版本化 JSON，不附带源笔记身份。 */
internal fun EditorClipboardFragment.encode(): String = clipboardJson.encodeToString(this)

/**
 * 校验外部剪贴板片段，包括版本、块类型、父边和最大深度。
 * @param encoded 私有 MIME 提供的 JSON；其他应用可以任意构造，不能直接信任。
 * @return 可安全粘贴的片段；未知版本或损坏数据为空，调用方退回纯文本。
 */
internal fun decodeClipboardFragment(encoded: String?): EditorClipboardFragment? = runCatching {
    if (encoded == null || encoded.length > 8_000_000) return null
    val fragment = clipboardJson.decodeFromString<EditorClipboardFragment>(encoded)
    require(fragment.version == 1 && fragment.blocks.size in 1..10_000)
    val depths = mutableListOf<Int>()
    fragment.blocks.forEachIndexed { index, block ->
        require(
            block.type in setOf(
                BlockTypes.PARAGRAPH, BlockTypes.HEADING, BlockTypes.BULLETED_LIST,
                BlockTypes.NUMBERED_LIST, BlockTypes.TODO, BlockTypes.QUOTE, BlockTypes.CODE
            )
        )
        require(block.parentIndex == null || block.parentIndex in 0 until index)
        require(block.level in 1..6 && block.ordinal in 1..1_000_000)
        val depth = block.parentIndex?.let { depths[it] + 1 } ?: 0
        require(depth <= 64)
        depths += depth
    }
    fragment.copy(blocks = fragment.blocks.map { block ->
        block.copy(richText = RichTextNormalizer.normalize(RichText(block.richText.runs.map { it.copy(annotationIds = emptyList()) })))
    })
}.getOrNull()

/**
 * 只序列化当前选区，保留样式、列表实际编号和选中块之间的父子关系。
 * @return 没有有效文字选区时为空。
 */
internal fun TagloomEditorState.selectedClipboardFragment(): EditorClipboardFragment? {
    if (!selection.isNotEmpty) return null
    val ranges = selectedBlockRanges().dropLastWhile { it.start == it.end }
    if (ranges.isEmpty()) return null
    val indices = ranges.mapIndexed { index, range -> range.block.id to index }.toMap()
    val first = ranges.first()
    return EditorClipboardFragment(
        inline = ranges.size == 1 && (first.start != 0 || first.end != first.block.selectionText().orEmpty().length ||
                first.block.content is AttachmentBlockContent),
        blocks = ranges.map { (block, start, end) ->
            val original =
                block.editableRichText() ?: RichTextDocumentReducer.fromPlainText(block.selectionText().orEmpty())
            EditorClipboardBlock(
                type = if (block.content is AttachmentBlockContent) BlockTypes.PARAGRAPH else block.type,
                parentIndex = indices[block.parentId],
                ordinal = if (block.type == BlockTypes.NUMBERED_LIST) numberedListOrdinal(block.id) else 1,
                level = (block.content as? HeadingBlockContent)?.level ?: 1,
                checked = (block.content as? TodoBlockContent)?.checked ?: false,
                language = (block.content as? CodeBlockContent)?.language,
                richText = RichText(
                    RichTextDocumentReducer.slice(
                        original,
                        start,
                        end
                    ).runs.map { it.copy(annotationIds = emptyList()) }),
            )
        },
    )
}

/** @return 保留列表标记和相对缩进的纯文本，不把富文本标记变成用户正文。 */
internal fun EditorClipboardFragment.plainText(): String {
    if (inline) return blocks.joinToString("\n") { it.richText.plainText }
    val depths = mutableListOf<Int>()
    return blocks.map { block ->
        val depth = block.parentIndex?.let { depths[it] + 1 } ?: 0
        depths += depth
        val prefix = when (block.type) {
            BlockTypes.NUMBERED_LIST -> "${block.ordinal}. "
            BlockTypes.BULLETED_LIST -> "- "
            BlockTypes.TODO -> if (block.checked) "- [x] " else "- [ ] "
            BlockTypes.HEADING -> "${"#".repeat(block.level)} "
            BlockTypes.QUOTE -> "> "
            else -> ""
        }
        val indent = "    ".repeat(depth)
        block.richText.plainText.split('\n').mapIndexed { index, line ->
            indent + (if (index == 0) prefix else " ".repeat(prefix.length)) + line
        }.joinToString("\n")
    }.joinToString("\n")
}
