package com.oruke.tagloom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 绘制当前平台适用的纵向滚动轨道。
 *
 * 桌面端提供可拖动的原生轨道，触摸平台保持无常驻轨道的系统手势体验，避免遮挡窄屏表格单元格。
 *
 * @param scrollState 需要与轨道共享的纵向滚动状态。
 * @param modifier 轨道在父容器中的布局修饰符。
 */
@Composable
internal expect fun PlatformVerticalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

/**
 * 绘制当前平台适用的横向滚动轨道。
 *
 * 桌面端使用可拖动的原生轨道；触摸平台保留内容的水平滑动手势，不增加常驻轨道。
 *
 * @param scrollState 需要与轨道共享的横向滚动状态。
 * @param modifier 轨道在父容器中的布局修饰符。
 */
@Composable
internal expect fun PlatformHorizontalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

/**
 * 当前平台横向滚动轨道在视口底部需要预留的高度。
 *
 * 触摸平台返回零，避免为了不可见轨道在窄屏内容下方制造空白。
 */
internal expect val PlatformHorizontalScrollBarGutter: Dp
