package com.oruke.tagloom

import org.jetbrains.compose.resources.getString
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.desktop_export_annotations_title
import tagloom.app.shared.generated.resources.desktop_export_attachment_prefix
import tagloom.app.shared.generated.resources.desktop_export_database_reference_prefix
import tagloom.app.shared.generated.resources.desktop_export_html_language_tag
import tagloom.app.shared.generated.resources.desktop_export_note_reference_prefix
import tagloom.app.shared.generated.resources.desktop_export_unknown_block_prefix
import tagloom.app.shared.generated.resources.desktop_export_untitled

/**
 * 保存 HTML、PDF 与 Word 导出内容需要的当前语言静态文本。
 *
 * 渲染器只接收这个纯 Kotlin 值对象，因此不依赖 Compose 运行时；本机文件网关在用户发起导出
 * 时读取共享资源，确保导出文件与当前界面使用相同的语言。
 */
internal data class DocumentExportLabels(
    /** HTML `lang` 属性使用的 BCP 47 语言标签。 */
    val htmlLanguageTag: String,
    /** Note 标题为空时使用的可见标题。 */
    val untitled: String,
    /** 附件名称前的本地化前缀，已包含适合当前语言的分隔符。 */
    val attachmentPrefix: String,
    /** Note 引用 ID 前的本地化前缀，已包含适合当前语言的分隔符。 */
    val noteReferencePrefix: String,
    /** Database 引用 ID 前的本地化前缀，说明动态投影不会复制进静态导出。 */
    val databaseReferencePrefix: String,
    /** 未支持 Block 类型前的本地化前缀，已包含适合当前语言的分隔符。 */
    val unknownBlockPrefix: String,
    /** 独立加注列表的本地化标题。 */
    val annotationsTitle: String,
)

/**
 * 从共享 Compose 资源加载桌面导出内容使用的文案。
 */
internal object DesktopDocumentExportLabels {
    /**
     * 读取当前 Locale 的导出内容文案。
     *
     * @return 与当前共享 UI 相同语言的完整导出文案。
     */
    suspend fun load(): DocumentExportLabels = DocumentExportLabels(
        htmlLanguageTag = getString(Res.string.desktop_export_html_language_tag),
        untitled = getString(Res.string.desktop_export_untitled),
        attachmentPrefix = getString(Res.string.desktop_export_attachment_prefix),
        noteReferencePrefix = getString(Res.string.desktop_export_note_reference_prefix),
        databaseReferencePrefix = getString(Res.string.desktop_export_database_reference_prefix),
        unknownBlockPrefix = getString(Res.string.desktop_export_unknown_block_prefix),
        annotationsTitle = getString(Res.string.desktop_export_annotations_title),
    )
}
