package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.widthIn
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
import tagloom.app.shared.generated.resources.*

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
 * @param onInsertAfter 在下方插入并打开 Block 类型菜单。
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
    onInsertAfter: () -> Unit,
) {
    TagloomDropdownMenu(
        expanded = actionMenuOpen, onDismissRequest = { onActionMenuChange(false) },
        modifier = Modifier.widthIn(min = 180.dp).onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                onActionMenuChange(false); true
            } else false
        }) {
        TagloomMenuItem(
            text = stringResource(Res.string.editor_block_insert_after),
            onClick = { onActionMenuChange(false); onInsertAfter() },
        )
        LocalWorkspaceAgentHarness.current?.takeIf { it.available }?.let { agentController ->
            BlockMenuAttachToAgentItem(
                state = state,
                blockId = block.id,
                controller = agentController,
                onDismissMenu = { onActionMenuChange(false) },
            )
        }
        TagloomMenuDivider()
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
    TagloomDropdownMenu(
        expanded = typeMenuOpen, onDismissRequest = { onTypeMenuChange(false) },
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                onTypeMenuChange(false); true
            } else false
        }) {
        EditorBlockCommands.filter(EditorBlockCommand::convertible).forEach { command ->
            EditorBlockCommandItem(command) {
                state.turnBlockInto(block.id, command.type)?.let(onCursorRequest)
                onTypeMenuChange(false)
            }
        }
    }
}
