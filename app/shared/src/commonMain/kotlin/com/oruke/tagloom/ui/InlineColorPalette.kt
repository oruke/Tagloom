package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 描述富文本颜色选择器中的一个跨主题语义色。
 */
internal data class InlineColorOption(
    /** 写入 Inline Mark 的稳定 token。 */
    val token: String,
    /** 浅色编辑器中的文字预览色。 */
    val textColor: Color,
    /** 浅色编辑器中的高亮预览色。 */
    val highlightColor: Color,
)

/** 编辑器颜色面板使用的稳定、可读语义色集合。 */
internal val InlineColorPalette: List<InlineColorOption> = listOf(
    InlineColorOption("gray", Color(0xFF5F6363), Color(0xFFE5E7E5)),
    InlineColorOption("red", Color(0xFFA33D3D), Color(0xFFF8DADA)),
    InlineColorOption("orange", Color(0xFF934A12), Color(0xFFFCE1C5)),
    InlineColorOption("yellow", Color(0xFF705800), Color(0xFFFFF0A8)),
    InlineColorOption("green", Color(0xFF2F6B45), Color(0xFFD7EFDC)),
    InlineColorOption("teal", Color(0xFF0C6B62), Color(0xFFCDEBE6)),
    InlineColorOption("blue", Color(0xFF315D9B), Color(0xFFDCE7FA)),
    InlineColorOption("purple", Color(0xFF6B4C9A), Color(0xFFE9DFF8)),
    InlineColorOption("pink", Color(0xFF92486F), Color(0xFFF6DDE9)),
)

/**
 * 返回稳定颜色 token 在当前 Locale 下的名称。
 *
 * @return 面向用户的颜色名称；未知 token 原样展示，避免静默丢失后续版本的语义色。
 */
@Composable
internal fun InlineColorOption.localizedLabel(): String = when (token) {
    "gray" -> stringResource(Res.string.editor_color_gray)
    "red" -> stringResource(Res.string.editor_color_red)
    "orange" -> stringResource(Res.string.editor_color_orange)
    "yellow" -> stringResource(Res.string.editor_color_yellow)
    "green" -> stringResource(Res.string.editor_color_green)
    "teal" -> stringResource(Res.string.editor_color_teal)
    "blue" -> stringResource(Res.string.editor_color_blue)
    "purple" -> stringResource(Res.string.editor_color_purple)
    "pink" -> stringResource(Res.string.editor_color_pink)
    else -> token
}
