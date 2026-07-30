package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.deleteBlockAndFocus
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.insertBlockAfter
import com.oruke.tagloom.editor.numberedListOrdinal
import com.oruke.tagloom.editor.turnBlockInto
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染一行没有卡片边框的 Block，并按需显示加号和拖动把手。
 *
 * @param state 编辑器状态。
 * @param block 当前 Block。
 * @param depth 嵌套深度。
 * @param cursorRequest 外部焦点请求。
 * @param commandMenuRequested 是否强制打开 Slash 菜单。
 * @param onCursorRequest 请求光标移动。
 * @param onCursorConsumed 已消费光标请求。
 * @param onCommandMenuRequest 请求指定 Block 打开命令菜单。
 * @param onCommandMenuDismiss 关闭命令菜单。
 * @param onSaveRequest 保存快捷键动作。
 */
@Composable
internal fun EditorBlockRow(
    state: TagloomEditorState,
    block: Block,
    depth: Int,
    cursorRequest: EditorCursorTarget?,
    commandMenuRequested: Boolean,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCursorConsumed: () -> Unit,
    onCommandMenuRequest: (com.oruke.tagloom.core.model.BlockId) -> Unit,
    onCommandMenuDismiss: () -> Unit,
    onSaveRequest: () -> Unit,
) {
    val interactionSource = remember(block.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = state.selection.blockId == block.id
    var actionMenuOpen by remember(block.id) { mutableStateOf(false) }
    var typeMenuOpen by remember(block.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 22).dp, bottom = blockBottomSpacing(block))
            .hoverable(interactionSource),
        verticalAlignment = blockVerticalAlignment(block),
    ) {
        BlockGutter(
            visible = hovered || active || actionMenuOpen || typeMenuOpen,
            onAdd = {
                state.insertBlockAfter(block.id)?.let { target ->
                    onCursorRequest(target)
                    onCommandMenuRequest(target.blockId)
                }
            },
            onMove = { direction -> state.moveBlock(block.id, direction) },
            onOpenActions = { actionMenuOpen = true },
        )
        Box(Modifier.weight(1f)) {
            when (block.content) {
                is CodeBlockContent -> EditorCodeField(
                    state = state,
                    block = block,
                    cursorRequest = cursorRequest,
                    onCursorRequest = onCursorRequest,
                    onCursorConsumed = onCursorConsumed,
                    onSaveRequest = onSaveRequest,
                    modifier = Modifier.fillMaxWidth(),
                )
                DividerBlockContent -> HorizontalDivider(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                )
                else -> if (block.editableRichText() != null) {
                    TextBlockContent(
                        state = state,
                        block = block,
                        cursorRequest = cursorRequest,
                        commandMenuRequested = commandMenuRequested,
                        onCursorRequest = onCursorRequest,
                        onCursorConsumed = onCursorConsumed,
                        onCommandMenuDismiss = onCommandMenuDismiss,
                        onSaveRequest = onSaveRequest,
                    )
                } else {
                    ReadOnlyBlockPlaceholder(block)
                }
            }
            BlockActionMenus(
                state = state,
                block = block,
                actionMenuOpen = actionMenuOpen,
                typeMenuOpen = typeMenuOpen,
                onActionMenuChange = { open -> actionMenuOpen = open },
                onTypeMenuChange = { open -> typeMenuOpen = open },
                onCursorRequest = onCursorRequest,
            )
        }
    }
}

/**
 * 渲染 Block 的操作菜单与类型转换菜单。
 *
 * @param state 编辑器状态。
 * @param block 当前 Block。
 * @param actionMenuOpen 操作菜单是否打开。
 * @param typeMenuOpen 类型菜单是否打开。
 * @param onActionMenuChange 操作菜单状态变更。
 * @param onTypeMenuChange 类型菜单状态变更。
 * @param onCursorRequest 转换或删除后的焦点请求。
 */
