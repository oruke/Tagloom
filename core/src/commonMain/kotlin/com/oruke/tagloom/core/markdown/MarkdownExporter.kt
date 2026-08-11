package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableBooleanCell
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableScalarCell
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent

/**
 * 负责把领域对象渲染为 Markdown，并集中记录不可移植能力。
 */
internal object MarkdownExporter {
    /**
     * 导出一篇 Note，并返回去重后的能力差异。
     *
     * @param note 待导出的 Note。
     * @param mode 目标 Markdown 模式。
     * @return Markdown 文本与能力差异。
     */
    fun export(
        note: Note,
        mode: MarkdownExportMode,
    ): MarkdownExportResult {
        val losses = mutableListOf<MarkdownCapabilityLoss>()
        val lines = mutableListOf<String>()
        if (note.title.isNotBlank()) {
            lines += "# ${MarkdownInlineCodec.escapePlain(note.title)}"
            lines += ""
        }

        note.blocks.filterNot(Block::deleted).forEach { block ->
            lines += exportBlock(block, mode, losses)
            lines += ""
        }
        if (mode == MarkdownExportMode.TAGLOOM_EXTENDED) {
            val referenced = note.blocks
                .flatMap(::annotationIds)
                .distinct()
                .mapNotNull(note.annotations::get)
            if (referenced.isNotEmpty()) {
                lines += "<!-- tagloom-annotations -->"
                referenced.forEach { annotation ->
                    lines += "[^${annotation.id.value}]: ${MarkdownInlineCodec.escapePlain(annotation.body)}"
                }
            }
        } else if (note.annotations.isNotEmpty()) {
            losses += MarkdownCapabilityLoss(
                code = "annotations_omitted",
                message = "标准 Markdown 不保留行内加注锚点与加注正文",
            )
        }
        if (note.directTagIds.isNotEmpty()) {
            losses += MarkdownCapabilityLoss(
                code = "tags_omitted",
                message = "Markdown 正文未包含 Tagloom 标签关系",
            )
        }
        if (note.propertyValues.isNotEmpty() || note.manualPropertyBindings.isNotEmpty()) {
            losses += MarkdownCapabilityLoss(
                code = "properties_omitted",
                message = "Markdown 正文未包含 Tagloom 属性值与绑定",
            )
        }
        return MarkdownExportResult(
            markdown = lines.dropLastWhile(String::isBlank).joinToString("\n") + "\n",
            capabilityLosses = losses.distinctBy { loss -> Triple(loss.code, loss.message, loss.blockId) },
        )
    }

    /**
     * 导出单个 Block，并把无法表达的内容加入损失清单。
     *
     * @param block 待导出 Block。
     * @param mode 当前导出模式。
     * @param losses 可追加的能力差异集合。
     * @return 一行或多行 Markdown。
     */
    private fun exportBlock(
        block: Block,
        mode: MarkdownExportMode,
        losses: MutableList<MarkdownCapabilityLoss>,
    ): String = when (val content = block.content) {
        is HeadingBlockContent -> "${"#".repeat(content.level)} ${exportInline(content.richText, mode, losses, block.id)}"
        is TodoBlockContent -> "- [${if (content.checked) "x" else " "}] ${exportInline(content.richText, mode, losses, block.id)}"
        is RichTextBlockContent -> {
            val prefix = when (block.type) {
                BlockTypes.BULLETED_LIST -> "- "
                BlockTypes.NUMBERED_LIST -> "1. "
                BlockTypes.QUOTE -> "> "
                else -> ""
            }
            prefix + exportInline(content.richText, mode, losses, block.id)
        }
        is CodeBlockContent -> "```${content.language.orEmpty()}\n${content.text}\n```"
        DividerBlockContent -> "---"
        is AttachmentBlockContent -> {
            losses += MarkdownCapabilityLoss(
                code = "encrypted_attachment_reference",
                message = "加密附件只能导出说明，不能生成可移植明文链接",
                blockId = block.id,
            )
            val label = content.caption.plainText.ifBlank { content.fileName }
            "[${MarkdownInlineCodec.escapePlain(label)}](tagloom-blob:${content.blobId.value})"
        }
        is NoteReferenceBlockContent -> "[Tagloom note ${content.noteId.value}](tagloom-note:${content.noteId.value})"
        TabsBlockContent -> {
            losses += MarkdownCapabilityLoss(
                code = "tabs_flattened",
                message = "Markdown 已按正文顺序展开页签结构，当前活动页签和默认页签不会保留",
                blockId = block.id,
            )
            ""
        }
        is TabBlockContent -> "## ${exportInline(content.title, mode, losses, block.id)}"
        is BaseTableBlockContent -> exportBaseTable(content, mode, losses, block.id)
        is DatabaseBlockContent -> {
            losses += MarkdownCapabilityLoss(
                code = "database_projection_omitted",
                message = "Markdown 不包含动态 Database 投影，仅保留 Tagloom 数据集引用",
                blockId = block.id,
            )
            "[Tagloom Database](tagloom-database:${content.databaseId.value})"
        }
        is UnknownBlockContent -> {
            losses += MarkdownCapabilityLoss(
                code = "unknown_block_omitted",
                message = "未知 Block 已以占位文本导出，原始扩展数据仍保留在工作区",
                blockId = block.id,
            )
            "> [Tagloom 未知 Block：${MarkdownInlineCodec.escapePlain(block.type)}]"
        }
    }

