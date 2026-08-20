package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.EditorBlockType
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.TextEditDelta
import com.oruke.tagloom.editor.adjacentCursor
import com.oruke.tagloom.editor.applyInlineMarkdownShortcut
import com.oruke.tagloom.editor.applyMarkdownShortcut
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.mergeBlockWithPrevious
import com.oruke.tagloom.editor.pasteMarkdownAtBlock
import com.oruke.tagloom.editor.splitBlock
import com.oruke.tagloom.editor.toggleSelectionMark
import com.oruke.tagloom.editor.turnBlockInto
import com.oruke.tagloom.editor.turnBlockIntoBaseTable
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_block_no_matching_commands

/**
 * 渲染一个保持输入法组合、结构化样式、跨块键盘行为和 Slash 菜单的 RichText Block。
 *
 * @param state 共享编辑器状态。
 * @param block 当前文本 Block。
 * @param textStyle Block 类型对应排版。
 * @param placeholder 空 Block 提示。
 * @param cursorRequest 外部请求的焦点与光标位置。
 * @param commandMenuRequested 是否由左侧加号强制打开类型菜单。
 * @param onCursorRequest 请求另一个 Block 获得焦点。
 * @param onCursorConsumed 当前 Block 已消费焦点请求。
 * @param onCommandMenuDismiss 关闭外部强制菜单。
 * @param databaseCreationAvailable 当前平台是否装配 Database 写入入口。
 * @param onCreateDatabaseBlock 请求异步创建并绑定 Database Block。
 * @param onSaveRequest 保存快捷键动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun EditorRichTextField(
    state: TagloomEditorState,
    block: Block,
    textStyle: TextStyle,
    placeholder: String,
    cursorRequest: EditorCursorTarget?,
    commandMenuRequested: Boolean,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCursorConsumed: () -> Unit,
    onCommandMenuDismiss: () -> Unit,
    databaseCreationAvailable: Boolean,
    onCreateDatabaseBlock: (BlockId) -> Unit,
    onSaveRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val richText = block.editableRichText() ?: return
    val modelText = richText.toAnnotatedString()
    val initialSelection = state.selection.takeIf { selection -> selection.blockId == block.id }
    var fieldValue by remember(block.id) {
        mutableStateOf(
            TextFieldValue(
                annotatedString = modelText,
                selection = TextRange(
                    initialSelection?.start?.coerceIn(0, modelText.length) ?: 0,
                    initialSelection?.end?.coerceIn(0, modelText.length) ?: 0,
                ),
            ),
        )
    }
    val focusRequester = remember(block.id) { FocusRequester() }
    var focused by remember(block.id) { mutableStateOf(false) }
    var dismissedSlashText by remember(block.id) { mutableStateOf<String?>(null) }
    var selectedCommandIndex by remember(block.id) { mutableIntStateOf(0) }
    var tablePickerOpen by remember(block.id) { mutableStateOf(false) }
    val slashTrigger = findSlashCommandTrigger(fieldValue)
    val commands = filterEditorBlockCommands(
        query = slashTrigger?.query.orEmpty(),
        databaseAvailable = databaseCreationAvailable,
    )
    val showCommandMenu = commandMenuRequested || (
        slashTrigger != null && dismissedSlashText != fieldValue.text && focused
        )

    LaunchedEffect(modelText) {
        if (fieldValue.annotatedString != modelText) {
            fieldValue = fieldValue.copy(
                annotatedString = modelText,
                selection = TextRange(
                    fieldValue.selection.start.coerceIn(0, modelText.length),
                    fieldValue.selection.end.coerceIn(0, modelText.length),
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
            // 焦点请求必须在目标输入框确认聚焦后再清除，否则新 Block 首次组合时会丢失请求。
            onCursorConsumed()
        }
    }
    LaunchedEffect(commands.size, slashTrigger?.query) {
        selectedCommandIndex = selectedCommandIndex.coerceIn(0, (commands.lastIndex).coerceAtLeast(0))
    }

    Box(modifier) {
        BasicTextField(
            value = fieldValue,
            onValueChange = onValueChange@{ incoming ->
                val previous = fieldValue
                val delta = TextEditDelta.between(previous.text, incoming.text)
                if (
                    incoming.composition == null &&
                    delta.insertedText.length > 1 &&
                    ('\n' in delta.insertedText || '\r' in delta.insertedText)
                ) {
                    state.pasteMarkdownAtBlock(
                        blockId = block.id,
                        selectionStart = delta.replacedStart,
                        selectionEnd = delta.replacedEnd,
                        markdown = delta.insertedText,
                    )?.let { target ->
                        fieldValue = previous
                        onCursorRequest(target)
                        return@onValueChange
                    }
                }
                fieldValue = incoming
                dismissedSlashText = null
                state.updateBlockText(block.id, incoming.text)
                state.updateSelection(block.id, incoming.selection.start, incoming.selection.end)
                if (incoming.composition == null && incoming.selection.collapsed) {
                    val inlineTarget = state.applyInlineMarkdownShortcut(block.id, incoming.selection.end)
                    if (inlineTarget != null) {
                        onCursorRequest(inlineTarget)
                    } else if (incoming.selection.end == incoming.text.length) {
                        state.applyMarkdownShortcut(block.id, incoming.text)?.let(onCursorRequest)
                    }
                }
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
                        commandPressed && event.isShiftPressed && event.key == Key.S -> {
                            toggleMark(state, InlineMarkTypes.STRIKETHROUGH)
                        }
                        commandPressed && event.key == Key.S -> {
                            onSaveRequest()
                            true
                        }
                        commandPressed && event.key == Key.B -> toggleMark(state, InlineMarkTypes.BOLD)
                        commandPressed && event.key == Key.I -> toggleMark(state, InlineMarkTypes.ITALIC)
                        commandPressed && event.key == Key.E -> toggleMark(state, InlineMarkTypes.CODE)
                        commandPressed && event.key == Key.Enter && block.type == BlockTypes.TODO -> {
                            state.toggleTodo(block.id)
                            true
                        }
                        commandPressed && event.isShiftPressed && event.key == Key.DirectionUp -> {
                            state.moveBlock(block.id, BlockMoveDirection.UP)
                            true
                        }
                        commandPressed && event.isShiftPressed && event.key == Key.DirectionDown -> {
                            state.moveBlock(block.id, BlockMoveDirection.DOWN)
                            true
                        }
                        event.key == Key.Tab -> {
                            if (event.isShiftPressed) state.outdentBlock(block.id) else state.indentBlock(block.id)
                            true
                        }
                        event.key == Key.Enter && !event.isShiftPressed && showCommandMenu && commands.isNotEmpty() -> {
                            val command = commands[selectedCommandIndex]
                            if (command.type == EditorBlockType.BASE_TABLE) {
                                tablePickerOpen = true
                            } else {
                                applyCommand(
                                    state = state,
                                    block = block,
                                    command = command,
                                    slashTrigger = slashTrigger,
                                    onCursorRequest = onCursorRequest,
                                    onCreateDatabaseBlock = onCreateDatabaseBlock,
                                )
                                onCommandMenuDismiss()
                            }
                            true
                        }
                        event.key == Key.Enter && !event.isShiftPressed -> {
                            state.splitBlock(
                                block.id,
                                fieldValue.selection.start,
                                fieldValue.selection.end,
                            )?.let(onCursorRequest)
                            true
                        }
                        event.key == Key.Backspace && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                            state.mergeBlockWithPrevious(block.id)?.let(onCursorRequest) != null
                        }
                        showCommandMenu && event.key == Key.DirectionDown && commands.isNotEmpty() -> {
                            selectedCommandIndex = (selectedCommandIndex + 1).coerceAtMost(commands.lastIndex)
                            true
                        }
                        showCommandMenu && event.key == Key.DirectionUp && commands.isNotEmpty() -> {
                            selectedCommandIndex = (selectedCommandIndex - 1).coerceAtLeast(0)
                            true
                        }
                        event.key == Key.DirectionUp && fieldValue.selection.collapsed && fieldValue.selection.start == 0 -> {
                            state.adjacentCursor(block.id, BlockMoveDirection.UP)?.let(onCursorRequest) != null
                        }
                        event.key == Key.DirectionDown && fieldValue.selection.collapsed &&
                            fieldValue.selection.end == fieldValue.text.length -> {
                            state.adjacentCursor(block.id, BlockMoveDirection.DOWN)?.let(onCursorRequest) != null
                        }
                        showCommandMenu && event.key == Key.Escape -> {
                            tablePickerOpen = false
                            dismissedSlashText = fieldValue.text
                            onCommandMenuDismiss()
                            true
                        }
                        else -> false
                    }
                },
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (fieldValue.text.isEmpty() && !focused) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                    innerTextField()
                }
            },
        )
        TagloomDropdownMenu(
            expanded = showCommandMenu && !tablePickerOpen,
            properties = PopupProperties(focusable = false),
            modifier = Modifier.widthIn(min = 260.dp),
            onDismissRequest = {
                dismissedSlashText = fieldValue.text
                onCommandMenuDismiss()
            },
        ) {
            if (commands.isEmpty()) {
                Text(
                    stringResource(Res.string.editor_block_no_matching_commands),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                commands.forEachIndexed { index, command ->
                    EditorBlockCommandItem(command, selected = index == selectedCommandIndex) {
                        if (command.type == EditorBlockType.BASE_TABLE) {
                            tablePickerOpen = true
                        } else {
                            applyCommand(
                                state = state,
                                block = block,
                                command = command,
                                slashTrigger = slashTrigger,
                                onCursorRequest = onCursorRequest,
                                onCreateDatabaseBlock = onCreateDatabaseBlock,
                            )
                            onCommandMenuDismiss()
                        }
                    }
                }
            }
        }
        TagloomDropdownMenu(
            expanded = tablePickerOpen,
            onDismissRequest = { tablePickerOpen = false },
        ) {
            BaseTableInsertPicker(
                onCreate = { rows, columns ->
                    applyBaseTableCommand(
                        state = state,
                        block = block,
                        slashTrigger = slashTrigger,
                        rows = rows,
                        columns = columns,
                        onCursorRequest = onCursorRequest,
                    )
                    tablePickerOpen = false
                    onCommandMenuDismiss()
                },
                onCancel = { tablePickerOpen = false },
            )
        }
    }
}

/**
 * 对当前非空选区切换 Mark，并向键盘事件报告是否已处理。
 *
 * @param state 编辑器状态。
 * @param markType Mark 稳定类型。
 * @return 存在非空选区时为 true。
 */
