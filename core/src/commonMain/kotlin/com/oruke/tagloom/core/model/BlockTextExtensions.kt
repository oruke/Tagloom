package com.oruke.tagloom.core.model

/**
 * 提取 Block 可用于本地搜索、预览或授权 AI 上下文的纯文本。
 *
 * @param includeAttachmentFileName 是否包含可能敏感的原始附件文件名。
 * @return 没有文本或 Block 已删除时为空。
 */
fun Block.extractPlainText(includeAttachmentFileName: Boolean = true): String? {
    if (deleted) return null
    return when (val value = content) {
        is RichTextBlockContent -> value.richText.plainText
        is HeadingBlockContent -> value.richText.plainText
        is TodoBlockContent -> value.richText.plainText
        is CodeBlockContent -> value.text
        is AttachmentBlockContent -> buildList {
            if (includeAttachmentFileName) add(value.fileName)
            add(value.caption.plainText)
        }.filter(String::isNotBlank).joinToString(separator = " ")
        is TabBlockContent -> value.title.plainText
        is BaseTableBlockContent -> buildList {
            add(value.title.plainText)
            addAll(value.columns.filterNot(BaseTableColumn::deleted).map { column -> column.name.plainText })
            value.rows.filterNot(BaseTableRow::deleted).forEach { row ->
                row.cells.values.forEach { cell ->
                    when (cell) {
                        is BaseTableTextCell -> add(cell.richText.plainText)
                        is BaseTableScalarCell -> add(cell.value)
                        is BaseTableBooleanCell -> add(cell.value.toString())
                        is UnknownBaseTableCell -> Unit
                    }
                }
            }
        }.filter(String::isNotBlank).joinToString(separator = " ")
        else -> null
    }?.takeIf(String::isNotBlank)
}

/**
 * 返回 Block 中可能承载行内样式、链接和加注的 RichText。
 *
 * @return 不支持行内内容或 Block 已删除时为空。
 */
fun Block.richTextOrNull(): RichText? {
    if (deleted) return null
    return when (val value = content) {
        is RichTextBlockContent -> value.richText
        is HeadingBlockContent -> value.richText
        is TodoBlockContent -> value.richText
        is AttachmentBlockContent -> value.caption
        is TabBlockContent -> value.title
        else -> null
    }
}
