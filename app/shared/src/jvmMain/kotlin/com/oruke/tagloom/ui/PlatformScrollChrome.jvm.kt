package com.oruke.tagloom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 在 JVM Desktop 上绘制可拖动的原生纵向滚动轨道。
 *
 * 空内容或未溢出的内容不显示轨道，避免内嵌 Block 平白增加视觉噪声。
 *
 * @param scrollState 需要与轨道共享的纵向滚动状态。
 * @param modifier 轨道在父容器中的布局修饰符。
 */
@Composable
internal actual fun PlatformVerticalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    if (scrollState.maxValue == 0) return
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
    )
}

/**
 * 在 JVM Desktop 上绘制可拖动的原生横向滚动轨道。
 *
 * 内容未横向溢出时不渲染，避免为单列或窄表格额外占据视觉空间。
 *
 * @param scrollState 需要与轨道共享的横向滚动状态。
 * @param modifier 轨道在父容器中的布局修饰符。
 */
@Composable
internal actual fun PlatformHorizontalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    if (scrollState.maxValue == 0) return
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
    )
}

/** JVM Desktop 横向轨道与内容之间的最小布局预留高度。 */
internal actual val PlatformHorizontalScrollBarGutter: Dp = 12.dp
