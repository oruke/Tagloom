package com.oruke.tagloom.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.editor.TagloomEditorState

/**
 * 为整篇笔记共享拖动会话、落位时钟、取消快捷键及边缘滚动。
 * @param editor 生产编辑器状态。
 * @param documentId 当前文档身份，切换文档即丢弃旧预览。
 * @param listState 外层文档滚动状态。
 * @param modifier 编辑器外层的尺寸、位置和父布局约束。
 * @param content 使用同一拖动会话的编辑器内容。
 */
@Composable
internal fun EditorBlockDragScope(
    editor: TagloomEditorState,
    documentId: ObjectId,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val drag = remember(documentId) { EditorBlockDragState(editor) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val release = remember(drag, drag.releaseVersion) { Animatable(if (drag.releasedPositions.isEmpty()) 1f else 0f) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(drag.releaseVersion) {
        val version = drag.releaseVersion
        if (drag.releasedPositions.isNotEmpty()) release.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        drag.completeRelease(version)
    }
    LaunchedEffect(drag.activeId, drag.pointerY, viewport) {
        if (drag.activeId == null) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val direction = when {
            drag.pointerY < viewport.top + edge -> -((viewport.top + edge - drag.pointerY) / edge).coerceIn(0f, 1f)
            drag.pointerY > viewport.bottom - edge -> ((drag.pointerY - viewport.bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        // 指针离开边缘就停止请求动画帧，静止拖动不会让整个编辑器持续重绘。
        if (direction == 0f) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (drag.activeId != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            if (listState.scrollBy(direction * edge * 12f * seconds) == 0f) break
        }
    }
    CompositionLocalProvider(LocalEditorBlockDrag provides drag, LocalBlockReleaseProgress provides release.value) {
        Box(modifier.fillMaxSize().onGloballyPositioned {
            viewport = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        }.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && drag.activeId != null) {
                drag.finish(cancel = true)
                true
            } else false
        }) {
            content()
            val target = drag.target
            if (target != null) Canvas(Modifier.matchParentSize().testTag("block-drop-indicator")) {
                val bounds = drag.indicatorBounds ?: return@Canvas
                val y = (if (target.after) bounds.bottom else bounds.top) - viewport.top
                val left = bounds.left - viewport.left + BLOCK_GUTTER_WIDTH.toPx()
                val right = bounds.right - viewport.left - BLOCK_GUTTER_WIDTH.toPx()
                drawLine(accent, Offset(left, y), Offset(right, y), 2.dp.toPx())
                drawCircle(accent, 3.dp.toPx(), Offset(left, y))
            }
        }
    }
}

/**
 * 分开测量位置与绘制位置，提供拖动抬起、插入线和确认/取消后的连续落位。
 * @param block 当前行的 Block。
 * @param content 包含手柄与正文的完整行。
 */
@Composable
internal fun BlockDragFrame(block: Block, content: @Composable () -> Unit) {
    val drag = LocalEditorBlockDrag.current
    val container = LocalBlockDragContainer.current
    val progress = LocalBlockReleaseProgress.current
    val lifted = drag?.liftedIds?.contains(block.id) == true
    val dragging = drag?.activeId != null
    val pinnable = LocalPinnableContainer.current
    val bringIntoView = remember(block.id) { BringIntoViewRequester() }
    DisposableEffect(block.id, drag) { onDispose { drag?.unregister(block.id) } }
    // LazyColumn 不能在自动滚动时回收正在接收指针的手柄，否则会中断整次拖动。
    DisposableEffect(lifted, pinnable) {
        val pinned = if (lifted) pinnable?.pin() else null
        onDispose { pinned?.release() }
    }
    LaunchedEffect(drag?.releaseVersion) {
        if (drag?.releasedId == block.id) {
            // 先等重排布局落定，再显示落在视口边缘的源块；不请求输入焦点或改变文字选区。
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(
        Modifier.fillMaxWidth().zIndex(if (lifted) 1f else 0f)
            .bringIntoViewRequester(bringIntoView)
            .testTag("block-row-${block.id.value}")
            .onGloballyPositioned {
                drag?.register(
                    BlockDragLayout(
                        block, container,
                        Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    )
                )
            }
            .drawBehind {
                if (lifted && dragging) drawRect(accent.copy(alpha = 0.045f))
            }) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationY =
                if (dragging) drag.translation(block.id) else drag?.releaseTranslation(block.id, progress) ?: 0f
            shadowElevation = if (lifted) 5.dp.toPx() * if (dragging) 1f else 1f - progress else 0f
            shape = RoundedCornerShape(6.dp)
            alpha = if (lifted && dragging) 0.96f else 1f
        }.background(if (lifted) surface else Color.Transparent, RoundedCornerShape(6.dp))) { content() }
    }
}
