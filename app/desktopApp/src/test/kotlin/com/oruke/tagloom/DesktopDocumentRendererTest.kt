package com.oruke.tagloom

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.platform.DocumentExportFormat
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * 验证桌面导出生成真实 Markdown、HTML、PDF 和 DOCX 文件结构。
 */
class DesktopDocumentRendererTest {
    /**
     * 验证四种格式均可被对应解析器读取，并保留中文正文。
     */
    @Test
    fun rendersPortableDocumentFormats() {
        val note = sampleNote()
        val markdown = DesktopDocumentRenderer.render(note, DocumentExportFormat.MARKDOWN)
        val html = DesktopDocumentRenderer.render(note, DocumentExportFormat.HTML)
        val pdf = DesktopDocumentRenderer.render(note, DocumentExportFormat.PDF)
        val word = DesktopDocumentRenderer.render(note, DocumentExportFormat.WORD)

        assertTrue(markdown.decodeToString().contains("**权威状态**"))
        assertTrue(html.decodeToString().contains("<!doctype html>"))
        assertTrue(html.decodeToString().contains("color:#396b9a"))
        assertContentEquals("%PDF".encodeToByteArray(), pdf.copyOfRange(0, 4))
        Loader.loadPDF(pdf).use { document ->
            val extracted = PDFTextStripper().getText(document)
            assertTrue(extracted.contains("中文导出验证"), "PDF 回读文本：$extracted")
        }
        assertContentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()), word.copyOfRange(0, 2))
        XWPFDocument(ByteArrayInputStream(word)).use { document ->
            assertTrue(document.paragraphs.joinToString("\n") { paragraph -> paragraph.text }.contains("中文导出验证"))
        }
    }

    /**
     * 构造同时覆盖标题、RichText 颜色和代码块的脱敏测试 Note。
     *
     * @return 可供全部导出器使用的 Note。
     */
    private fun sampleNote(): Note = Note(
        title = "中文导出验证",
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = BlockId("heading-export"),
                type = BlockTypes.HEADING,
                orderKey = "0000000000",
                content = HeadingBlockContent(2, RichText(listOf(InlineRun("文档能力")))),
            ),
            Block(
                id = BlockId("paragraph-export"),
                type = BlockTypes.PARAGRAPH,
                orderKey = "0000000001",
                content = RichTextBlockContent(
                    RichText(
                        listOf(
                            InlineRun("权威状态", marks = listOf(InlineMark(InlineMarkTypes.BOLD))),
                            InlineRun(
                                " 可移植",
                                marks = listOf(
                                    InlineMark(
                                        InlineMarkTypes.TEXT_COLOR,
                                        mapOf(InlineMarkAttributes.COLOR_TOKEN to "blue"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            Block(
                id = BlockId("code-export"),
                type = BlockTypes.CODE,
                orderKey = "0000000002",
                content = CodeBlockContent("kotlin", "val answer = 42"),
            ),
        ),
    )
}
