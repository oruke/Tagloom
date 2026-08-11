package com.oruke.tagloom

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import org.apache.fontbox.ttf.TrueTypeCollection
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 使用 PDFBox 和系统可用的 CJK 字体生成真实 PDF 文档。
 */
internal object DocumentPdfRenderer {
    /**
     * 把 Note 渲染为分页、可选择文字的 PDF。
     *
     * @param note 待导出的权威 Note payload。
     * @param labels 导出内容使用的当前语言静态文本。
     * @return 以 `%PDF` 开头的完整 PDF 文件字节。
     */
    fun render(
        note: Note,
        labels: DocumentExportLabels,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            loadExportFont(document).use { loadedFont ->
                val writer = PdfPageWriter(document, loadedFont.font)

                // 标题与正文分阶段写入，保证分页发生时每个 Block 仍保持稳定间距。
                writer.writeParagraph(note.title.ifBlank { labels.untitled }, TITLE_FONT_SIZE, 0f, TITLE_SPACING)
                note.blocks.filterNot(Block::deleted).forEach { block -> writer.writeBlock(block, labels) }
                document.save(output)
            }
        }
        return output.toByteArray()
    }

    /**
     * 从常见桌面系统字体目录加载可嵌入的 CJK TrueType 字体。
     *
     * @param document 当前 PDF 文档，字体必须绑定该文档。
     * @return 支持中文正文的嵌入字体及其延迟关闭资源。
     */
    private fun loadExportFont(document: PDDocument): LoadedPdfFont {
        val windowsCollections = System.getenv("WINDIR")?.let { root ->
            listOf(Paths.get(root, "Fonts", "msyh.ttc"))
        }.orEmpty()
        windowsCollections.filter(Files::isRegularFile).forEach { path ->
            loadCollectionFont(document, path)?.let { loaded -> return loaded }
        }
        val windowsFonts = System.getenv("WINDIR")?.let { root ->
            listOf(
                Paths.get(root, "Fonts", "NotoSansSC-VF.ttf"),
                Paths.get(root, "Fonts", "simhei.ttf"),
                Paths.get(root, "Fonts", "Deng.ttf"),
            )
        }.orEmpty()
        val candidates = windowsFonts + listOf(
            Paths.get("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            Paths.get("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Paths.get("/System/Library/Fonts/PingFang.ttc"),
        )
        val loaded = candidates
            .firstOrNull(Files::isRegularFile)
            ?.let { path -> LoadedPdfFont(PDType0Font.load(document, path.toFile())) }
        return loaded ?: error("未找到覆盖常用中文字符的 PDF 导出字体")
    }

    /**
     * 从 TrueType Collection 选择覆盖常用中文字符的字体，并保持集合到 PDF 保存完成。
     *
     * @param document 当前 PDF 文档。
     * @param path TTC 字体集合路径。
     * @return 可用字体和集合资源；集合中没有合适字体时为空。
     */
    private fun loadCollectionFont(
        document: PDDocument,
        path: Path,
    ): LoadedPdfFont? {
        val collection = TrueTypeCollection(path.toFile())
        var selected: TrueTypeFont? = null
        collection.processAllFonts { font ->
            if (selected == null) selected = font
        }
        val trueTypeFont = selected
        if (trueTypeFont == null) {
            collection.close()
            return null
        }
        val font = PDType0Font.load(document, trueTypeFont, true)
        return LoadedPdfFont(font, collection)
    }

    /**
     * 验证字体覆盖导出界面常用的简体中文字符。
     *
     * @return 全部探针字符均有字形时为 true。
     */
    /** PDF 标题字号。 */
    private const val TITLE_FONT_SIZE: Float = 24f

    /** PDF 标题后的间距。 */
    private const val TITLE_SPACING: Float = 14f

}

/**
 * 保存 PDF 字体及其在文档保存后才能关闭的可选底层集合。
 */
private data class LoadedPdfFont(
    /** 已注册到当前 PDF 文档的字体。 */
    val font: PDType0Font,
    /** subset 写入完成前必须保持打开的 TTC 集合。 */
    val resource: AutoCloseable? = null,
) : AutoCloseable {
    /** 在 PDF 保存完成后关闭底层 TTC 资源。 */
    override fun close() {
        resource?.close()
    }
}

