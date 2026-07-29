package com.oruke.tagloom

import com.oruke.tagloom.platform.DocumentExportFormat
import com.oruke.tagloom.platform.DocumentExportRequest
import com.oruke.tagloom.platform.DocumentFileGateway
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * 使用 Swing 文件选择器和 JVM 文档渲染器实现桌面多格式导出。
 */
class DesktopDocumentFileGateway : DocumentFileGateway {
    /**
     * 选择目标文件，在 IO 调度器编码并以同目录原子替换发布。
     *
     * @param request 当前 Note 与目标格式。
     * @return 用户完成保存时为 true，取消选择时为 false。
     */
    override suspend fun exportDocument(request: DocumentExportRequest): Boolean {
        val selected = chooseSavePath(request) ?: return false
        val target = selected.withRequiredExtension(request.format)
        val bytes = withContext(Dispatchers.IO) {
            DesktopDocumentRenderer.render(request.note, request.format)
        }
        withContext(Dispatchers.IO) {
            writeAtomically(target, bytes)
        }
        bytes.fill(0)
        return true
    }

    /**
     * 在 Swing 事件线程选择导出位置。
     *
     * @param request 含脱敏建议基础名和目标格式的请求。
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseSavePath(request: DocumentExportRequest): Path? = withContext(Dispatchers.Swing) {
        val descriptor = request.format.descriptor()
        val chooser = JFileChooser().apply {
            dialogTitle = "导出 ${descriptor.displayName}"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter(descriptor.filterDescription, descriptor.extension)
            selectedFile = java.io.File("${request.suggestedBaseName}.${descriptor.extension}")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }

    /**
     * 在目标同目录写入临时文件并原子替换，避免导出中断留下半文件。
     *
     * @param target 最终用户文件。
     * @param bytes 完整编码结果。
     */
    private fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        val parent = target.parent ?: error("导出目标缺少父目录")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".tagloom-export-", ".tmp")
        try {
            Files.write(temporary, bytes)
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 为用户未输入扩展名的目标补齐格式后缀。
     *
     * @param format 目标文档格式。
     * @return 保留用户目录且带正确后缀的路径。
     */
    private fun Path.withRequiredExtension(format: DocumentExportFormat): Path {
        val extension = format.descriptor().extension
        return if (fileName.toString().endsWith(".$extension", ignoreCase = true)) {
            this
        } else {
            resolveSibling("${fileName}.$extension")
        }
    }
}

/**
 * 保存文件选择器需要的格式名称、扩展名和过滤说明。
 */
private data class DesktopExportFormatDescriptor(
    /** 面向用户的格式名称。 */
    val displayName: String,
    /** 不含点号的标准扩展名。 */
    val extension: String,
    /** 文件选择器过滤说明。 */
    val filterDescription: String,
)

/**
 * 返回桌面文件选择器使用的格式描述。
 *
 * @return 与格式协议一致的名称、扩展名和过滤说明。
 */
private fun DocumentExportFormat.descriptor(): DesktopExportFormatDescriptor = when (this) {
    DocumentExportFormat.MARKDOWN -> DesktopExportFormatDescriptor("Markdown", "md", "Markdown (*.md)")
    DocumentExportFormat.HTML -> DesktopExportFormatDescriptor("HTML", "html", "HTML (*.html)")
    DocumentExportFormat.PDF -> DesktopExportFormatDescriptor("PDF", "pdf", "PDF (*.pdf)")
    DocumentExportFormat.WORD -> DesktopExportFormatDescriptor("Word", "docx", "Word 文档 (*.docx)")
}