@Composable
private fun BlockActionMenus(
    state: TagloomEditorState,
    block: Block,
    actionMenuOpen: Boolean,
    typeMenuOpen: Boolean,
    onActionMenuChange: (Boolean) -> Unit,
    onTypeMenuChange: (Boolean) -> Unit,
    onCursorRequest: (EditorCursorTarget) -> Unit,
) {
    DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { onActionMenuChange(false) }) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_convert)) }, onClick = {
            onActionMenuChange(false)
            onTypeMenuChange(true)
        })
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_move_up)) }, onClick = {
            state.moveBlock(block.id, BlockMoveDirection.UP)
            onActionMenuChange(false)
        })
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_move_down)) }, onClick = {
            state.moveBlock(block.id, BlockMoveDirection.DOWN)
            onActionMenuChange(false)
        })
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_indent)) }, onClick = {
            state.indentBlock(block.id)
            onActionMenuChange(false)
        })
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_outdent)) }, onClick = {
            state.outdentBlock(block.id)
            onActionMenuChange(false)
        })
        DropdownMenuItem(text = { Text(stringResource(Res.string.editor_block_action_delete)) }, onClick = {
            state.deleteBlockAndFocus(block.id)?.let(onCursorRequest)
            onActionMenuChange(false)
        })
    }
    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { onTypeMenuChange(false) }) {
        EditorBlockCommands.forEach { command ->
            EditorBlockCommandItem(command) {
                state.turnBlockInto(block.id, command.type)?.let(onCursorRequest)
                onTypeMenuChange(false)
            }
        }
    }
}

/**
 * 渲染 Block 左侧加号与可拖动把手。
 *
 * @param visible 当前是否悬停、聚焦或打开菜单。
 * @param onAdd 新增后续 Block。
 * @param onMove 拖动达到阈值后的移动动作。
 * @param onOpenActions 打开 Block 菜单。
 */
