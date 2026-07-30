package com.oruke.tagloom

import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.platform.DocumentExportFormat

/**
 * 在 JVM Desktop 进程内把已解锁 Note 编码为用户选择的文档格式。
 *
 * 明文字节只在导出调用期间存在，写入完成后由调用方清零。
 */
internal object DesktopDocumentRenderer {
    /**
     * 将 Note 渲染为目标格式的完整文件字节。
     *
     * @param note 当前已解锁会话中的权威 Note payload。
     * @param format 用户选择的文档格式。
     * @param labels 导出内容使用的当前语言静态文本。
     * @return 可以直接写入目标文件的完整字节。
     */
    fun render(
        note: Note,
        format: DocumentExportFormat,
        labels: DocumentExportLabels,
    ): ByteArray = when (format) {
        DocumentExportFormat.MARKDOWN -> MarkdownCodec
            .export(note, MarkdownExportMode.TAGLOOM_EXTENDED)
            .markdown
            .encodeToByteArray()

        DocumentExportFormat.HTML -> DocumentHtmlRenderer.render(note, labels)
        DocumentExportFormat.PDF -> DocumentPdfRenderer.render(note, labels)
        DocumentExportFormat.WORD -> DocumentWordRenderer.render(note, labels)
    }
}
