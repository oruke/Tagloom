package com.oruke.tagloom.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState

/** 每篇笔记独立的正文选区控制器，结构化字段仍使用各自的原生交互。 */
internal val LocalDocumentSelection = staticCompositionLocalOf<DocumentSelectionController?> { null }

/**
 * 在正文宿主观察鼠标拖选，并让长文档在靠近边缘时连续滚动。
 * @param state 文档状态。
 * @param listState 正文懒加载列表。
 * @param onCursorRequest 文档替换后的光标请求。
 * @param content 既有编辑器内容。
 */
@Composable
internal fun DocumentSelectionScope(
    state: TagloomEditorState,
    listState: LazyListState,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    content: @Composable () -> Unit,
) {
    val request by rememberUpdatedState(onCursorRequest)
    val controller = remember(state.selectedDocumentId) { DocumentSelectionController(state) { request(it) } }
    LaunchedEffect(state.selection.blockId) {
        if (state.selection.blockId == null) {
            controller.managed = false
            controller.anchor = null
            controller.focus = null
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val edge = with(LocalDensity.current) { 44.dp.toPx() }
    LaunchedEffect(controller.dragging, pointer, viewport) {
        if (!controller.dragging) return@LaunchedEffect
        val position = pointer ?: return@LaunchedEffect
        val speed = when {
            position.y < viewport.top + edge -> -((viewport.top + edge - position.y) / edge).coerceIn(0f, 1f)
            position.y > viewport.bottom - edge -> ((position.y - viewport.bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (speed == 0f) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (controller.dragging) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previousFrame) / 1_000_000_000f).coerceAtMost(0.04f)
            previousFrame = now
            if (listState.scrollBy(speed * edge * 16f * elapsed) == 0f) break
            controller.hit(position, exact = false)?.let(controller::extendTo)
        }
    }
    CompositionLocalProvider(LocalDocumentSelection provides controller) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); viewport = it.boundsInRoot() }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val start = controller.hit(down.position + origin, exact = true) ?: return@awaitEachGesture
                    val shift = currentEvent.keyboardModifiers.isShiftPressed
                    controller.begin(start, shift)
                    if (shift) down.consume()
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pointer = change.position + origin
                            if (!controller.dragging && change.pressed && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                controller.dragging = true
                            }
                            if (controller.dragging) {
                                controller.hit(pointer!!, exact = false)?.let(controller::extendTo)
                                change.consume()
                            }
                            if (!change.pressed) break
                        } while (true)
                    } finally {
                        pointer = null
                        controller.finish()
                    }
                }
            }) { content() }
    }
}