/**
 * 管理 PDF 当前页和纵向游标，集中处理换行与分页。
 */
private class PdfPageWriter(
    /** 正在写入的 PDF 文档。 */
    private val document: PDDocument,
    /** 已嵌入且支持正文字符的字体。 */
    private val font: PDType0Font,
) {
    /** 当前接收新行的页面。 */
    private var page: PDPage = newPage()

    /** 当前行基线的纵坐标。 */
    private var cursorY: Float = page.mediaBox.height - PAGE_MARGIN

    /**
     * 按类型写入一个活动 Block。
     *
     * @param block 待输出的 Block。
     * @param labels 导出内容使用的当前语言静态文本。
     */
    fun writeBlock(
        block: Block,
        labels: DocumentExportLabels,
    ) {
        when (val content = block.content) {
            is HeadingBlockContent -> writeParagraph(
                content.richText.plainText,
                headingFontSize(content.level),
                0f,
                BLOCK_SPACING,
            )

            is TodoBlockContent -> writeParagraph(
                "${if (content.checked) "☑" else "☐"} ${content.richText.plainText}",
                BODY_FONT_SIZE,
                LIST_INDENT,
                SMALL_SPACING,
            )

            is RichTextBlockContent -> {
                val prefix = when (block.type) {
                    BlockTypes.BULLETED_LIST -> "• "
                    BlockTypes.NUMBERED_LIST -> "1. "
                    BlockTypes.QUOTE -> "❝ "
                    else -> ""
                }
                writeParagraph(prefix + content.richText.plainText, BODY_FONT_SIZE, 0f, SMALL_SPACING)
            }

            is CodeBlockContent -> writeParagraph(content.text, CODE_FONT_SIZE, CODE_INDENT, BLOCK_SPACING)
            DividerBlockContent -> writeParagraph("────────────────────────────────────────", BODY_FONT_SIZE, 0f, BLOCK_SPACING)
            is AttachmentBlockContent -> writeParagraph(
                labels.attachmentPrefix + content.caption.plainText.ifBlank { content.fileName },
                BODY_FONT_SIZE,
                0f,
                SMALL_SPACING,
            )

            is NoteReferenceBlockContent -> writeParagraph(
                labels.noteReferencePrefix + content.noteId.value,
                BODY_FONT_SIZE,
                0f,
                SMALL_SPACING,
            )

            is com.oruke.tagloom.core.model.DatabaseBlockContent -> writeParagraph(
                labels.databaseReferencePrefix + content.databaseId.value,
                BODY_FONT_SIZE,
                0f,
                SMALL_SPACING,
            )

            is com.oruke.tagloom.core.model.BaseTableBlockContent -> {
                if (content.title.plainText.isNotBlank()) {
                    writeParagraph(content.title.plainText, headingFontSize(3), 0f, SMALL_SPACING)
                }
                val columns = content.columns.filterNot { column -> column.deleted }.sortedBy { column -> column.orderKey }
                writeParagraph(columns.joinToString(" | ") { column -> column.name.plainText }, CODE_FONT_SIZE, 0f, SMALL_SPACING)
                content.rows.filterNot { row -> row.deleted }.sortedBy { row -> row.orderKey }.forEach { row ->
                    val line = columns.joinToString(" | ") { column ->
                        when (val cell = row.cells[column.columnId]) {
                            is com.oruke.tagloom.core.model.BaseTableTextCell -> cell.richText.plainText
                            is com.oruke.tagloom.core.model.BaseTableScalarCell -> cell.value
                            is com.oruke.tagloom.core.model.BaseTableBooleanCell -> if (cell.value) "✓" else ""
                            else -> ""
                        }
                    }
                    writeParagraph(line, CODE_FONT_SIZE, 0f, SMALL_SPACING)
                }
            }

            is com.oruke.tagloom.core.model.TabBlockContent -> writeParagraph(
                content.title.plainText,
                headingFontSize(2),
                0f,
                SMALL_SPACING,
            )

            com.oruke.tagloom.core.model.TabsBlockContent -> Unit
            is UnknownBlockContent -> writeParagraph(
                labels.unknownBlockPrefix + block.type,
                BODY_FONT_SIZE,
                0f,
                SMALL_SPACING,
            )
        }
    }

    /**
     * 写入一个可自动换行和跨页的段落。
     *
     * @param text 原始段落文本。
     * @param fontSize 当前段落字号。
     * @param indent 左侧额外缩进。
     * @param spacingAfter 段落后的垂直间距。
     */
    fun writeParagraph(
        text: String,
        fontSize: Float,
        indent: Float,
        spacingAfter: Float,
    ) {
        val safeText = text.ifBlank { " " }
        val lines = wrapText(safeText, fontSize, page.mediaBox.width - PAGE_MARGIN * 2 - indent)
        lines.forEach { line ->
            ensureLineSpace(fontSize)
            drawLine(line.ifEmpty { " " }, fontSize, indent)
            cursorY -= lineHeight(fontSize)
        }
        cursorY -= spacingAfter
    }

    /**
     * 把段落按显式换行和可用宽度拆为 PDF 行。
     *
     * @param text 已替换缺失字形的文本。
     * @param fontSize 当前字号。
     * @param maxWidth 单行最大宽度。
     * @return 至少包含一项的行列表。
     */
    private fun wrapText(
        text: String,
        fontSize: Float,
        maxWidth: Float,
    ): List<String> = text.split('\n').flatMap { sourceLine ->
        if (sourceLine.isEmpty()) {
            listOf("")
        } else {
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            sourceLine.codePoints().forEach { codePoint ->
                val character = String(Character.toChars(codePoint))
                val candidate = current.toString() + character
                if (current.isNotEmpty() && textWidth(candidate, fontSize) > maxWidth) {
                    lines += current.toString()
                    current.clear()
                }
                current.append(character)
            }
            lines + current.toString()
        }
    }.ifEmpty { listOf("") }

    /**
     * 在当前页面绘制一行文字。
     *
     * @param line 已适配字体的单行文本。
     * @param fontSize 字号。
     * @param indent 左侧额外缩进。
     */
    private fun drawLine(
        line: String,
        fontSize: Float,
        indent: Float,
    ) {
        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true,
        ).use { stream ->
            stream.beginText()
            stream.setFont(font, fontSize)
            stream.newLineAtOffset(PAGE_MARGIN + indent, cursorY)
            stream.showText(line)
            stream.endText()
        }
    }

    /**
     * 在当前页空间不足时切换到新页面。
     *
     * @param fontSize 下一行字号。
     */
    private fun ensureLineSpace(fontSize: Float) {
        if (cursorY - lineHeight(fontSize) >= PAGE_MARGIN) return
        page = newPage()
        cursorY = page.mediaBox.height - PAGE_MARGIN
    }

    /**
     * 创建并注册一张 A4 页面。
     *
     * @return 已加入文档的新页面。
     */
    private fun newPage(): PDPage = PDPage(PDRectangle.A4).also(document::addPage)

    /**
     * 计算一段文字在当前字体字号下的宽度。
     *
     * @param text 待测量文本。
     * @param fontSize 字号。
     * @return PDF 用户空间中的宽度。
     */
    private fun textWidth(
        text: String,
        fontSize: Float,
    ): Float = font.getStringWidth(text) / 1000f * fontSize

    /**
     * 返回不同字号的舒适行高。
     *
     * @param fontSize 字号。
     * @return PDF 用户空间中的行高。
     */
    private fun lineHeight(fontSize: Float): Float = fontSize * 1.55f

    /**
     * 根据标题层级返回逐级收敛的字号。
     *
     * @param level Markdown 语义标题层级。
     * @return 适合正文层次的字号。
     */
    private fun headingFontSize(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 19f
        2 -> 17f
        3 -> 15f
        4 -> 14f
        else -> 13f
    }

    /** 页面四周留白。 */
    private companion object {
        /** A4 页面边距。 */
        const val PAGE_MARGIN: Float = 58f

        /** 普通正文字号。 */
        const val BODY_FONT_SIZE: Float = 11f

        /** 代码块字号。 */
        const val CODE_FONT_SIZE: Float = 9.5f

        /** 列表缩进。 */
        const val LIST_INDENT: Float = 12f

        /** 代码块缩进。 */
        const val CODE_INDENT: Float = 14f

        /** 普通 Block 后间距。 */
        const val SMALL_SPACING: Float = 4f

        /** 强分隔 Block 后间距。 */
        const val BLOCK_SPACING: Float = 9f
    }
}
