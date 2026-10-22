package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.PopupProperties
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.EditorBlockType
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.selectedBlockRanges
import com.oruke.tagloom.editor.TextEditDelta
import com.oruke.tagloom.editor.adjacentCursor
import com.oruke.tagloom.editor.applyInlineMarkdownShortcut
import com.oruke.tagloom.editor.applyMarkdownShortcut
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.mergeBlockWithPrevious
import com.oruke.tagloom.editor.pasteMarkdownAtBlock
import com.oruke.tagloom.editor.splitBlock
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
 * @param commandMenuRequested 是否由左侧手柄菜单强制打开类型菜单。
 * @param onCursorRequest 请求另一个 Block 获得焦点。
 * @param onCursorConsumed 当前 Block 已消费焦点请求。
 * @param onCommandMenuDismiss 关闭外部强制菜单。
 * @param databaseCreationAvailable 当前平台是否装配 Database 写入入口。
 * @param onCreateDatabaseBlock 请求异步创建并绑定 Database Block。
 * @param onSaveRequest 保存快捷键动作。
 * @param onFirstLineCenterChanged 报告文字输入区中首行的真实垂直中心，单位 dp。
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
    onFirstLineCenterChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val linkController = LocalWorkspaceLinks.current
    val richText = block.editableRichText() ?: return
    val modelText = richText.toAnnotatedString()
    val initialSelection = state.selectedBlockRanges().firstOrNull { it.block.id == block.id }
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
    val selection = rememberDocumentSelectionDecoration(
        state, block.id, fieldValue,
        onValue = { fieldValue = it }, onFocus = { focusRequester.requestFocus() }, base = find.transformation()
    )
    var dismissedSlashText by remember(block.id) { mutableStateOf<String?>(null) }
    var selectedCommandIndex by remember(block.id) { mutableIntStateOf(0) }
    var tablePickerOpen by remember(block.id) { mutableStateOf(false) }
    var pastedLinkUrl by remember(block.id) { mutableStateOf<String?>(null) }
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
        DocumentSelectionColors(selection.clipboard) {
            BasicTextField(
                value = fieldValue,
                visualTransformation = selection.transformation,
                onTextLayout = {
                    find.onTextLayout(it)
                    selection.onTextLayout(it)
                    if (it.lineCount > 0) {
                        onFirstLineCenterChanged(with(density) {
                            ((it.getLineTop(0) + it.getLineBottom(0)) / 2).toDp()
                        })
                    }
                },
                onValueChange = onValueChange@{ incoming ->
                    selection.acceptInput(incoming)?.let { fieldValue = it; return@onValueChange }
                    val previous = fieldValue
                    val delta = TextEditDelta.between(previous, incoming)
                    if (linkController?.available == true && block.type == BlockTypes.PARAGRAPH && previous.text.isBlank() && incoming.composition == null && delta.insertedText.length > 4) {
                        com.oruke.tagloom.core.model.LinkUrl.parse(incoming.text)?.let { pastedLinkUrl = it.value }
                    }
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
                    // 跨块命令可能已更新模型；失焦时原生字段的旧选区回调不能把旧正文写回。
                    val textChanged = previous.text != incoming.text
                    if (textChanged) state.updateBlockText(block.id, incoming.text)
                    if (focused || textChanged) state.updateSelection(
                        block.id,
                        incoming.selection.start,
                        incoming.selection.end
                    )
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
                    .testTag("text-input-${block.id.value}")
                    .then(find.modifier)
                    .then(selection.modifier)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        focused = focusState.isFocused
                        if (focusState.isFocused && !selection.ownsSelection) {
                            state.updateSelection(block.id, fieldValue.selection.start, fieldValue.selection.end)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (selection.onKeyEvent(event)) return@onPreviewKeyEvent true
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
                                        onCreateLink = linkController?.let { links -> { id -> links.begin(id) } },
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
        }
        pastedLinkUrl?.let { url -> LinkPasteChoices(state, block.id, url, { pastedLinkUrl = null }, onCursorRequest) }
        TagloomDropdownMenu(
            expanded = showCommandMenu && !tablePickerOpen && pastedLinkUrl == null,
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
                                onCreateLink = linkController?.let { links -> { id -> links.begin(id) } },
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
