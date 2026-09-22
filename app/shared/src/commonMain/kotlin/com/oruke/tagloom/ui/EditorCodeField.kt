package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.selectedBlockRanges
import com.oruke.tagloom.editor.adjacentCursor
import com.oruke.tagloom.editor.mergeBlockWithPrevious
import com.oruke.tagloom.editor.updateCodeBlockLanguage
import com.oruke.tagloom.editor.updateCodeBlockWrap
import com.oruke.tagloom.editor.wrapsCodeLines
import com.oruke.tagloom.ui.code.CodeBlockToolbar
import com.oruke.tagloom.ui.code.CodeSyntaxPalette
import com.oruke.tagloom.ui.code.rememberCodeVisualTransformation
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染可编辑的语法高亮代码块，保留原文、软换行、光标及输入法组合状态。
 *
 * @param state 编辑器状态。
 * @param block 当前代码 Block。
 * @param cursorRequest 外部焦点与光标请求。
 * @param onCursorRequest 请求其他 Block 获得焦点。
 * @param onCursorConsumed 已消费当前焦点请求。
 * @param onSaveRequest 保存快捷键动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun EditorCodeField(
    state: TagloomEditorState,
    block: Block,
    cursorRequest: EditorCursorTarget?,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCursorConsumed: () -> Unit,
    onSaveRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = block.content as? CodeBlockContent ?: return
    var fieldValue by remember(block.id) {
        val selection = state.selectedBlockRanges().firstOrNull { it.block.id == block.id }
        mutableStateOf(
            TextFieldValue(
                content.text, selection = TextRange(
                    selection?.start?.coerceIn(0, content.text.length) ?: 0,
                    selection?.end?.coerceIn(0, content.text.length) ?: 0,
                )
            )
        )
    }
    val focusRequester = remember(block.id) { FocusRequester() }
    val find = rememberNoteSearchDecoration(
        NoteSearchTarget(NoteSearchField.BODY, block.id),
        onRestoreSelection = { start, end ->
            val range = if (state.selection.isCrossBlock) state.selectedBlockRanges()
                .firstOrNull { it.block.id == block.id } else null
            val restored = TextRange(
                (range?.start ?: start).coerceIn(0, fieldValue.text.length),
                (range?.end ?: end).coerceIn(0, fieldValue.text.length)
            )
            fieldValue = fieldValue.copy(selection = restored, composition = null)
            if (!state.selection.isCrossBlock) state.updateSelection(block.id, restored.start, restored.end)
        })
    var focused by remember(block.id) { mutableStateOf(false) }
    val wrap = block.wrapsCodeLines()
    val horizontalScroll = rememberScrollState()
    val syntaxPalette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        CodeSyntaxPalette.Dark
    } else {
        CodeSyntaxPalette.Light
    }
    val syntaxTransformation = rememberCodeVisualTransformation(fieldValue.text, content.language, syntaxPalette)
    val selection = rememberDocumentSelectionDecoration(
        state,
        block.id,
        fieldValue,
        onValue = { fieldValue = it },
        onFocus = { focusRequester.requestFocus() },
        base = find.transformation(syntaxTransformation)
    )

    LaunchedEffect(content.text) {
        if (fieldValue.text != content.text) {
            fieldValue = fieldValue.copy(
                text = content.text,
                selection = TextRange(
                    fieldValue.selection.start.coerceIn(0, content.text.length),
                    fieldValue.selection.end.coerceIn(0, content.text.length),
                ),
            )
        }
    }
    LaunchedEffect(cursorRequest) {
        val request = cursorRequest?.takeIf { target -> target.blockId == block.id } ?: return@LaunchedEffect
        val offset = request.offset.coerceIn(0, fieldValue.text.length)
        fieldValue = fieldValue.copy(selection = TextRange(offset))
        state.updateSelection(block.id, offset, offset)
        focusRequester.requestFocus()
    }
    LaunchedEffect(cursorRequest, focused) {
        if (focused && cursorRequest?.blockId == block.id) {
            // 仅在代码输入框真实获得焦点后确认请求，避免快速跨 Block 输入回落到旧输入框。
            onCursorConsumed()
        }
    }

    Column(
        modifier = modifier
            .testTag("code-block-${block.id.value}")
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                RoundedCornerShape(8.dp),
            ),
    ) {
        CodeBlockToolbar(
            language = content.language,
            code = fieldValue.text,
            wrap = wrap,
            onLanguageChange = { state.updateCodeBlockLanguage(block.id, it) },
            onWrapChange = { state.updateCodeBlockWrap(block.id, it) },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            val availableWidth = maxWidth
            // 仅不换行时使用无限横向测量；禁用滚动手势并不会恢复软换行的宽度约束。
            Box(Modifier.fillMaxWidth().then(if (wrap) Modifier else Modifier.horizontalScroll(horizontalScroll))) {
                DocumentSelectionColors(selection.clipboard) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = onValueChange@{ incoming ->
                            selection.acceptInput(incoming)?.let { fieldValue = it; return@onValueChange }
                            val textChanged = fieldValue.text != incoming.text
                            fieldValue = incoming
                            // 焦点转移只更新原生光标，不以过期字段文本覆盖已经完成的跨块编辑。
                            if (textChanged) state.updateCodeBlockText(block.id, incoming.text)
                            if (focused || textChanged) state.updateSelection(
                                block.id,
                                incoming.selection.start,
                                incoming.selection.end
                            )
                        },
                        modifier = Modifier
                            .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = availableWidth))
                            .testTag("code-input-${block.id.value}")
                            .then(find.modifier)
                            .then(selection.modifier)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                focused = focusState.isFocused
                                if (focusState.isFocused && !selection.ownsSelection) {
                                    state.updateSelection(
                                        block.id,
                                        fieldValue.selection.start,
                                        fieldValue.selection.end
                                    )
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (selection.onKeyEvent(event)) return@onPreviewKeyEvent true
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                val commandPressed = event.isCtrlPressed || event.isMetaPressed
                                when {
                                    commandPressed && event.key == Key.S -> {
                                        onSaveRequest()
                                        true
                                    }

                                    event.key == Key.Tab -> {
                                        val start = minOf(fieldValue.selection.start, fieldValue.selection.end)
                                        val end = maxOf(fieldValue.selection.start, fieldValue.selection.end)
                                        val updated = fieldValue.text.replaceRange(start, end, CODE_INDENT)
                                        val cursor = start + CODE_INDENT.length
                                        fieldValue = fieldValue.copy(
                                            text = updated,
                                            selection = TextRange(cursor),
                                            composition = null
                                        )
                                        state.updateCodeBlockText(block.id, updated)
                                        state.updateSelection(block.id, cursor, cursor)
                                        true
                                    }

                                    event.key == Key.Backspace && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                                        state.mergeBlockWithPrevious(block.id)?.let(onCursorRequest) != null
                                    }

                                    event.key == Key.DirectionUp && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                                        state.adjacentCursor(block.id, BlockMoveDirection.UP)
                                            ?.let(onCursorRequest) != null
                                    }

                                    event.key == Key.DirectionDown && fieldValue.selection.collapsed &&
                                            fieldValue.selection.end == fieldValue.text.length -> {
                                        state.adjacentCursor(block.id, BlockMoveDirection.DOWN)
                                            ?.let(onCursorRequest) != null
                                    }

                                    else -> false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            lineHeight = 21.sp,
                        ),
                        visualTransformation = selection.transformation,
                        onTextLayout = { find.onTextLayout(it); selection.onTextLayout(it) },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (fieldValue.text.isEmpty() && !focused) {
                                    Text(
                                        stringResource(Res.string.editor_code_placeholder),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.5.sp,
                                            lineHeight = 21.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 代码 Block 的一次 Tab 输入使用四个空格，避免平台制表符渲染差异。 */
private const val CODE_INDENT: String = "    "
