package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.RichText

/**
 * 把结构化 RichText 映射为 Compose AnnotatedString，保留单词级 Mark 与加注高亮。
 *
 * @return 可直接交给 TextFieldValue 渲染的 AnnotatedString。
 */
fun RichText.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val start = length
        append(run.text)
        val end = length
        run.marks.forEach { mark ->
            mark.toSpanStyle()?.let { style -> addStyle(style, start, end) }
        }
        if (run.annotationIds.isNotEmpty()) {
            addStyle(SpanStyle(background = Color(0xFFFFE7A3)), start, end)
        }
    }
}

/**
 * 把已知 InlineMark 转换为 Compose SpanStyle，未知 Mark 由领域模型继续保留。
 *
 * @return 可渲染样式；未知类型为 null。
 */
private fun InlineMark.toSpanStyle(): SpanStyle? = when (type) {
    InlineMarkTypes.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
    InlineMarkTypes.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineMarkTypes.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    InlineMarkTypes.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    InlineMarkTypes.CODE -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0xFFE4ECE9),
    )
    InlineMarkTypes.HIGHLIGHT -> SpanStyle(
        background = inlineHighlightColor(attributes[InlineMarkAttributes.COLOR_TOKEN]),
    )
    InlineMarkTypes.TEXT_COLOR -> SpanStyle(
        color = inlineTextColor(attributes[InlineMarkAttributes.COLOR_TOKEN]),
    )
    InlineMarkTypes.LINK -> SpanStyle(
        color = Color(0xFF006B5F),
        textDecoration = TextDecoration.Underline,
    )
    else -> null
}

/**
 * 把持久化语义 token 映射为可读文字颜色。
 *
 * @param token 工作区快照中的稳定颜色名称。
 * @return 未知 token 回退正文墨色，保证旧客户端仍可阅读。
 */
private fun inlineTextColor(token: String?): Color = when (token) {
    "gray" -> Color(0xFF5F6363)
    "red" -> Color(0xFFA33D3D)
    "orange" -> Color(0xFF934A12)
    "yellow" -> Color(0xFF705800)
    "green" -> Color(0xFF2F6B45)
    "teal" -> Color(0xFF0C6B62)
    "blue" -> Color(0xFF315D9B)
    "purple" -> Color(0xFF6B4C9A)
    "pink" -> Color(0xFF92486F)
    else -> Color(0xFF202523)
}

/**
 * 把持久化语义 token 映射为浅色高亮背景。
 *
 * @param token 工作区快照中的稳定颜色名称。
 * @return 未知 token 回退默认黄色高亮。
 */
private fun inlineHighlightColor(token: String?): Color = when (token) {
    "gray" -> Color(0xFFE5E7E5)
    "red" -> Color(0xFFF8DADA)
    "orange" -> Color(0xFFFCE1C5)
    "yellow" -> Color(0xFFFFF0A8)
    "green" -> Color(0xFFD7EFDC)
    "teal" -> Color(0xFFCDEBE6)
    "blue" -> Color(0xFFDCE7FA)
    "purple" -> Color(0xFFE9DFF8)
    "pink" -> Color(0xFFF6DDE9)
    else -> Color(0xFFFFEE9C)
}
