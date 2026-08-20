package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.deleteBlockAndFocus
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.turnBlockInto
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_block_action_convert
import tagloom.app.shared.generated.resources.editor_block_action_delete
import tagloom.app.shared.generated.resources.editor_block_action_indent
import tagloom.app.shared.generated.resources.editor_block_action_move_down
import tagloom.app.shared.generated.resources.editor_block_action_move_up
import tagloom.app.shared.generated.resources.editor_block_action_outdent
import tagloom.app.shared.generated.resources.editor_block_actions_description
import tagloom.app.shared.generated.resources.editor_block_add_description

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
internal fun BlockActionMenus(
    state: TagloomEditorState,
    block: Block,
    actionMenuOpen: Boolean,
    typeMenuOpen: Boolean,
    onActionMenuChange: (Boolean) -> Unit,
    onTypeMenuChange: (Boolean) -> Unit,
    onCursorRequest: (EditorCursorTarget) -> Unit,
) {
    TagloomDropdownMenu(expanded = actionMenuOpen, onDismissRequest = { onActionMenuChange(false) }) {
        if (block.editableRichText() != null || block.content == DividerBlockContent) {
            TagloomMenuItem(
                text = stringResource(Res.string.editor_block_action_convert),
                onClick = {
                    onActionMenuChange(false)
                    onTypeMenuChange(true)
                },
            )
        }
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_action_move_up),
            onClick = {
                state.moveBlock(block.id, BlockMoveDirection.UP)
                onActionMenuChange(false)
            },
        )
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_action_move_down),
            onClick = {
                state.moveBlock(block.id, BlockMoveDirection.DOWN)
                onActionMenuChange(false)
            },
        )
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_action_indent),
            onClick = {
                state.indentBlock(block.id)
                onActionMenuChange(false)
            },
        )
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_action_outdent),
            onClick = {
                state.outdentBlock(block.id)
                onActionMenuChange(false)
            },
        )
        TagloomMenuDivider()
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_action_delete),
            destructive = true,
            onClick = {
                state.deleteBlockAndFocus(block.id)?.let(onCursorRequest)
                onActionMenuChange(false)
            },
        )
    }
    TagloomDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { onTypeMenuChange(false) }) {
        EditorBlockCommands.filter(EditorBlockCommand::convertible).forEach { command ->
            EditorBlockCommandItem(command) {
                state.turnBlockInto(block.id, command.type)?.let(onCursorRequest)
                onTypeMenuChange(false)
            }
        }
    }
}

/**
 * 渲染 Block 左侧 24dp 交互区中的加号与拖动把手。
 *
 * @param visible 当前是否悬停、聚焦或打开菜单。
 * @param onAdd 新增后续 Block。
 * @param onMove 拖动达到阈值后的移动动作。
 * @param onOpenActions 打开 Block 菜单。
 */
@Composable
internal fun BlockGutter(
    visible: Boolean,
    onAdd: () -> Unit,
    onMove: (BlockMoveDirection) -> Unit,
    onOpenActions: () -> Unit,
    menu: @Composable () -> Unit = {},
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier.width(BLOCK_GUTTER_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible) {
            GutterButton(
                label = "+",
                description = stringResource(Res.string.editor_block_add_description),
                onClick = onAdd,
            )
            Box {
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
                menu()
            }
        }
    }
}

/**
 * 渲染 Block gutter 中一个无底色紧凑按钮。
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
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(12.dp, 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Block 左侧加号和拖动把手占用的固定宽度。 */
internal val BLOCK_GUTTER_WIDTH = 24.dp

/** 长按拖动把手触发一次 Block 移动的垂直距离。 */
private val DRAG_MOVE_THRESHOLD = 34.dp
