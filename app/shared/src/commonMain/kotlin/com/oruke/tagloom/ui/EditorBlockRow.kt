package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.insertBlockAfter

/**
 * 渲染连续文档中的 Block，并在完整命中区域内提供统一操作手柄。
 *
 * @param state 编辑器状态。
 * @param databaseController Database Block 可用的目录与写入控制器。
 * @param block 当前 Block。
 * @param depth 嵌套深度。
 * @param cursorRequest 外部焦点请求。
 * @param commandMenuBlockId 当前强制打开 Slash 菜单的 Block。
 * @param onCursorRequest 请求光标移动。
 * @param onCursorConsumed 已消费光标请求。
 * @param onCommandMenuRequest 请求指定 Block 打开命令菜单。
 * @param onCommandMenuDismiss 关闭命令菜单。
 * @param onCreateDatabaseBlock 请求通过当前 Block 创建并绑定 Database。
 * @param onOpenNote 从 Database 投影打开原始 Note。
 * @param onSaveRequest 保存快捷键动作。
 */
@Composable
internal fun EditorBlockRow(
    state: TagloomEditorState,
    databaseController: DatabaseEditorController?,
    block: Block,
    depth: Int,
    cursorRequest: EditorCursorTarget?,
    commandMenuBlockId: BlockId?,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCursorConsumed: () -> Unit,
    onCommandMenuRequest: (BlockId) -> Unit,
    onCommandMenuDismiss: () -> Unit,
    onCreateDatabaseBlock: (BlockId) -> Unit,
    onOpenNote: (ObjectId) -> Unit,
    onSaveRequest: () -> Unit,
) {
    val links = LocalWorkspaceLinks.current
    val interactionSource = remember(block.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = state.selection.blockId == block.id
    val linkEntryVisible = links?.entry?.let { it.blockId == block.id && it.sourceId == state.selectedDocumentId } == true
    // 用实际文字排版度量，空行、标题及平台字体的首行高度不能由按钮尺寸推断。
    var firstLineCenter by remember(block.id, block.type, linkEntryVisible) { mutableStateOf<Dp?>(null) }
    var actionMenuOpen by remember(block.id) { mutableStateOf(false) }
    var typeMenuOpen by remember(block.id) { mutableStateOf(false) }
    val citedHighlight = LocalWorkspaceAgentHarness.current?.highlightedCitation?.let {
        it.noteId == state.selectedDocumentId && it.blockId == block.id
    } == true
    val citationModifier = if (citedHighlight) {
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f), MaterialTheme.shapes.small)
            .testTag("cited-highlighted-block-${block.id.value}")
    } else {
        Modifier
    }
    BlockDragFrame(block) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(citationModifier)
                .padding(start = (depth * 22).dp, top = blockTopSpacing(block), bottom = blockBottomSpacing(block))
                .hoverable(interactionSource),
        ) {
            BlockGutter(
                blockId = block.id,
                visible = hovered || active || actionMenuOpen || typeMenuOpen,
                firstLineCenter = firstLineCenter,
                onOpenActions = { actionMenuOpen = true },
                menu = {
                    BlockActionMenus(
                        state = state,
                        block = block,
                        actionMenuOpen = actionMenuOpen,
                        typeMenuOpen = typeMenuOpen,
                        onActionMenuChange = { actionMenuOpen = it },
                        onTypeMenuChange = { typeMenuOpen = it },
                        onCursorRequest = onCursorRequest,
                        onInsertAfter = {
                            state.insertBlockAfter(block.id)?.let { target ->
                                onCursorRequest(target)
                                onCommandMenuRequest(target.blockId)
                            }
                        },
                    )
                },
            )
            Box(Modifier.weight(1f)) {
                if (linkEntryVisible) {
                    LinkEntryForm(links, state, onCursorRequest)
                } else if (databaseController?.isCreatingBlock(block.id) == true) {
                    DatabaseBlockCreatingPlaceholder()
                } else when (val content = block.content) {
                    is BaseTableBlockContent -> BaseTableBlockEditor(
                        state = state,
                        block = block,
                        content = content,
                    )

                    TabsBlockContent -> TabsBlockEditor(
                        state = state,
                        databaseController = databaseController,
                        block = block,
                        cursorRequest = cursorRequest,
                        commandMenuBlockId = commandMenuBlockId,
                        onCursorRequest = onCursorRequest,
                        onCursorConsumed = onCursorConsumed,
                        onCommandMenuRequest = onCommandMenuRequest,
                        onCommandMenuDismiss = onCommandMenuDismiss,
                        onCreateDatabaseBlock = onCreateDatabaseBlock,
                        onOpenNote = onOpenNote,
                        onSaveRequest = onSaveRequest,
                    )

                    is DatabaseBlockContent -> DatabaseBlockEditor(
                        controller = databaseController,
                        content = content,
                        onOpenNote = onOpenNote,
                    )

                    is com.oruke.tagloom.core.model.NoteReferenceBlockContent -> LinkReferenceBlock(state, block, onOpenNote)

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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                    )

                    else -> if (block.editableRichText() != null) {
                        TextBlockContent(
                            state = state,
                            block = block,
                            onFirstLineCenterChanged = { firstLineCenter = it },
                            cursorRequest = cursorRequest,
                            commandMenuRequested = commandMenuBlockId == block.id,
                            onCursorRequest = onCursorRequest,
                            onCursorConsumed = onCursorConsumed,
                            onCommandMenuDismiss = onCommandMenuDismiss,
                            databaseCreationAvailable = databaseController != null,
                            onCreateDatabaseBlock = onCreateDatabaseBlock,
                            onSaveRequest = onSaveRequest,
                        )
                    } else {
                        ReadOnlyBlockPlaceholder(block)
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(BLOCK_GUTTER_WIDTH))
        }
    }
}
