package com.oruke.tagloom

import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent

/**
 * 把 Note 渲染为可离线打开、自包含样式且默认不执行外部脚本的 HTML。
 */
internal object DocumentHtmlRenderer {
    /**
     * 生成完整 UTF-8 HTML 文档。
     *
     * @param note 待导出的权威 Note payload。
     * @param labels 导出内容使用的当前语言静态文本。
     * @return 包含标题、Block、RichText 与加注的 HTML 字节。
     */
    fun render(
        note: Note,
        labels: DocumentExportLabels,
    ): ByteArray = buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"${escapeAttribute(labels.htmlLanguageTag)}\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
        appendLine("<title>${escapeHtml(note.title.ifBlank { labels.untitled })}</title>")
        appendLine("<style>$DOCUMENT_STYLE</style>")
        appendLine("</head>")
        appendLine("<body><main>")
        appendLine("<h1 class=\"page-title\">${escapeHtml(note.title.ifBlank { labels.untitled })}</h1>")
        note.blocks.filterNot(Block::deleted).forEach { block -> appendLine(renderBlock(block, labels)) }
        append(renderAnnotations(note, labels))
        appendLine("</main></body></html>")
    }.encodeToByteArray()

    /**
     * 按 Block 协议生成对应 HTML 结构。
     *
     * @param block 待渲染的活动 Block。
     * @param labels 导出内容使用的当前语言静态文本。
     * @return 已完成内容转义的 HTML 片段。
     */
    private fun renderBlock(
        block: Block,
        labels: DocumentExportLabels,
    ): String = when (val content = block.content) {
        is HeadingBlockContent -> {
            val level = content.level.coerceIn(1, 6)
            "<h$level>${renderRichText(content.richText)}</h$level>"
        }

        is TodoBlockContent -> "<label class=\"todo\"><input type=\"checkbox\" disabled${if (content.checked) " checked" else ""}>${renderRichText(content.richText)}</label>"
        is RichTextBlockContent -> when (block.type) {
            BlockTypes.BULLETED_LIST -> "<ul><li>${renderRichText(content.richText)}</li></ul>"
            BlockTypes.NUMBERED_LIST -> "<ol><li>${renderRichText(content.richText)}</li></ol>"
            BlockTypes.QUOTE -> "<blockquote>${renderRichText(content.richText)}</blockquote>"
            else -> "<p>${renderRichText(content.richText)}</p>"
        }

        is CodeBlockContent -> "<pre><code data-language=\"${escapeAttribute(content.language.orEmpty())}\">${escapeHtml(content.text)}</code></pre>"
        DividerBlockContent -> "<hr>"
        is AttachmentBlockContent -> {
            val label = content.caption.plainText.ifBlank { content.fileName }
            "<p class=\"attachment\">${escapeHtml(labels.attachmentPrefix + label)}</p>"
        }

        is NoteReferenceBlockContent -> {
            "<p class=\"reference\">${escapeHtml(labels.noteReferencePrefix + content.noteId.value)}</p>"
        }

        is UnknownBlockContent -> "<p class=\"unknown\">${escapeHtml(labels.unknownBlockPrefix + block.type)}</p>"
    }

    /**
     * 保留 Run 级样式和加注锚点并生成安全的行内 HTML。
     *
     * @param richText 待渲染的规范化 RichText。
     * @return 经过转义和 Mark 包裹的 HTML。
     */
    private fun renderRichText(richText: RichText): String = richText.runs.joinToString(separator = "") { run ->
        var rendered = escapeHtml(run.text)
        run.marks.forEach { mark -> rendered = applyMark(rendered, mark) }
        if (run.annotationIds.isNotEmpty()) {
            val ids = run.annotationIds.joinToString(",") { id -> id.value }
            "<span class=\"annotation-anchor\" data-annotation-ids=\"${escapeAttribute(ids)}\">$rendered<sup>※</sup></span>"
        } else {
            rendered
        }
    }

    /**
     * 将一个 Inline Mark 应用到已转义的行内片段。
     *
     * @param content 已转义且可能已被内层 Mark 包裹的 HTML。
     * @param mark 当前结构化 Mark。
     * @return 应用当前 Mark 后的 HTML。
     */
    private fun applyMark(
        content: String,
        mark: InlineMark,
    ): String = when (mark.type) {
        InlineMarkTypes.BOLD -> "<strong>$content</strong>"
        InlineMarkTypes.ITALIC -> "<em>$content</em>"
        InlineMarkTypes.UNDERLINE -> "<u>$content</u>"
        InlineMarkTypes.STRIKETHROUGH -> "<s>$content</s>"
        InlineMarkTypes.CODE -> "<code>$content</code>"
        InlineMarkTypes.LINK -> {
            val href = mark.attributes["href"].orEmpty()
            if (isSafeLinkTarget(href)) "<a href=\"${escapeAttribute(href)}\">$content</a>" else content
        }

        InlineMarkTypes.TEXT_COLOR -> colorToken(mark)?.let { token ->
            "<span style=\"color:${token.textHex}\">$content</span>"
        } ?: content

        InlineMarkTypes.HIGHLIGHT -> colorToken(mark)?.let { token ->
            "<mark style=\"background:${token.highlightHex}\">$content</mark>"
        } ?: content

        else -> content
    }

    /**
     * 输出当前 Note 中仍存在的独立加注正文。
     *
     * @param note 待导出的 Note。
     * @param labels 导出内容使用的当前语言静态文本。
     * @return 加注列表 HTML；没有加注时为空字符串。
     */
    private fun renderAnnotations(
        note: Note,
        labels: DocumentExportLabels,
    ): String {
        if (note.annotations.isEmpty()) return ""
        return buildString {
            appendLine("<section class=\"annotations\"><h2>${escapeHtml(labels.annotationsTitle)}</h2><ol>")
            note.annotations.values.forEach { annotation ->
                appendLine("<li id=\"annotation-${escapeAttribute(annotation.id.value)}\">${escapeHtml(annotation.body)}</li>")
            }
            appendLine("</ol></section>")
        }
    }

    /**
     * 判断链接目标是否属于离线文档允许的显式安全协议。
     *
     * @param target 原始链接目标。
     * @return 仅 http、https、mailto 和 Tagloom 内部引用返回 true。
     */
    private fun isSafeLinkTarget(target: String): Boolean = SAFE_LINK_PREFIXES.any(target::startsWith)

    /**
     * 把结构化颜色 Mark 解析为稳定导出色。
     *
     * @param mark 文字颜色或高亮 Mark。
     * @return 已知语义色；未知 token 返回空。
     */
    private fun colorToken(mark: InlineMark): ExportColorToken? = EXPORT_COLORS[mark.attributes[InlineMarkAttributes.COLOR_TOKEN]]

    /**
     * 转义 HTML 文本节点中的特殊字符。
     *
     * @param value 原始用户文本。
     * @return 不会改变文档结构的安全文本。
     */
    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * 转义 HTML 属性值。
     *
     * @param value 原始属性值。
     * @return 可安全放入双引号属性的文本。
     */
    private fun escapeAttribute(value: String): String = escapeHtml(value)

    /** 允许保留为可点击链接的协议前缀。 */
    private val SAFE_LINK_PREFIXES: List<String> = listOf("https://", "http://", "mailto:", "tagloom-note:", "tagloom-blob:")

    /** 自包含导出页面使用的紧凑阅读样式。 */
    private val DOCUMENT_STYLE: String = """
        :root{color-scheme:light;font-family:Inter,"Segoe UI","Microsoft YaHei",sans-serif;color:#202322;background:#fff}
        body{margin:0}main{max-width:760px;margin:64px auto;padding:0 32px 96px;line-height:1.7}
        .page-title{font-size:2.35rem;line-height:1.2;margin:0 0 2rem}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.6em 0 .55em}
        p{margin:.55em 0}blockquote{border-left:3px solid #9aafa7;margin:1em 0;padding:.25em 1em;color:#59635f}
        pre{background:#f4f5f3;border-radius:8px;padding:16px;overflow:auto}code{font-family:"JetBrains Mono",Consolas,monospace;background:#f1f2ef;border-radius:4px;padding:.1em .3em}pre code{background:none;padding:0}
        ul,ol{margin:.5em 0;padding-left:1.7em}.todo{display:block;margin:.55em 0}.todo input{margin-right:.65em}
        hr{border:0;border-top:1px solid #dfe3df;margin:2em 0}.attachment,.reference,.unknown{color:#69736f}.annotation-anchor{border-bottom:1px dotted #687f76}.annotation-anchor sup{color:#557267;margin-left:.15em}
        .annotations{border-top:1px solid #dfe3df;margin-top:3rem;padding-top:1rem}.annotations h2{font-size:1.1rem}a{color:#2f6b5b}
    """.trimIndent()
}

