package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.insertBlockAfter
import com.oruke.tagloom.editor.numberedListOrdinal
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_block_placeholder_default
import tagloom.app.shared.generated.resources.editor_block_placeholder_heading
import tagloom.app.shared.generated.resources.editor_block_placeholder_list
import tagloom.app.shared.generated.resources.editor_block_placeholder_quote
import tagloom.app.shared.generated.resources.editor_block_placeholder_todo
import tagloom.app.shared.generated.resources.editor_block_preserved_data
import tagloom.app.shared.generated.resources.editor_block_read_only
import tagloom.app.shared.generated.resources.editor_block_unknown_uneditable

/**
 * 渲染一行没有卡片边框的 Block，并按需显示加号和拖动把手。
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
    val interactionSource = remember(block.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = state.selection.blockId == block.id
    var actionMenuOpen by remember(block.id) { mutableStateOf(false) }
    var typeMenuOpen by remember(block.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (depth * 22).dp,
                top = blockTopSpacing(block),
                bottom = blockBottomSpacing(block)
            )
            .hoverable(interactionSource),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (databaseController?.isCreatingBlock(block.id) == true) {
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
        Box(
            modifier = Modifier.align(Alignment.TopStart).offset(x = -BLOCK_GUTTER_WIDTH),
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
        }
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
 * @param databaseCreationAvailable 当前平台是否装配 Database 写入入口。
 * @param onCreateDatabaseBlock 请求通过当前 Block 创建并绑定 Database。
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
    databaseCreationAvailable: Boolean,
    onCreateDatabaseBlock: (BlockId) -> Unit,
    onSaveRequest: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = blockVerticalAlignment(block)) {
        when (val content = block.content) {
            is TodoBlockContent -> {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val checked = content.checked
                val outlineColor = MaterialTheme.colorScheme.outline
                val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
                val primaryColor = MaterialTheme.colorScheme.primary
                
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(24.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { state.toggleTodo(block.id) }
                        )
                        .hoverable(interactionSource),
                    contentAlignment = Alignment.TopCenter
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.padding(top = 2.5.dp).size(16.dp)) {
                        val cornerRadius = 3.5.dp.toPx()
                        val strokeWidth = 1.5.dp.toPx()
                        
                        if (checked) {
                            drawRoundRect(
                                color = primaryColor,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                            )
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.28f, size.height * 0.5f)
                                lineTo(size.width * 0.45f, size.height * 0.68f)
                                lineTo(size.width * 0.72f, size.height * 0.32f)
                            }
                            drawPath(
                                path = path,
                                color = androidx.compose.ui.graphics.Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.8.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        } else {
                            val borderColor = if (isHovered) outlineColor else outlineVariantColor
                            drawRoundRect(
                                color = borderColor,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                            )
                        }
                    }
                }
            }
            else -> blockPrefix(state, block)?.let { prefix ->
                Box(
                    modifier = Modifier.width(22.dp).height(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        prefix,
                        style = blockTextStyle(block),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val quoteBorderColor = MaterialTheme.colorScheme.primary
        val quoteModifier = if (block.type == BlockTypes.QUOTE) {
            Modifier.weight(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(0.dp, 4.dp, 4.dp, 0.dp)
                )
                .drawBehind {
                    val borderThickness = 3.5.dp.toPx()
                    val cornerRadius = 1.75.dp.toPx()
                    drawRoundRect(
                        color = quoteBorderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(borderThickness, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                    )
                }
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
        } else {
            Modifier.weight(1f).let {
                if (block.type == BlockTypes.TODO && (block.content as? TodoBlockContent)?.checked == true) {
                    it.alpha(0.6f)
                } else {
                    it
                }
            }
        }
        
        EditorRichTextField(
            state = state,
            block = block,
            textStyle = if (block.type == BlockTypes.QUOTE) {
                blockTextStyle(block).copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                blockTextStyle(block)
            },
            placeholder = blockPlaceholder(block),
            cursorRequest = cursorRequest,
            commandMenuRequested = commandMenuRequested,
            onCursorRequest = onCursorRequest,
            onCursorConsumed = onCursorConsumed,
            onCommandMenuDismiss = onCommandMenuDismiss,
            databaseCreationAvailable = databaseCreationAvailable,
            onCreateDatabaseBlock = onCreateDatabaseBlock,
            onSaveRequest = onSaveRequest,
            modifier = quoteModifier,
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
        1 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    }
    else -> MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
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
    else -> null
}

/**
 * 返回 Block 内容与 gutter 的垂直对齐方式。
 *
 * @param block 当前 Block。
 * @return 代码块顶部对齐，其他内容居中。
 */
private fun blockVerticalAlignment(block: Block): Alignment.Vertical = Alignment.Top

/**
 * 返回不同 Block 之间的排版节奏。
 *
 * @param block 当前 Block。
 * @return 标题和代码使用更大下间距。
 */
private fun blockBottomSpacing(block: Block) = when {
    block.content is DatabaseBlockContent || block.content is BaseTableBlockContent || block.content == TabsBlockContent -> 16.dp
    block.content is HeadingBlockContent -> when ((block.content as HeadingBlockContent).level) {
        1 -> 6.dp
        2 -> 4.dp
        else -> 4.dp
    }
    block.type == BlockTypes.CODE -> 14.dp
    block.type == BlockTypes.DIVIDER -> 0.dp
    block.type == BlockTypes.QUOTE -> 8.dp
    block.type in setOf(BlockTypes.BULLETED_LIST, BlockTypes.NUMBERED_LIST, BlockTypes.TODO) -> 3.dp
    else -> 8.dp
}

private fun blockTopSpacing(block: Block) = when {
    block.content is DatabaseBlockContent || block.content is BaseTableBlockContent || block.content == TabsBlockContent -> 8.dp
    block.content is HeadingBlockContent -> when ((block.content as HeadingBlockContent).level) {
        1 -> 20.dp
        2 -> 16.dp
        else -> 12.dp
    }
    block.type == BlockTypes.CODE -> 6.dp
    block.type == BlockTypes.QUOTE -> 4.dp
    block.type in setOf(BlockTypes.BULLETED_LIST, BlockTypes.NUMBERED_LIST, BlockTypes.TODO) -> 0.dp
    block.type == BlockTypes.DIVIDER -> 0.dp
    else -> 0.dp
}
