package com.oruke.tagloom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 内嵌表格与数据集在宽屏时允许占用的最大正文高度。 */
internal val EMBEDDED_BLOCK_DESKTOP_MAX_HEIGHT = 520.dp

/** 内嵌表格与数据集在紧凑布局时允许占用的最大正文高度。 */
internal val EMBEDDED_BLOCK_COMPACT_MAX_HEIGHT = 360.dp

/** 编辑器从宽屏布局切换到紧凑布局的可用宽度阈值。 */
internal val EMBEDDED_BLOCK_COMPACT_BREAKPOINT = 680.dp

/**
 * 为编辑器中的内嵌长内容提供受限的纵向视口。
 *
 * 外层编辑器使用 LazyColumn；这里先限制高度，再创建独立纵向滚动状态，避免单个表格或数据集把
 * 整篇笔记的阅读位置推走。横向滚动仍由具体内容自己处理；调用方传入共享状态时，视口只负责在
 * 桌面端固定绘制对应轨道，因而不会与表格的列溢出策略耦合。
 *
 * @param compact 是否采用紧凑宽度的高度预算。
 * @param modifier 外部布局修饰符。
 * @param desktopMaxHeight 宽屏下内容区域的最大高度。
 * @param compactMaxHeight 紧凑宽度下内容区域的最大高度。
 * @param horizontalScrollState 可选的横向滚动状态；提供后桌面端在视口底部绘制共享轨道。
 * @param content 需要放入纵向视口的内容。
 */
@Composable
internal fun BoundedBlockViewport(
    compact: Boolean,
    modifier: Modifier = Modifier,
    desktopMaxHeight: Dp = EMBEDDED_BLOCK_DESKTOP_MAX_HEIGHT,
    compactMaxHeight: Dp = EMBEDDED_BLOCK_COMPACT_MAX_HEIGHT,
    horizontalScrollState: ScrollState? = null,
    content: @Composable () -> Unit,
) {
    val verticalScrollState = rememberScrollState()
    val maxHeight = if (compact) compactMaxHeight else desktopMaxHeight
    val hasHorizontalOverflow = horizontalScrollState?.maxValue?.let { maxValue -> maxValue > 0 } == true
    val horizontalTrackGutter = if (hasHorizontalOverflow) PlatformHorizontalScrollBarGutter else 0.dp
    val hasVerticalOverflow = verticalScrollState.maxValue > 0

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScrollState)
                // 横向轨道固定在底部；桌面端为末行预留空间，避免轨道遮挡可点击内容。
                .padding(bottom = horizontalTrackGutter),
        ) {
            content()
        }
        PlatformVerticalScrollBar(
            scrollState = verticalScrollState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = 3.dp, bottom = 3.dp + horizontalTrackGutter),
        )
        horizontalScrollState?.let { state ->
            PlatformHorizontalScrollBar(
                scrollState = state,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = if (hasVerticalOverflow) PlatformHorizontalScrollBarGutter else 0.dp),
            )
        }
    }
}
