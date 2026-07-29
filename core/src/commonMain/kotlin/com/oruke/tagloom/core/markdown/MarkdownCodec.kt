package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note

/**
 * 区分最大兼容标准 Markdown 与保留 Tagloom 加注语义的扩展格式。
 */
enum class MarkdownExportMode {
    /** 只输出常见 Markdown，无法表达的能力进入损失清单。 */
    STANDARD,

    /** 使用 `{{annotation-id|text}}` 和脚注定义保留加注。 */
    TAGLOOM_EXTENDED,
}

/**
 * 描述 Markdown 无法无损表达的单项能力差异。
 */
data class MarkdownCapabilityLoss(
    /** 稳定机器可读能力代码。 */
    val code: String,
    /** 面向用户的中文说明。 */
    val message: String,
    /** 发生差异的 Block；笔记级差异为 null。 */
    val blockId: BlockId? = null,
)

/**
 * 返回 Markdown 文本和导出前必须展示的能力差异。
 */
data class MarkdownExportResult(
    /** 生成的 Markdown 文本。 */
    val markdown: String,
    /** 去重后的有损能力清单。 */
    val capabilityLosses: List<MarkdownCapabilityLoss>,
)

/**
 * 配置 Markdown 导入时标题、时间和稳定 Block ID 的生成方式。
 */
class MarkdownImportOptions(
    /** 文档没有一级标题时使用的笔记标题。 */
    val defaultTitle: String,
    /** 是否把第一个一级标题提升为 Note 标题而不保留为 Block。 */
    val firstHeadingAsTitle: Boolean = true,
    /** 新建加注使用的 UTC ISO-8601 时间文本。 */
    val importedAt: String,
    /** 根据导入顺序生成稳定且不透明 Block ID。 */
    val blockIdFactory: (Int) -> BlockId,
)

/**
 * 返回导入得到的 Note payload 与无法完全解释的语法警告。
 */
data class MarkdownImportResult(
    /** 完成 RichText 规范化的笔记 payload。 */
    val note: Note,
    /** 不阻止导入但需要用户复核的警告。 */
    val warnings: List<String>,
)

/**
 * 在 Tagloom Note/Block/RichText 与 Markdown 之间提供稳定公共入口。
 */
object MarkdownCodec {
    /**
     * 把 Note 导出为标准或 Tagloom 扩展 Markdown。
     *
     * @param note 待导出的权威 Note payload。
     * @param mode 标准或保留加注的扩展模式。
     * @return Markdown 与能力差异清单。
     */
    fun export(
        note: Note,
        mode: MarkdownExportMode,
    ): MarkdownExportResult = MarkdownExporter.export(note, mode)

    /**
     * 把 Markdown 导入为 Note、Block、RichText 和独立 Annotation。
     *
     * @param markdown 待导入 Markdown 文本。
     * @param options 标题、时间和 ID 生成策略。
     * @return 可进入 NoteValidator 的规范化 Note 与警告。
     */
    fun import(
        markdown: String,
        options: MarkdownImportOptions,
    ): MarkdownImportResult = MarkdownImporter.import(markdown, options)
}