    /**
     * 将 BaseTable 有损导出为 GFM 表格。
     *
     * 稳定 Row/Column ID、局部列类型、选项和 RichText 样式不会进入 GFM，因此始终记录能力差异。
     *
     * @param content BaseTable 内容。
     * @param mode 当前 Markdown 模式。
     * @param losses 可追加能力差异。
     * @param blockId 所属 Block ID。
     * @return GFM 表格文本。
     */
    private fun exportBaseTable(
        content: BaseTableBlockContent,
        mode: MarkdownExportMode,
        losses: MutableList<MarkdownCapabilityLoss>,
        blockId: BlockId,
    ): String {
        losses += MarkdownCapabilityLoss(
            code = "base_table_gfm_lossy",
            message = "GFM 表格不保留稳定行列 ID、局部类型、列选项和删除历史",
            blockId = blockId,
        )
        val columns = content.columns.filterNot(BaseTableColumn::deleted)
        if (columns.isEmpty()) {
            return content.title.plainText.takeIf(String::isNotBlank)
                ?.let { title -> "**${MarkdownInlineCodec.escapePlain(title)}**" }
                .orEmpty()
        }
        val header = columns.joinToString(prefix = "| ", separator = " | ", postfix = " |") { column ->
            escapeTableCell(exportInline(column.name, mode, losses, blockId).ifBlank { " " })
        }
        val separator = columns.joinToString(prefix = "| ", separator = " | ", postfix = " |") { "---" }
        val rows = content.rows.filterNot(BaseTableRow::deleted).map { row ->
            columns.joinToString(prefix = "| ", separator = " | ", postfix = " |") { column ->
                val value = when (val cell = row.cells[column.columnId]) {
                    is BaseTableTextCell -> exportInline(cell.richText, mode, losses, blockId)
                    is BaseTableScalarCell -> cell.value
                    is BaseTableBooleanCell -> if (cell.value) "true" else "false"
                    else -> ""
                }
                escapeTableCell(value)
            }
        }
        return buildList {
            content.title.plainText.takeIf(String::isNotBlank)?.let { title ->
                add("**${MarkdownInlineCodec.escapePlain(title)}**")
                add("")
            }
            add(header)
            add(separator)
            addAll(rows)
        }.joinToString("\n")
    }

    /**
     * 转义 GFM 单元格中的管道和换行。
     *
     * @param value 已渲染单元格文本。
     * @return 单行 GFM 单元格内容。
     */
    private fun escapeTableCell(value: String): String = value.replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>")

    /**
     * 导出 RichText Run 的常见 Mark 与扩展加注锚点。
     *
     * @param richText 待导出富文本。
     * @param mode 当前导出模式。
     * @param losses 可追加能力差异。
     * @param blockId 所属 Block ID。
     * @return Markdown 行内文本。
     */
    private fun exportInline(
        richText: RichText,
        mode: MarkdownExportMode,
        losses: MutableList<MarkdownCapabilityLoss>,
        blockId: BlockId,
    ): String = richText.runs.joinToString(separator = "") { run ->
        var rendered = MarkdownInlineCodec.escapePlain(run.text)
        run.marks.sortedBy(InlineMark::type).forEach { mark ->
            rendered = when (mark.type) {
                InlineMarkTypes.CODE -> "`${run.text.replace("`", "\\`")}`"
                InlineMarkTypes.BOLD -> "**$rendered**"
                InlineMarkTypes.ITALIC -> "*$rendered*"
                InlineMarkTypes.STRIKETHROUGH -> "~~$rendered~~"
                InlineMarkTypes.LINK -> "[$rendered](${mark.attributes["href"].orEmpty()})"
                else -> {
                    losses += MarkdownCapabilityLoss(
                        code = "inline_mark_${mark.type}",
                        message = "Markdown 未无损表达行内样式 ${mark.type}",
                        blockId = blockId,
                    )
                    rendered
                }
            }
        }
        if (run.annotationIds.isNotEmpty()) {
            if (mode == MarkdownExportMode.TAGLOOM_EXTENDED) {
                run.annotationIds.fold(rendered) { text, annotationId -> "{{${annotationId.value}|$text}}" }
            } else {
                losses += MarkdownCapabilityLoss(
                    code = "annotation_anchor_omitted",
                    message = "标准 Markdown 已移除行内加注锚点",
                    blockId = blockId,
                )
                rendered
            }
        } else {
            rendered
        }
    }

    /**
     * 收集 Block 中全部行内加注锚点。
     *
     * @param block 待扫描 Block。
     * @return 去重前的 AnnotationId 列表。
     */
    private fun annotationIds(block: Block) = when (val content = block.content) {
        is RichTextBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        is HeadingBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        is TodoBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        is AttachmentBlockContent -> content.caption.runs.flatMap(InlineRun::annotationIds)
        is TabBlockContent -> content.title.runs.flatMap(InlineRun::annotationIds)
        is BaseTableBlockContent -> buildList {
            addAll(content.title.runs.flatMap(InlineRun::annotationIds))
            addAll(content.columns.flatMap { column -> column.name.runs.flatMap(InlineRun::annotationIds) })
            addAll(
                content.rows.flatMap { row ->
                    row.cells.values.filterIsInstance<BaseTableTextCell>()
                        .flatMap { cell -> cell.richText.runs.flatMap(InlineRun::annotationIds) }
                },
            )
        }
        else -> emptyList()
    }
}
