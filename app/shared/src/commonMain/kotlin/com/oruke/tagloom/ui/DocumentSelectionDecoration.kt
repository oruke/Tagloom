package com.oruke.tagloom.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.*
import com.oruke.tagloom.ui.search.LocalNoteSearch

/** 暴露原文选区，供辅助技术和真实输入测试检查多块范围。 */
internal val DocumentSelectedRangeKey = SemanticsPropertyKey<TextRange>("DocumentSelectedRange")

/** 一个字段的文档选区绘制和输入桥接。 */
internal data class DocumentSelectionDecoration(
    /** 实际字段的布局、绘制和键盘入口。 */
    val modifier: Modifier,
    /** 接收最终排版结果。 */
    val onTextLayout: (TextLayoutResult) -> Unit,
    /** 只覆盖被选择文字的显示底色，不改变模型、字体或偏移。 */
    val transformation: VisualTransformation,
    /** 接管跨块输入替换；为空表示保持原生编辑。 */
    val acceptInput: (TextFieldValue) -> TextFieldValue?,
    /** 是否应忽略原生输入框失焦时的选区折叠。 */
    val ownsSelection: Boolean,
    /** 在单块 Enter、Tab 等命令之前处理文档级按键。 */
    val onKeyEvent: (KeyEvent) -> Boolean,
    /** 供原生右键和触摸菜单复用的多格式剪贴板操作。 */
    val clipboard: EditorClipboardActions,
)

/**
 * 接入正文或代码输入框，按完整行高绘制连续选区并保留双向文本片段。
 * @param state 文档状态。
 * @param blockId 字段的稳定块 ID。
 * @param value 原生文本及组合态。
 * @param onValue 同步文档选区到原生输入框。
 * @param onFocus 请求原生输入焦点。
 * @param base 已有查找和代码显示变换，必须保持原文偏移。
 * @return 可组合到 BasicTextField 的选区能力。
 */
@Composable
@Suppress("DEPRECATION")
internal fun rememberDocumentSelectionDecoration(
    state: TagloomEditorState,
    blockId: BlockId,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onFocus: () -> Unit,
    base: VisualTransformation,
): DocumentSelectionDecoration {
    val controller = LocalDocumentSelection.current
    val field = remember(blockId) { DocumentSelectionField() }
    val currentValue by rememberUpdatedState(value)
    val updateValue by rememberUpdatedState(onValue)
    val requestFocus by rememberUpdatedState(onFocus)
    var layout by remember(blockId) { mutableStateOf<TextLayoutResult?>(null) }
    val clipboard = rememberEditorClipboardActions(state, blockId, value, controller)
    val range = state.selectedBlockRanges().firstOrNull { it.block.id == blockId }?.let { TextRange(it.start, it.end) }
    val visibleRange = range.takeUnless { LocalNoteSearch.current?.isOpen == true }
    val pinnable = LocalPinnableContainer.current
    val pinned =
        controller?.managed == true && (state.selection.blockId == blockId || controller.anchor?.blockId == blockId)
    DisposableEffect(pinnable, pinned) {
        val handle = if (pinned) pinnable?.pin() else null
        onDispose { handle?.release() }
    }
    DisposableEffect(controller, blockId) {
        controller?.fields?.set(blockId, field)
        field.requestFocus = { requestFocus() }
        onDispose { controller?.fields?.remove(blockId) }
    }
    LaunchedEffect(state.selection, controller?.managed, controller?.dragging) {
        if (controller?.managed == true && range != null && currentValue.composition == null) {
            updateValue(currentValue.copy(selection = range))
            // 鼠标松开之后再确认首块焦点，避免原生文本手势的收尾回调把焦点留在末块。
            if (state.selection.isCrossBlock && state.selection.blockId == blockId && !controller.dragging) requestFocus()
        }
    }
    val color =
        MaterialTheme.colorScheme.primary.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.30f else 0.20f)
    val foreground = MaterialTheme.colorScheme.onSurface
    val transformation = VisualTransformation { text ->
        val transformed = base.filter(text)
        val selected = visibleRange?.takeUnless(TextRange::collapsed)
        val annotated =
            if (selected == null) transformed.text else selectedTextAppearance(transformed.text, selected, foreground)
        TransformedText(annotated, transformed.offsetMapping)
    }
    return DocumentSelectionDecoration(
        modifier = Modifier.onGloballyPositioned { field.coordinates = it }
            .semantics {
                if (range != null) this[DocumentSelectedRangeKey] = range
                copyText { clipboard.copy(); true }
                cutText { clipboard.copy(cut = true); true }
                pasteText { clipboard.paste(); true }
            }
            .drawWithCache {
                val result = layout
                val rectangles =
                    if (visibleRange != null && result != null && result.layoutInput.text.text == value.text) selectionLineRects(
                        result,
                        visibleRange
                    ) else emptyList()
                onDrawWithContent {
                    rectangles.forEach { rect ->
                        drawRect(
                            color,
                            Offset(rect.left, rect.top),
                            Size(rect.width, rect.height)
                        )
                    }
                    drawContent()
                }
            },
        onTextLayout = { layout = it; field.layout = it },
        transformation = transformation,
        acceptInput = { controller?.acceptInput(blockId, currentValue, it) },
        ownsSelection = controller?.dragging == true || state.selection.isCrossBlock,
        onKeyEvent = { event ->
            controller?.key(blockId, currentValue, event, clipboard) == true
        },
        clipboard = clipboard,
    )
}

