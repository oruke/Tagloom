package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_editor_attach_blocks_toast
import tagloom.app.shared.generated.resources.agent_editor_attach_locked_toast
import tagloom.app.shared.generated.resources.agent_editor_block_attach_ai
import tagloom.app.shared.generated.resources.agent_editor_selection_ask_ai
import tagloom.app.shared.generated.resources.ic_sparkle

/**
 * 渲染编辑器正文选区浮动栏（`SelectionFormattingToolbar`）末尾的 `✦ 询问 / 润色` 快捷挂载按钮。
 *
 * 点击后校验当前笔记有效 `AiAccessPolicy`，将选区覆盖的一个或多个稳定 `BlockId` 挂载至右侧伴随栏并使作曲框直接获焦。
 *
 * @param state 当前编辑器状态。
 * @param controller 工作区共享 Agent 控制器。
 */
@Composable
internal fun SelectionAskAiButton(
    state: TagloomEditorState,
    controller: WorkspaceAgentHarnessController,
) {
    val selectedDoc = state.selectedDocument ?: return
    val blockIds = state.selection.ids.toList()
    if (blockIds.isEmpty()) return
    val lockedToast = stringResource(Res.string.agent_editor_attach_locked_toast)
    val attachedToast = stringResource(Res.string.agent_editor_attach_blocks_toast, blockIds.size)

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.22f)),
    )
    Row(
        modifier = Modifier
            .clickable {
                controller.attachBlocksFromEditorQuickAction(
                    noteId = selectedDoc.id,
                    blockIds = blockIds,
                    lockedMessage = lockedToast,
                    attachedMessage = attachedToast,
                )
            }
            .background(
                color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.18f),
                shape = RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("btn-selection-ask-ai"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_sparkle),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.inversePrimary,
        )
        Text(
            text = stringResource(Res.string.agent_editor_selection_ask_ai),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}

/**
 * 渲染块手柄菜单（`BlockActionMenus`）中的「引用此块到 Agent」快捷动作项（支持任意正文块、代码块、`BaseTable`、`Tabs`）。
 *
 * @param state 当前编辑器状态。
 * @param blockId 当前手柄所属的稳定段落 ID。
 * @param controller 工作区共享 Agent 控制器。
 * @param onDismissMenu 点击后关闭块菜单的回调。
 */
@Composable
internal fun BlockMenuAttachToAgentItem(
    state: TagloomEditorState,
    blockId: BlockId,
    controller: WorkspaceAgentHarnessController,
    onDismissMenu: () -> Unit,
) {
    val selectedDoc = state.selectedDocument ?: return
    val targetIds = if (state.selection.ids.size > 1 && blockId in state.selection.ids) {
        state.selection.ids.toList()
    } else {
        listOf(blockId)
    }
    val lockedToast = stringResource(Res.string.agent_editor_attach_locked_toast)
    val attachedToast = stringResource(Res.string.agent_editor_attach_blocks_toast, targetIds.size)

    TagloomMenuItem(
        text = stringResource(Res.string.agent_editor_block_attach_ai),
        leadingIcon = {
            Icon(
                painter = painterResource(Res.drawable.ic_sparkle),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.testTag("block-menu-attach-agent-${blockId.value}"),
        onClick = {
            onDismissMenu()
            controller.attachBlocksFromEditorQuickAction(
                noteId = selectedDoc.id,
                blockIds = targetIds,
                lockedMessage = lockedToast,
                attachedMessage = attachedToast,
            )
        },
    )
}

/**
 * 在笔记编辑页底部居中展示选区或块手柄快捷挂载触发的瞬时反馈胶囊（如 `LOCKED` 禁读拦截提示）。
 *
 * @param controller 工作区共享 Agent 控制器。
 * @param modifier 外部对齐与边距修饰符。
 */
@Composable
internal fun EditorAgentQuickAttachToast(
    controller: WorkspaceAgentHarnessController,
    documentId: ObjectId? = null,
    modifier: Modifier = Modifier,
) {
    var previousDocId by remember { mutableStateOf(documentId) }
    if (previousDocId != documentId) {
        previousDocId = documentId
        controller.editorToastMessage = null
    }
    val message = controller.editorToastMessage ?: return
    LaunchedEffect(message) {
        delay(2000)
        if (controller.editorToastMessage == message) {
            controller.editorToastMessage = null
        }
    }
    Surface(
        modifier = modifier
            .clickable { controller.editorToastMessage = null }
            .testTag("editor-agent-toast"),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.small,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
