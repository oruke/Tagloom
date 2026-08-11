package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent

/**
 * 读取支持行内编辑的 Block RichText。
 *
 * @return 段落、标题、待办或附件说明的 RichText；其他 Block 返回 null。
 */
fun Block.editableRichText(): RichText? = when (val current = content) {
    is RichTextBlockContent -> current.richText
    is HeadingBlockContent -> current.richText
    is TodoBlockContent -> current.richText
    is AttachmentBlockContent -> current.caption
    is BaseTableBlockContent,
    is CodeBlockContent,
    is DatabaseBlockContent,
    DividerBlockContent,
    is NoteReferenceBlockContent,
    is TabBlockContent,
    TabsBlockContent,
    is UnknownBlockContent,
    -> null
}

/**
 * 在保持 Block 类型特有字段的前提下替换可编辑 RichText。
 *
 * @param richText 新的规范化行内内容。
 * @return 替换后的 Block；不支持行内编辑时返回原 Block。
 */
fun Block.withEditableRichText(richText: RichText): Block = copy(
    content = when (val current = content) {
        is RichTextBlockContent -> current.copy(richText = richText)
        is HeadingBlockContent -> current.copy(richText = richText)
        is TodoBlockContent -> current.copy(richText = richText)
        is AttachmentBlockContent -> current.copy(caption = richText)
        is BaseTableBlockContent,
        is CodeBlockContent,
        is DatabaseBlockContent,
        DividerBlockContent,
        is NoteReferenceBlockContent,
        is TabBlockContent,
        TabsBlockContent,
        is UnknownBlockContent,
        -> current
    },
)