/**
 * 分段覆盖选区内已有 Span 的色彩，避免窄范围 code／高亮 Span 优先于整段选区样式。
 * @param text 已包含原富文本、代码和查找样式的显示文本。
 * @param range 原文字符选区。
 * @param foreground 主题正文色。
 * @return 字符、字体、链接与段落信息不变的显示副本。
 */
internal fun selectedTextAppearance(text: AnnotatedString, range: TextRange, foreground: Color): AnnotatedString {
    val start = range.min.coerceIn(0, text.length)
    val end = range.max.coerceIn(start, text.length)
    val adjusted = text.flatMapAnnotations { annotation ->
        val style = annotation.item as? SpanStyle
        if (style == null || annotation.end <= start || annotation.start >= end) listOf(annotation)
        else buildList {
            if (annotation.start < start) add(annotation.copy(end = start))
            add(
                AnnotatedString.Range(
                    style.copy(background = Color.Transparent, color = foreground),
                    maxOf(start, annotation.start), minOf(end, annotation.end), annotation.tag
                )
            )
            if (annotation.end > end) add(annotation.copy(start = end))
        }
    }
    return AnnotatedString.Builder(adjusted).apply {
        addStyle(SpanStyle(background = Color.Transparent, color = foreground), start, end)
    }.toAnnotatedString()
}

/**
 * 隐藏原生按字形高度绘制的选区底色，仍保留触摸选择手柄和光标主题。
 * @param clipboard 当前字段统一的剪贴板命令。
 * @param content 参与统一选区绘制的输入框。
 */
@Composable
internal fun DocumentSelectionColors(clipboard: EditorClipboardActions, content: @Composable () -> Unit) {
    EditorClipboardMenus(clipboard) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = MaterialTheme.colorScheme.primary,
                backgroundColor = Color.Transparent,
            ), content = content
        )
    }
}

/**
 * 将每行实际字形的水平范围合并，以行盒高度绘制选区，保留双向文本未选中的间隙。
 * @param layout 与原文一致的排版结果。
 * @param range 已规范化的字符选区。
 * @return 互不重叠且高度一致的矩形集合。
 */
internal fun selectionLineRects(layout: TextLayoutResult, range: TextRange): List<Rect> {
    val start = range.min.coerceIn(0, layout.layoutInput.text.length)
    val end = range.max.coerceIn(start, layout.layoutInput.text.length)
    if (start == end) return emptyList()
    return buildList {
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            val boxes = (from until to).map { layout.getBoundingBox(it) }.sortedBy(Rect::left)
            var left = boxes.firstOrNull()?.left ?: continue
            var right = left
            boxes.forEach { box ->
                if (box.left > right + 1f) {
                    add(Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line)))
                    left = box.left
                }
                right = maxOf(right, box.right)
            }
            // 换行符/空行也留出一个细小选区，明确它们属于选中内容。
            add(Rect(left, layout.getLineTop(line), maxOf(right, left + 3f), layout.getLineBottom(line)))
        }
    }
}