private fun toggleMark(
    state: TagloomEditorState,
    markType: String,
): Boolean {
    if (!state.selection.isNotEmpty) {
        return false
    }
    state.toggleSelectionMark(markType)
    return true
}

/**
 * 清除 Slash 查询文本并转换当前 Block。
 *
 * @param state 编辑器状态。
 * @param block 当前 Block。
 * @param command 目标类型命令。
 * @param slashTrigger 当前输入触发范围；由加号打开时为空。
 * @param onCursorRequest 转换后的焦点请求。
 * @param onCreateDatabaseBlock 请求异步创建并绑定 Database Block。
 */
private fun applyCommand(
    state: TagloomEditorState,
    block: Block,
    command: EditorBlockCommand,
    slashTrigger: SlashCommandTrigger?,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCreateDatabaseBlock: (BlockId) -> Unit,
) {
    if (slashTrigger != null) {
        val currentText = block.editableRichText()?.plainText.orEmpty()
        state.updateBlockText(
            block.id,
            currentText.removeRange(slashTrigger.start, slashTrigger.endExclusive),
        )
    }
    if (command.type == com.oruke.tagloom.editor.EditorBlockType.DATABASE) {
        onCreateDatabaseBlock(block.id)
        return
    }
    state.turnBlockInto(block.id, command.type)?.let(onCursorRequest)
}

