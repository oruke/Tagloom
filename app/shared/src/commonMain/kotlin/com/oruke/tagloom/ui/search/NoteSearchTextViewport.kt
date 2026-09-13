package com.oruke.tagloom.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 为单行字段提供可由字符定位驱动的水平视口，避免内部光标滚动把查找结果截在屏外。
 * @param modifier 标题或单元格在页面中占用的有限宽度。
 * @param content 接收最小宽度修饰符的单行文本控件；查找定位附在该控件上。
 */
@Composable
internal fun NoteSearchTextViewport(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier) {
        val fieldModifier = Modifier.widthIn(min = maxWidth)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            content(fieldModifier)
        }
    }
}
