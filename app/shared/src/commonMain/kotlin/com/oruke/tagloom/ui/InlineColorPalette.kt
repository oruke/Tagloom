package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.Color

/**
 * 描述富文本颜色选择器中的一个跨主题语义色。
 */
internal data class InlineColorOption(
    /** 写入 Inline Mark 的稳定 token。 */
    val token: String,
    /** 面向用户的颜色名称。 */
    val label: String,
    /** 浅色编辑器中的文字预览色。 */
    val textColor: Color,
    /** 浅色编辑器中的高亮预览色。 */
    val highlightColor: Color,
)

/** 编辑器颜色面板使用的稳定、可读语义色集合。 */
internal val InlineColorPalette: List<InlineColorOption> = listOf(
    InlineColorOption("gray", "灰色", Color(0xFF5F6363), Color(0xFFE5E7E5)),
    InlineColorOption("red", "红色", Color(0xFFA33D3D), Color(0xFFF8DADA)),
    InlineColorOption("orange", "橙色", Color(0xFF934A12), Color(0xFFFCE1C5)),
    InlineColorOption("yellow", "黄色", Color(0xFF705800), Color(0xFFFFF0A8)),
    InlineColorOption("green", "绿色", Color(0xFF2F6B45), Color(0xFFD7EFDC)),
    InlineColorOption("teal", "青绿色", Color(0xFF0C6B62), Color(0xFFCDEBE6)),
    InlineColorOption("blue", "蓝色", Color(0xFF315D9B), Color(0xFFDCE7FA)),
    InlineColorOption("purple", "紫色", Color(0xFF6B4C9A), Color(0xFFE9DFF8)),
    InlineColorOption("pink", "粉色", Color(0xFF92486F), Color(0xFFF6DDE9)),
)
