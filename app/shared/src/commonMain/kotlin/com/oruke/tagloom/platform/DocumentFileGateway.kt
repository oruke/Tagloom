package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.Note

/**
 * 区分编辑器直接支持的文档导出格式。
 */
enum class DocumentExportFormat {
    /** 保留 Tagloom 扩展加注语义的 UTF-8 Markdown。 */
    MARKDOWN,

    /** 可在浏览器中独立打开的 UTF-8 HTML。 */
    HTML,

    /** 适合阅读和打印的 PDF。 */
    PDF,

    /** 可继续编辑的 Office Open XML Word 文档。 */
    WORD,
}

/**
 * 描述一次平台文档导出请求。
 */
data class DocumentExportRequest(
    /** 不含路径和扩展名的脱敏建议文件名。 */
    val suggestedBaseName: String,
    /** 当前已解锁会话中的完整 Note payload。 */
    val note: Note,
    /** 用户选择的目标格式。 */
    val format: DocumentExportFormat,
)

/**
 * 抽象平台文件选择器与文档编码能力，避免共享 UI 依赖 JVM、Android 或 iOS 文件 API。
 */
interface DocumentFileGateway {
    /**
     * 请求用户选择目标位置并原子写入指定格式文档。
     *
     * @param request 不含本地路径的导出请求。
     * @return 用户确认并完成写入时为 true，取消选择时为 false。
     */
    suspend fun exportDocument(request: DocumentExportRequest): Boolean
}
