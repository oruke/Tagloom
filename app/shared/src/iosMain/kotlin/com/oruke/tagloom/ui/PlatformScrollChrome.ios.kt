package com.oruke.tagloom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 使用触摸滚动，不在内嵌内容上常驻桌面式滚动轨道。
 *
 * @param scrollState 需要与内容共享的纵向滚动状态。
 * @param modifier 预留的轨道布局修饰符。
 */
@Composable
internal actual fun PlatformVerticalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier,
) = Unit

/**
 * iOS 使用触摸横向滑动，不在内嵌内容上常驻桌面式横向轨道。
 *
 * @param scrollState 需要与内容共享的横向滚动状态。
 * @param modifier 预留的轨道布局修饰符。
 */
@Composable
internal actual fun PlatformHorizontalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier,
) = Unit

/** iOS 不显示常驻横向轨道，因此不需要额外底部预留。 */
internal actual val PlatformHorizontalScrollBarGutter: Dp = 0.dp