@Composable
private fun BlockGutter(
    visible: Boolean,
    onAdd: () -> Unit,
    onMove: (BlockMoveDirection) -> Unit,
    onOpenActions: () -> Unit,
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier.width(BLOCK_GUTTER_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible) {
            GutterButton("+", stringResource(Res.string.editor_block_add_description), onAdd)
            GutterButton(
                label = "⠿",
                description = stringResource(Res.string.editor_block_actions_description),
                onClick = onOpenActions,
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragDistance = 0f },
                        onDragEnd = { dragDistance = 0f },
                        onDragCancel = { dragDistance = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDistance += dragAmount.y
                            when {
                                dragDistance <= -DRAG_MOVE_THRESHOLD.toPx() -> {
                                    onMove(BlockMoveDirection.UP)
                                    dragDistance = 0f
                                }
                                dragDistance >= DRAG_MOVE_THRESHOLD.toPx() -> {
                                    onMove(BlockMoveDirection.DOWN)
                                    dragDistance = 0f
                                }
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * 渲染 Block gutter 中一个紧凑图标按钮。
 *
 * @param label 可见符号。
 * @param description 无障碍说明。
 * @param onClick 点击动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
private fun GutterButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(23.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 按 Block 类型渲染 Todo、列表前缀、引用和连续 RichText 输入。
 *
 * @param state 编辑器状态。
 * @param block 当前文本 Block。
 * @param cursorRequest 外部光标请求。
 * @param commandMenuRequested 是否强制打开 Slash 菜单。
 * @param onCursorRequest 请求光标移动。
 * @param onCursorConsumed 当前 Block 已获得焦点。
 * @param onCommandMenuDismiss 关闭 Slash 菜单。
 * @param onSaveRequest 保存快捷键动作。
 */
@Composable
private fun TextBlockContent(
    state: TagloomEditorState,
    block: Block,
    cursorRequest: EditorCursorTarget?,
    commandMenuRequested: Boolean,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCursorConsumed: () -> Unit,
    onCommandMenuDismiss: () -> Unit,
    onSaveRequest: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = blockVerticalAlignment(block)) {
        when (val content = block.content) {
            is TodoBlockContent -> Checkbox(
                checked = content.checked,
                onCheckedChange = { state.toggleTodo(block.id) },
                modifier = Modifier.size(30.dp).padding(end = 6.dp),
            )
            else -> blockPrefix(state, block)?.let { prefix ->
                Text(
                    prefix,
                    modifier = Modifier.width(30.dp),
                    style = blockTextStyle(block),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        EditorRichTextField(
            state = state,
            block = block,
            textStyle = blockTextStyle(block),
            placeholder = blockPlaceholder(block),
            cursorRequest = cursorRequest,
            commandMenuRequested = commandMenuRequested,
            onCursorRequest = onCursorRequest,
            onCursorConsumed = onCursorConsumed,
            onCommandMenuDismiss = onCommandMenuDismiss,
            onSaveRequest = onSaveRequest,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 为附件、引用或旧客户端未知 Block 渲染不会被误编辑写回的只读占位。
 *
 * @param block 当前不可编辑 Block。
 */
@Composable
private fun ReadOnlyBlockPlaceholder(block: Block) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (block.content is UnknownBlockContent) {
                    stringResource(Res.string.editor_block_unknown_uneditable)
                } else {
                    stringResource(Res.string.editor_block_read_only)
                },
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(Res.string.editor_block_preserved_data, block.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 返回 Block 的连续文档排版样式。
 *
 * @param block 当前 Block。
 * @return 标题或正文 TextStyle。
 */
@Composable
private fun blockTextStyle(block: Block) = when (val content = block.content) {
    is HeadingBlockContent -> when (content.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    else -> MaterialTheme.typography.bodyLarge
}

/**
 * 返回不同 Block 的空内容提示。
 *
 * @param block 当前 Block。
 * @return 只在未聚焦空 Block 中显示的提示。
 */
@Composable
private fun blockPlaceholder(block: Block): String = when (block.type) {
    BlockTypes.HEADING -> stringResource(Res.string.editor_block_placeholder_heading)
    BlockTypes.TODO -> stringResource(Res.string.editor_block_placeholder_todo)
    BlockTypes.BULLETED_LIST,
    BlockTypes.NUMBERED_LIST,
    -> stringResource(Res.string.editor_block_placeholder_list)
    BlockTypes.QUOTE -> stringResource(Res.string.editor_block_placeholder_quote)
    else -> stringResource(Res.string.editor_block_placeholder_default)
}

/**
 * 返回列表或引用 Block 的行首符号。
 *
 * @param state 编辑器状态。
 * @param block 当前 Block。
 * @return 非前缀类型返回空。
 */
private fun blockPrefix(
    state: TagloomEditorState,
    block: Block,
): String? = when (block.type) {
    BlockTypes.BULLETED_LIST -> "•"
    BlockTypes.NUMBERED_LIST -> "${state.numberedListOrdinal(block.id)}."
    BlockTypes.QUOTE -> "“"
    else -> null
}

/**
 * 返回 Block 内容与 gutter 的垂直对齐方式。
 *
 * @param block 当前 Block。
 * @return 代码块顶部对齐，其他内容居中。
 */
private fun blockVerticalAlignment(block: Block): Alignment.Vertical = when (block.content) {
    is CodeBlockContent,
    is UnknownBlockContent,
    -> Alignment.Top
    else -> Alignment.CenterVertically
}

/**
 * 返回不同 Block 之间的排版节奏。
 *
 * @param block 当前 Block。
 * @return 标题和代码使用更大下间距。
 */
private fun blockBottomSpacing(block: Block) = when (block.type) {
    BlockTypes.HEADING -> 13.dp
    BlockTypes.CODE -> 12.dp
    BlockTypes.DIVIDER -> 8.dp
    else -> 5.dp
}

/** Block 左侧加号和拖动把手占用的固定宽度。 */
internal val BLOCK_GUTTER_WIDTH = 52.dp

/** 长按拖动把手触发一次 Block 移动的垂直距离。 */
private val DRAG_MOVE_THRESHOLD = 34.dp