/**
 * 清除 Slash 查询文本并按用户选择的尺寸创建正文表格。
 *
 * @param state 编辑器状态。
 * @param block 当前命令 Block。
 * @param slashTrigger Slash 触发范围；由加号打开时为空。
 * @param rows 初始活动行数。
 * @param columns 初始活动列数。
 * @param onCursorRequest 转换后的焦点请求。
 */
private fun applyBaseTableCommand(
    state: TagloomEditorState,
    block: Block,
    slashTrigger: SlashCommandTrigger?,
    rows: Int,
    columns: Int,
    onCursorRequest: (EditorCursorTarget) -> Unit,
) {
    if (slashTrigger != null) {
        val currentText = block.editableRichText()?.plainText.orEmpty()
        state.updateBlockText(block.id, currentText.removeRange(slashTrigger.start, slashTrigger.endExclusive))
    }
    state.turnBlockIntoBaseTable(block.id, rows, columns)?.let(onCursorRequest)
}

/**
 * 寻找折叠光标所在词中的 Slash 命令触发范围。
 *
 * Slash 只在行首或空白字符之后生效，URL 和普通路径中的 `/` 因此不会意外打开命令菜单。
 *
 * @param value 当前文本框值与光标。
 * @return 可继续输入过滤的 Slash token；不满足触发条件时为空。
 */
internal fun findSlashCommandTrigger(value: TextFieldValue): SlashCommandTrigger? {
    if (!value.selection.collapsed) {
        return null
    }
    val caret = value.selection.end.coerceIn(0, value.text.length)
    if (caret == 0) {
        return null
    }
    val slash = value.text.lastIndexOf('/', caret - 1)
    if (slash < 0 || (slash > 0 && !value.text[slash - 1].isWhitespace())) {
        return null
    }
    val query = value.text.substring(slash + 1, caret)
    if (query.any(Char::isWhitespace)) {
        return null
    }
    return SlashCommandTrigger(slash, caret, query)
}

/**
 * 描述当前 Slash 命令在 Block 纯文本中的范围和查询文本。
 */
internal data class SlashCommandTrigger(
    /** `/` 所在字符偏移。 */
    val start: Int,
    /** 当前光标位置，也是 token 半开终点。 */
    val endExclusive: Int,
    /** 去除 `/` 后用于过滤命令的文本。 */
    val query: String,
)
