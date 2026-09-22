package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.toArgb
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.EditorClipboardBlock
import com.oruke.tagloom.editor.EditorClipboardFragment

/** 把选中正文投影为独立 HTML；只输出已知标签和转义文本，不复制文档元数据。 */
internal object EditorClipboardHtml {
    /**
     * 导出标准列表、标题、引用、代码和行内格式。
     * @param fragment 已裁剪到用户选区的正文片段。
     * @return 供系统 text/html 使用的 HTML 片段。
     */
    fun render(fragment: EditorClipboardFragment): String =
        "<div style=\"white-space:pre-wrap\">" + if (fragment.inline) {
            fragment.blocks.joinToString("<br>") { inline(it.richText) } + "</div>"
        } else siblings(fragment.blocks, null) + "</div>"

    /**
     * 按相邻列表类型分组，子列表放在所属 li 内，编号使用实际选中位置。
     * @param blocks 选区内的有序块。
     * @param parent 当前层级父块下标。
     * @return 当前层级及后代的合法 HTML。
     */
    private fun siblings(blocks: List<EditorClipboardBlock>, parent: Int?): String = buildString {
        var list: String? = null
        blocks.forEachIndexed { index, block ->
            if (block.parentIndex != parent) return@forEachIndexed
            val nextList = when (block.type) {
                BlockTypes.NUMBERED_LIST -> "ol"
                BlockTypes.BULLETED_LIST, BlockTypes.TODO -> "ul"
                else -> null
            }
            if (nextList != list) {
                list?.let { append("</$it>") }
                nextList?.let { append(if (it == "ol") "<ol start=\"${block.ordinal}\">" else "<ul>") }
                list = nextList
            }
            val children = siblings(blocks, index)
            val text = inline(block.richText)
            when (block.type) {
                BlockTypes.NUMBERED_LIST -> append("<li value=\"${block.ordinal}\">$text$children</li>")
                BlockTypes.BULLETED_LIST -> append("<li>$text$children</li>")
                BlockTypes.TODO -> append("<li>${if (block.checked) "☑" else "☐"} $text$children</li>")
                BlockTypes.HEADING -> append("<h${block.level}>$text</h${block.level}>$children")
                BlockTypes.QUOTE -> append("<blockquote>$text$children</blockquote>")
                BlockTypes.CODE -> append("<pre><code>${escape(block.richText.plainText)}</code></pre>$children")
                else -> append("<p>$text</p>$children")
            }
        }
        list?.let { append("</$it>") }
    }

    /**
     * 将领域 Mark 映射为标准行内元素，未知 Mark 留在私有片段中。
     * @param text 已裁剪的富文本。
     * @return 可嵌入块元素的安全 HTML。
     */
    private fun inline(text: RichText): String = text.runs.joinToString("") { run ->
        run.marks.fold(escape(run.text).replace("\n", "<br>")) { rendered, mark ->
            when (mark.type) {
                InlineMarkTypes.BOLD -> "<strong>$rendered</strong>"
                InlineMarkTypes.ITALIC -> "<em>$rendered</em>"
                InlineMarkTypes.UNDERLINE -> "<u>$rendered</u>"
                InlineMarkTypes.STRIKETHROUGH -> "<s>$rendered</s>"
                InlineMarkTypes.CODE -> "<code>$rendered</code>"
                InlineMarkTypes.HIGHLIGHT -> "<span style=\"background-color:${color(mark, true)}\">$rendered</span>"
                InlineMarkTypes.TEXT_COLOR -> "<span style=\"color:${color(mark, false)}\">$rendered</span>"
                InlineMarkTypes.LINK -> {
                    val href = mark.attributes["href"].orEmpty()
                    if (href.substringBefore(':').lowercase() in setOf("https", "http", "mailto", "tagloom-note"))
                        "<a href=\"${escape(href)}\">$rendered</a>" else rendered
                }

                else -> rendered
            }
        }
    }

    /**
     * 复用编辑器语义色，避免导出和界面颜色维护两套映射。
     * @param mark 颜色或高亮 Mark。
     * @param background 是否导出背景色。
     * @return 六位 CSS 色值。
     */
    private fun color(mark: InlineMark, background: Boolean): String {
        val token = mark.attributes[InlineMarkAttributes.COLOR_TOKEN]
        val color = if (background) inlineHighlightColor(token) else inlineTextColor(token)
        return "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
    }

    /**
     * 转义 HTML 文本和属性值，不允许选中文字生成任意标签。
     * @param text 原始文字。
     * @return 实体转义后的文字。
     */
    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
