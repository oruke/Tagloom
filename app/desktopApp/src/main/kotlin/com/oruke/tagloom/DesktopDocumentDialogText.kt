package com.oruke.tagloom

import com.oruke.tagloom.platform.DocumentExportFormat
import org.jetbrains.compose.resources.getString
import tagloom.app.shared.generated.resources.*

/**
 * 提供桌面文档导出文件选择器使用的共享本地化文案。
 *
 * 文件选择器不处于 Compose 组合上下文中，因此这里通过 suspend 资源读取 API 将共享资源
 * 转换为 Swing 可使用的普通字符串，不在导出 Gateway 中复制语言分支。
 */
internal object DesktopDocumentDialogText {
    /**
     * 返回导出指定格式时的窗口标题。
     *
     * @param format 用户选择的目标文档格式。
     * @return 当前 Locale 下的文件选择器标题。
     */
    suspend fun exportTitle(format: DocumentExportFormat): String =
        getString(Res.string.desktop_dialog_export_document, formatDisplayName(format))

    /**
     * 返回指定导出格式在 Swing 过滤器中的可见说明。
     *
     * @param format 用户选择的目标文档格式。
     * @return 当前 Locale 下的文件类型说明。
     */
    suspend fun fileTypeDescription(format: DocumentExportFormat): String = when (format) {
        DocumentExportFormat.MARKDOWN -> getString(Res.string.desktop_file_type_markdown)
        DocumentExportFormat.HTML -> getString(Res.string.desktop_file_type_html)
        DocumentExportFormat.PDF -> getString(Res.string.desktop_file_type_pdf)
        DocumentExportFormat.WORD -> getString(Res.string.desktop_file_type_word)
    }

    /**
     * 返回导出格式在当前 Locale 下的显示名称。
     *
     * @param format 用户选择的目标文档格式。
     * @return 当前 Locale 下的格式名称。
     */
    private suspend fun formatDisplayName(format: DocumentExportFormat): String = when (format) {
        DocumentExportFormat.MARKDOWN -> getString(Res.string.editor_export_markdown_name)
        DocumentExportFormat.HTML -> getString(Res.string.editor_export_html_name)
        DocumentExportFormat.PDF -> getString(Res.string.editor_export_pdf_name)
        DocumentExportFormat.WORD -> getString(Res.string.editor_export_word_name)
    }
}
