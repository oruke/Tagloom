package com.oruke.tagloom.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.search.NoteSearchState
import com.oruke.tagloom.editor.search.NoteSearchMatch
import kotlin.math.roundToInt

/** 当前匹配字符的屏幕位置回报，仅用于避免浮层遮挡，不保存正文。 */
internal val LocalNoteSearchMatchPosition = staticCompositionLocalOf<((NoteSearchMatch, Rect) -> Unit)?> { null }

/**
 * 将查找工具栏覆盖在图标下方，与正文共享焦点树且不参与正文测量。
 * @param search 当前笔记的临时查找会话。
 * @param onClose 关闭查找并恢复编辑焦点。
 * @param content 编辑画布，接收用于报告搜索图标位置的修饰符。
 */
@Composable
internal fun NoteSearchOverlay(
    search: NoteSearchState,
    onClose: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var anchor by remember(search) { mutableStateOf<Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var toolbarHeight by remember { mutableStateOf(0) }
    var locatedMatch by remember(search) { mutableStateOf<Pair<NoteSearchMatch, Rect>?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val gutter = with(density) { 8.dp.toPx() }
        val toolbarWidth = minOf(480.dp, (maxWidth - 16.dp).coerceAtLeast(0.dp))
        val widthPx = with(density) { toolbarWidth.toPx() }
        val viewportWidth = with(density) { maxWidth.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }
        CompositionLocalProvider(LocalNoteSearchMatchPosition provides { match, bounds -> locatedMatch = match to bounds }) {
            content(Modifier.onGloballyPositioned {
                // 不能用裁剪后的 boundsInRoot；页头滚出视口时它会变成空矩形，导致浮层跳到左侧。
                anchor = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
            })
        }
        if (search.isOpen) {
            // 页头被 LazyColumn 回收后保留横向锚点，并把浮层夹在视口内，查找不会随正文滚走。
            val right = anchor?.right?.minus(origin.x) ?: (viewportWidth - gutter)
            val below = anchor?.bottom?.minus(origin.y)?.plus(gutter) ?: gutter
            val left = (right - widthPx).coerceIn(gutter, (viewportWidth - widthPx - gutter).coerceAtLeast(gutter))
            val maxTop = (viewportHeight - toolbarHeight - gutter).coerceAtLeast(gutter)
            var top = below.coerceIn(gutter, maxTop)
            val match = locatedMatch?.takeIf { it.first == search.active }?.second?.translate(-origin)
            // 查找长标题等顶端字段时已无空间向上滚动，只让浮层避开当前字符，正文仍保持原位。
            if (match != null && match.overlaps(Rect(left, top, left + widthPx, top + toolbarHeight))) {
                top = if (match.bottom + gutter <= maxTop) match.bottom + gutter
                else (match.top - toolbarHeight - gutter).coerceIn(gutter, maxTop)
            }
            Box(
                Modifier.offset {
                    IntOffset(left.roundToInt(), top.roundToInt())
                }.width(toolbarWidth).onSizeChanged { toolbarHeight = it.height },
            ) {
                NoteSearchBar(search, onClose)
            }
        }
    }
}
