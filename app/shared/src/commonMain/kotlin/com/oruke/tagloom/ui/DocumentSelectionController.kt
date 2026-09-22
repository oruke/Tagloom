package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.editor.*

/** 一处已挂载输入框的实际字符布局和焦点入口，不持有文档内容副本。 */
internal class DocumentSelectionField {
    /** 原文对应的排版结果。 */
    var layout: TextLayoutResult? = null

    /** 字段在编辑器中的实时坐标。 */
    var coordinates: LayoutCoordinates? = null

    /** 请求该输入框接收原生键盘及输入法事件。 */
    var requestFocus: () -> Unit = {}
}

/**
 * 协调正文指针、键盘与输入法选区；所有写入仍经过共享编辑器命令。
 * @param state 当前笔记的共享编辑状态。
 * @param onCursorRequest 替换或折叠选区后的定位入口。
 */
internal class DocumentSelectionController(
    /** 文档选区和原有事务写入入口。 */
    val state: TagloomEditorState,
    /** 完成编辑后回到原生输入框的焦点回调。 */
    private val onCursorRequest: (EditorCursorTarget) -> Unit,
) {
    /** 已挂载的文本布局，离屏字段由组合生命周期移除。 */
    val fields = mutableMapOf<BlockId, DocumentSelectionField>()

    /** 有方向的选择起点。 */
    var anchor: EditorCursorTarget? = null

    /** 有方向的选择终点。 */
    var focus: EditorCursorTarget? = null

    /** 是否正在由文档选择接管原生拖动。 */
    var dragging by mutableStateOf(false)

    /** 当前选区是否由跨块控制器产生，需要同步到各原生输入框。 */
    var managed by mutableStateOf(false)

    /**
     * 在真实文本字段中命中字符；跨字段空白选择最近一行。
     * @param position 根坐标中的鼠标位置。
     * @param exact 只允许命中可见字段内部，供开始拖动使用。
     * @return 原文字符位置；没有候选字段时为空。
     */
    fun hit(position: Offset, exact: Boolean): EditorCursorTarget? {
        val allowed =
            if (exact) null else anchor?.let { state.selectionFlow(it.blockId).map { block -> block.id }.toSet() }
        val candidates = fields.filter { (id, field) ->
            field.coordinates?.isAttached == true && field.layout != null && (allowed == null || id in allowed) &&
                    field.coordinates!!.boundsInRoot().height > 0f
        }
        val target = candidates.entries.minByOrNull { (_, field) ->
            val rect = field.coordinates!!.boundsInRoot()
            when {
                position.y < rect.top -> rect.top - position.y; position.y > rect.bottom -> position.y - rect.bottom; else -> 0f
            }
        } ?: return null
        val field = target.value
        val rect = field.coordinates!!.boundsInRoot()
        if (exact && !rect.contains(position)) return null
        val local = field.coordinates!!.localToRoot(Offset.Zero)
        val layout = field.layout!!
        val offset = when {
            position.y < rect.top -> 0
            position.y > rect.bottom -> layout.layoutInput.text.length
            else -> layout.getOffsetForPosition(position - local)
        }
        return EditorCursorTarget(target.key, offset.coerceIn(0, layout.layoutInput.text.length))
    }

    /**
     * 记录本次拖选的起点，Shift 点击沿用已有锚点。
     * @param target 鼠标按下位置。
     * @param extend 是否延伸已有选区。
     */
    fun begin(target: EditorCursorTarget, extend: Boolean) {
        if (!extend) {
            anchor = target
            focus = target
            managed = false
            if (state.selection.isCrossBlock) state.updateSelection(target.blockId, target.offset, target.offset)
        } else {
            if (!managed || anchor == null) anchor =
                state.selection.blockId?.let { EditorCursorTarget(it, state.selection.start) } ?: target
            extendTo(target)
        }
    }

    /**
     * 将当前选区延伸到新的字符位置。
     * @param target 有方向的终点。
     */
    fun extendTo(target: EditorCursorTarget) {
        val start = anchor ?: return
        managed = true
        focus = target
        state.selectDocumentRange(start, target)
    }

    /** 把原生输入焦点放在选区首块，确保反向选区的 IME 替换也使用正确组合偏移。 */
    fun finish() {
        dragging = false
        if (managed) state.selection.blockId?.let { fields[it]?.requestFocus?.invoke() }
    }

    /**
     * 接收输入框发出的文本变更，原子替换跨块范围并保留输入法组合态。
     * @param blockId 事件来源块。
     * @param previous 输入前原生字段值。
     * @param incoming 输入后原生字段值。
     * @return 已处理时返回新字段值；未接管时为空。
     */
    fun acceptInput(blockId: BlockId, previous: TextFieldValue, incoming: TextFieldValue): TextFieldValue? {
        if (dragging) return previous
        val latestBlock = state.selectedDocument?.note?.blocks?.firstOrNull { it.id == blockId && !it.deleted }
        val latestText = latestBlock?.selectionText()
        if (latestBlock != null && latestText != null && latestText != previous.text && incoming.text == previous.text) {
            // 模型命令与焦点转移可发生在同一帧：旧 IME 会回送最后渲染的正文。
            // 这是选区回声而非输入，必须恢复新模型，不能拿已更新的本地值误判为文本差异。
            return TextFieldValue(
                latestBlock.editableRichText()?.toAnnotatedString() ?: androidx.compose.ui.text.AnnotatedString(
                    latestText
                ),
                TextRange(
                    incoming.selection.start.coerceIn(0, latestText.length),
                    incoming.selection.end.coerceIn(0, latestText.length)
                )
            )
        }
        if (!state.selection.isCrossBlock) {
            if (previous.text != incoming.text) managed = false
            return null
        }
        if (incoming.text == previous.text) return previous
        val delta = TextEditDelta.between(previous, incoming)
        val target = state.replaceDocumentSelection(delta.insertedText) ?: return previous
        managed = false
        anchor = null
        focus = null
        val block = state.selectedDocument?.note?.blocks?.firstOrNull { it.id == target.blockId } ?: return previous
        if (target.blockId != blockId) onCursorRequest(target)
        // 首块原生选区已同步到被替换前缀，IME 的组合偏移无需平移；尾块余文只接在输入之后。
        return TextFieldValue(
            block.editableRichText()?.toAnnotatedString()
                ?: androidx.compose.ui.text.AnnotatedString(block.selectionText().orEmpty()),
            TextRange(target.offset), incoming.composition?.takeIf { target.blockId == blockId })
    }

    /**
     * 处理文档级快捷键，其余按键继续交给原生输入框。
     * @param blockId 接收键盘事件的字段。
     * @param value 该字段当前光标及组合态。
     * @param event 原生键盘事件。
     * @param clipboard 系统多格式剪贴板命令。
     * @return 是否消费事件。
     */
    fun key(
        blockId: BlockId,
        value: TextFieldValue,
        event: KeyEvent,
        clipboard: EditorClipboardActions,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown || value.composition != null) return false
        val command = event.isCtrlPressed || event.isMetaPressed
        val cross = state.selection.isCrossBlock
        val flow = state.selectionFlow(anchor?.blockId?.takeIf { managed } ?: blockId)
        if (flow.isEmpty()) return false
        if (command && event.key == Key.A) {
            anchor = EditorCursorTarget(flow.first().id, 0)
            extendTo(EditorCursorTarget(flow.last().id, flow.last().selectionText().orEmpty().length))
            finish()
            return true
        }
        if (command && event.key in listOf(Key.C, Key.X, Key.V)) {
            when (event.key) {
                Key.C -> clipboard.copy()
                Key.X -> clipboard.copy(cut = true)
                else -> clipboard.paste(plainOnly = event.isShiftPressed)
            }
            return true
        }
        if (event.key == Key.Insert && (command || event.isShiftPressed)) {
            if (command) clipboard.copy() else clipboard.paste()
            return true
        }
        if (event.key == Key.Delete && event.isShiftPressed) {
            clipboard.copy(cut = true)
            return true
        }
        if (cross && command && event.key in listOf(Key.B, Key.I, Key.E)) {
            state.toggleSelectionMark(
                when (event.key) {
                    Key.B -> InlineMarkTypes.BOLD; Key.I -> InlineMarkTypes.ITALIC; else -> InlineMarkTypes.CODE
                }
            )
            return true
        }
        if (cross && (event.key == Key.Backspace || event.key == Key.Delete)) {
            replace(""); return true
        }
        if (cross && event.key == Key.Enter) {
            val first = state.selectedBlockRanges().first().block
            if (event.isShiftPressed || first.editableRichText() == null) {
                state.replaceDocumentSelection("\n")?.let(::collapse)
            } else {
                state.replaceDocumentSelection("")?.let { target ->
                    state.splitBlock(target.blockId, target.offset, target.offset)?.let(::collapse)
                }
            }
            return true
        }
        if (cross && event.key == Key.Tab) {
            replace("    "); return true
        }
        val arrows =
            listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.MoveHome, Key.MoveEnd)
        if (event.key == Key.Escape && cross) {
            collapse(EditorCursorTarget(state.selection.blockId!!, state.selection.start)); return true
        }
        if (event.key !in arrows) return false
        if (command && !event.isShiftPressed) return false
        val backward = event.key in listOf(Key.DirectionLeft, Key.DirectionUp, Key.MoveHome)
        if (cross && !event.isShiftPressed) {
            collapse(
                EditorCursorTarget(
                    if (backward) state.selection.blockId!! else state.selection.ids.last(),
                    if (backward) state.selection.start else state.selection.end
                )
            )
            return true
        }
        if (!event.isShiftPressed) {
            managed = false; return false
        }
        if (command && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) return false
        if (!managed) {
            anchor = EditorCursorTarget(blockId, value.selection.start)
            focus = EditorCursorTarget(blockId, value.selection.end)
        }
        val current = focus ?: EditorCursorTarget(blockId, value.selection.end)
        val index = flow.indexOfFirst { it.id == current.blockId }
        if (index < 0) return false
        val text = flow[index].selectionText().orEmpty()
        val layout = fields[current.blockId]?.layout
        val offset = current.offset.coerceIn(0, text.length)
        val line = layout?.getLineForOffset(offset) ?: 0
        val next = when {
            command && event.key == Key.MoveHome -> EditorCursorTarget(flow.first().id, 0)
            command && event.key == Key.MoveEnd -> EditorCursorTarget(
                flow.last().id,
                flow.last().selectionText().orEmpty().length
            )

            event.key == Key.MoveHome -> current.copy(offset = layout?.getLineStart(line) ?: 0)
            event.key == Key.MoveEnd -> current.copy(offset = layout?.getLineEnd(line, true) ?: text.length)
            event.key == Key.DirectionUp && line > 0 && layout != null -> current.copy(
                offset = layout.getOffsetForPosition(
                    Offset(layout.getCursorRect(offset).left, layout.getLineTop(line - 1))
                )
            )

            event.key == Key.DirectionDown && layout != null && line < layout.lineCount - 1 -> current.copy(
                offset = layout.getOffsetForPosition(
                    Offset(layout.getCursorRect(offset).left, layout.getLineBottom(line + 1) - 1f)
                )
            )

            backward && (offset == 0 || event.key == Key.DirectionUp) -> flow.getOrNull(index - 1)
                ?.let { EditorCursorTarget(it.id, it.selectionText().orEmpty().length) }

            !backward && (offset == text.length || event.key == Key.DirectionDown) -> flow.getOrNull(index + 1)
                ?.let { EditorCursorTarget(it.id, 0) }

            command && layout != null -> current.copy(
                offset = if (backward) layout.getWordBoundary((offset - 1).coerceAtLeast(0)).start
                else layout.getWordBoundary(offset.coerceAtMost((text.length - 1).coerceAtLeast(0))).end
            )

            else -> current.copy(
                offset = if (backward) (offset - if (offset > 1 && text[offset - 1].isLowSurrogate() && text[offset - 2].isHighSurrogate()) 2 else 1).coerceAtLeast(
                    0
                )
                else (offset + if (offset + 1 < text.length && text[offset].isHighSurrogate() && text[offset + 1].isLowSurrogate()) 2 else 1).coerceAtMost(
                    text.length
                )
            )
        }
        if (next != null) extendTo(next)
        finish()
        return true
    }

    /**
     * 替换选区并请求新光标；多行剪贴板交由既有 Markdown 导入处理。
     * @param text 替换原文。
     */
    private fun replace(text: String) {
        if ('\n' !in text && '\r' !in text) {
            state.replaceDocumentSelection(text)?.let(::collapse)
            return
        }
        val target = state.replaceDocumentSelection("") ?: return
        val pasted = state.pasteMarkdownAtBlock(target.blockId, target.offset, target.offset, text)
            ?: state.replaceDocumentSelection(text)
        managed = false
        anchor = null
        focus = null
        pasted?.let(onCursorRequest)
    }

    /**
     * 收起文档选区并回到原生编辑。
     * @param target 收起后的插入位置。
     */
    fun collapse(target: EditorCursorTarget) {
        managed = false
        anchor = null
        focus = null
        state.updateSelection(target.blockId, target.offset, target.offset)
        onCursorRequest(target)
    }
}