/**
 * 保存语义色在可移植文档中的前景色与柔和背景色。
 */
internal data class ExportColorToken(
    /** 不含透明度的 CSS/Office RGB 前景色。 */
    val textHex: String,
    /** 不含透明度的 CSS/Office RGB 背景色。 */
    val highlightHex: String,
    /** Word 高亮枚举使用的最接近颜色名。 */
    val wordHighlightName: String,
)

/** 编辑器语义色到稳定导出色的映射。 */
internal val EXPORT_COLORS: Map<String, ExportColorToken> = mapOf(
    "gray" to ExportColorToken("#59615e", "#e8ebe9", "lightGray"),
    "red" to ExportColorToken("#a6403b", "#f5ddda", "red"),
    "orange" to ExportColorToken("#a45c1e", "#f6e5d3", "darkYellow"),
    "yellow" to ExportColorToken("#8a7014", "#f6edbd", "yellow"),
    "green" to ExportColorToken("#3e7252", "#dcecdf", "green"),
    "teal" to ExportColorToken("#2f7470", "#d7ece9", "cyan"),
    "blue" to ExportColorToken("#396b9a", "#dce8f4", "blue"),
    "purple" to ExportColorToken("#73558f", "#eadff1", "magenta"),
    "pink" to ExportColorToken("#9a4f72", "#f3dfe8", "magenta"),
)
