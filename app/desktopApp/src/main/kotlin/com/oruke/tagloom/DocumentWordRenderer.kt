package com.oruke.tagloom

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import java.io.ByteArrayOutputStream
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun

/**
 * 使用 Apache POI 生成可继续编辑的 Office Open XML Word 文档。
 */
internal object DocumentWordRenderer {
    /**
     * 把 Note 编码为真实 DOCX 压缩包。
     *
     * @param note 待导出的权威 Note payload。
     * @return 可由 Word、LibreOffice 打开的 DOCX 字节。
     */
    fun render(note: Note): ByteArray {
        val output = ByteArrayOutputStream()
        XWPFDocument().use { document ->
            document.properties.coreProperties.title = note.title.ifBlank { "无标题" }
            document.createParagraph().apply {
                style = "Title"
                createRun().apply {
                    isBold = true
                    fontSize = 24
                    setText(note.title.ifBlank { "无标题" })
                }
            }
            note.blocks.filterNot(Block::deleted).forEach { block -> appendBlock(document, block) }
            appendAnnotations(document, note)
            document.write(output)
        }
        return output.toByteArray()
    }

    /**
     * 将一个 Block 转换为 Word 段落。
     *
     * @param document 当前 Word 文档。
     * @param block 待输出的活动 Block。
     */
    private fun appendBlock(
        document: XWPFDocument,
        block: Block,
    ) {
        when (val content = block.content) {
            is HeadingBlockContent -> document.createParagraph().apply {
                style = "Heading${content.level.coerceIn(1, 6)}"
                appendRichText(content.richText)
            }

            is TodoBlockContent -> document.createParagraph().apply {
                createRun().setText(if (content.checked) "☑ " else "☐ ")
                appendRichText(content.richText)
            }

            is RichTextBlockContent -> document.createParagraph().apply {
                val prefix = when (block.type) {
                    BlockTypes.BULLETED_LIST -> "• "
                    BlockTypes.NUMBERED_LIST -> "1. "
                    BlockTypes.QUOTE -> "❝ "
                    else -> ""
                }
                if (prefix.isNotEmpty()) createRun().setText(prefix)
                appendRichText(content.richText)
            }

            is CodeBlockContent -> document.createParagraph().apply {
                createRun().apply {
                    fontFamily = "Consolas"
                    fontSize = 10
                    setText(content.text)
                }
            }

            DividerBlockContent -> document.createParagraph().createRun().setText("────────────────────────────────────────")
            is AttachmentBlockContent -> document.createParagraph().createRun().setText(
                "附件：${content.caption.plainText.ifBlank { content.fileName }}",
            )

            is NoteReferenceBlockContent -> document.createParagraph().createRun().setText(
                "Tagloom 笔记引用：${content.noteId.value}",
            )

            is UnknownBlockContent -> document.createParagraph().createRun().setText("未支持的 Block：${block.type}")
        }
    }

    /**
     * 按 Run 保留 RichText 的常用排版 Mark。
     *
     * @param richText 待输出的规范化 RichText。
     */
    private fun XWPFParagraph.appendRichText(richText: RichText) {
        richText.runs.forEach { inlineRun ->
            createRun().apply {
                applyInlineMarks(inlineRun)
                setText(inlineRun.text)
            }
            if (inlineRun.annotationIds.isNotEmpty()) {
                createRun().apply {
                    setText("※")
                    setSubscript(org.apache.poi.xwpf.usermodel.VerticalAlign.SUPERSCRIPT)
                }
            }
        }
    }

    /**
     * 把一个 InlineRun 的样式映射到 Word Run。
     *
     * @param inlineRun 文字及其结构化 Mark。
     */
    private fun XWPFRun.applyInlineMarks(inlineRun: InlineRun) {
        inlineRun.marks.forEach { mark ->
            when (mark.type) {
                InlineMarkTypes.BOLD -> isBold = true
                InlineMarkTypes.ITALIC -> isItalic = true
                InlineMarkTypes.UNDERLINE -> underline = UnderlinePatterns.SINGLE
                InlineMarkTypes.STRIKETHROUGH -> isStrikeThrough = true
                InlineMarkTypes.CODE -> fontFamily = "Consolas"
                InlineMarkTypes.LINK -> {
                    color = "396B9A"
                    underline = UnderlinePatterns.SINGLE
                }

                InlineMarkTypes.TEXT_COLOR -> EXPORT_COLORS[mark.attributes[InlineMarkAttributes.COLOR_TOKEN]]
                    ?.let { token -> color = token.textHex.removePrefix("#").uppercase() }

                InlineMarkTypes.HIGHLIGHT -> EXPORT_COLORS[mark.attributes[InlineMarkAttributes.COLOR_TOKEN]]
                    ?.let { token -> setTextHighlightColor(token.wordHighlightName) }
            }
        }
    }

    /**
     * 在正文后输出仍被 Note 保存的独立加注。
     *
     * @param document 当前 Word 文档。
     * @param note 待导出的 Note。
     */
    private fun appendAnnotations(
        document: XWPFDocument,
        note: Note,
    ) {
        if (note.annotations.isEmpty()) return
        document.createParagraph().apply {
            style = "Heading2"
            createRun().setText("加注")
        }
        note.annotations.values.forEachIndexed { index, annotation ->
            document.createParagraph().createRun().setText("${index + 1}. ${annotation.body}")
        }
    }
}
