package com.oruke.tagloom.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 笔记查找的语义配色，以琥珀色标记文本命中，避免与青绿色交互状态混淆。 */
internal data class NoteSearchColors(
    /** 普通匹配的柔和底色。 */
    val matchBackground: Color,
    /** 普通匹配在高亮底色上的可读文字色。 */
    val matchForeground: Color,
    /** 当前匹配使用的更鲜明底色。 */
    val activeBackground: Color,
    /** 当前匹配在强调底色上的文字色。 */
    val activeForeground: Color,
    /** 当前匹配的内描边，不参与字符测量。 */
    val activeOutline: Color,
)

/** Stitch 浅色设计中的文本命中色，保留原文字符、字号与字重。 */
private val LightNoteSearchColors = NoteSearchColors(
    matchBackground = Color(0xFFFEF3C7),
    matchForeground = Color(0xFF202724),
    activeBackground = Color(0xFFFCD34D),
    activeForeground = Color(0xFF202724),
    activeOutline = Color(0xFFD97706),
)

/** 深色画布上保持同一琥珀色语义，并提供足够文字对比度。 */
private val DarkNoteSearchColors = NoteSearchColors(
    matchBackground = Color(0xFF51401B),
    matchForeground = Color(0xFFF6E8C5),
    activeBackground = Color(0xFFE0B64D),
    activeForeground = Color(0xFF241900),
    activeOutline = Color(0xFFFFD782),
)

/** @return 跟随应用当前画布明暗的查找配色，不依赖系统主题或平台 API。 */
@Composable
internal fun noteSearchColors(): NoteSearchColors =
    if (MaterialTheme.colorScheme.surface.luminance() < .5f) DarkNoteSearchColors else LightNoteSearchColors
