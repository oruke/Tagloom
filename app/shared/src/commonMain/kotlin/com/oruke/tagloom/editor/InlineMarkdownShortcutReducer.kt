package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextEditor

/**
 * 保存一次闭合 Markdown 行内语法转换后的富文本和新光标位置。
 */
internal data class InlineMarkdownShortcutEdit(
    /** 删除分隔符并写入结构化 Mark 的富文本。 */
    val richText: RichText,
    /** 位于转换后正文末尾的光标偏移。 */
    val cursorOffset: Int,
)

/**
 * 把刚刚闭合的常见 Markdown 行内语法转换为权威 RichText Mark。
 *
 * 只处理光标前最后一个闭合 token，避免用户输入普通星号或波浪号时提前改写内容。
 */
internal object InlineMarkdownShortcutReducer {
    /**
     * 尝试转换光标前的粗体、斜体、删除线或行内代码 token。
     *
     * @param richText 已包含本次字符输入的富文本。
     * @param caretOffset 当前折叠光标位置。
     * @return 成功转换后的编辑结果；不存在刚闭合 token 时为空。
     */
    fun apply(
        richText: RichText,
        caretOffset: Int,
    ): InlineMarkdownShortcutEdit? {
        val text = richText.plainText
        val caret = caretOffset.coerceIn(0, text.length)
        val prefix = text.substring(0, caret)
        val token = listOf(
            findToken(prefix, "**", InlineMarkTypes.BOLD),
            findToken(prefix, "~~", InlineMarkTypes.STRIKETHROUGH),
            findToken(prefix, "`", InlineMarkTypes.CODE),
            findItalicToken(prefix),
        ).firstOrNull { candidate -> candidate != null } ?: return null
        val replacement = RichText(
            runs = listOf(
                InlineRun(
                    text = token.content,
                    marks = listOf(InlineMark(token.markType)),
                ),
            ),
        )
        return InlineMarkdownShortcutEdit(
            richText = RichTextEditor.replaceRange(
                richText = richText,
                startInclusive = token.start,
                endExclusive = token.endExclusive,
                replacement = replacement,
            ),
            cursorOffset = token.start + token.content.length,
        )
    }

    /**
     * 寻找以指定成对分隔符结尾且未被转义的 token。
     *
     * @param prefix 截止光标的文本。
     * @param delimiter 成对分隔符。
     * @param markType 转换后的 Mark 类型。
     * @return 最靠近光标的合法 token；不存在时为空。
     */
    private fun findToken(
        prefix: String,
        delimiter: String,
        markType: String,
    ): MarkdownInlineToken? {
        if (!prefix.endsWith(delimiter) || prefix.length <= delimiter.length * 2) {
            return null
        }
        val opening = prefix.lastIndexOf(delimiter, prefix.length - delimiter.length - 1)
        if (opening < 0 || isEscaped(prefix, opening)) {
            return null
        }
        val contentStart = opening + delimiter.length
        val contentEnd = prefix.length - delimiter.length
        val content = prefix.substring(contentStart, contentEnd)
        if (content.isBlank() || '\n' in content || delimiter in content) {
            return null
        }
        return MarkdownInlineToken(opening, prefix.length, content, markType)
    }

    /**
     * 寻找单星号斜体 token，并排除粗体双星号边界。
     *
     * @param prefix 截止光标的文本。
     * @return 合法斜体 token；不存在时为空。
     */
    private fun findItalicToken(prefix: String): MarkdownInlineToken? {
        if (!prefix.endsWith('*') || prefix.endsWith("**") || prefix.length < 3) {
            return null
        }
        val opening = prefix.lastIndexOf('*', prefix.lastIndex - 1)
        if (
            opening < 0 ||
            isEscaped(prefix, opening) ||
            prefix.getOrNull(opening - 1) == '*' ||
            prefix.getOrNull(opening + 1) == '*'
        ) {
            return null
        }
        val content = prefix.substring(opening + 1, prefix.lastIndex)
        if (content.isBlank() || '\n' in content || '*' in content) {
            return null
        }
        return MarkdownInlineToken(opening, prefix.length, content, InlineMarkTypes.ITALIC)
    }

    /**
     * 判断分隔符前是否存在奇数个连续反斜杠。
     *
     * @param text 完整前缀文本。
     * @param offset 分隔符起点。
     * @return 分隔符被用户转义时为 true。
     */
    private fun isEscaped(
        text: String,
        offset: Int,
    ): Boolean {
        var slashCount = 0
        var index = offset - 1
        while (index >= 0 && text[index] == '\\') {
            slashCount += 1
            index -= 1
        }
        return slashCount % 2 == 1
    }
}

/**
 * 描述一个等待转换的 Markdown 行内 token。
 */
private data class MarkdownInlineToken(
    /** 含起始分隔符的字符位置。 */
    val start: Int,
    /** 含结束分隔符的半开终点。 */
    val endExclusive: Int,
    /** 去除分隔符后的正文。 */
    val content: String,
    /** 转换后的稳定 Mark 类型。 */
    val markType: String,
)
