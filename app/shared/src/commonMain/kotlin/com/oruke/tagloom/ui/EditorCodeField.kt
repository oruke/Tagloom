package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.oruke.tagloom.editor.adjacentCursor
import com.oruke.tagloom.editor.mergeBlockWithPrevious
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染可保留换行和输入法状态的多行代码 Block。
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
    var fieldValue by remember(block.id) { mutableStateOf(TextFieldValue(content.text)) }
    val focusRequester = remember(block.id) { FocusRequester() }
    var focused by remember(block.id) { mutableStateOf(false) }

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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                content.language?.uppercase() ?: "CODE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.editor_code_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { incoming ->
                fieldValue = incoming
                state.updateCodeBlockText(block.id, incoming.text)
                state.updateSelection(block.id, incoming.selection.start, incoming.selection.end)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                    if (focusState.isFocused) {
                        state.updateSelection(block.id, fieldValue.selection.start, fieldValue.selection.end)
                    }
                }
                .onPreviewKeyEvent { event ->
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
                            fieldValue = fieldValue.copy(text = updated, selection = TextRange(cursor))
                            state.updateCodeBlockText(block.id, updated)
                            state.updateSelection(block.id, cursor, cursor)
                            true
                        }
                        event.key == Key.Backspace && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                            state.mergeBlockWithPrevious(block.id)?.let(onCursorRequest) != null
                        }
                        event.key == Key.DirectionUp && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                            state.adjacentCursor(block.id, BlockMoveDirection.UP)?.let(onCursorRequest) != null
                        }
                        event.key == Key.DirectionDown && fieldValue.selection.collapsed &&
                            fieldValue.selection.end == fieldValue.text.length -> {
                            state.adjacentCursor(block.id, BlockMoveDirection.DOWN)?.let(onCursorRequest) != null
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
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (fieldValue.text.isEmpty() && !focused) {
                        Text(
                            stringResource(Res.string.editor_code_placeholder),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 21.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** 代码 Block 的一次 Tab 输入使用四个空格，避免平台制表符渲染差异。 */
private const val CODE_INDENT: String = "    "
