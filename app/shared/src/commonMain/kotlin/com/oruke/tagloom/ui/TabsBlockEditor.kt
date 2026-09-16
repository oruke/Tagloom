package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import tagloom.app.shared.generated.resources.editor_tabs_add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.editor.BlockMoveDirection
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.activeTabId
import com.oruke.tagloom.editor.addTab
import com.oruke.tagloom.editor.deleteTab
import com.oruke.tagloom.editor.duplicateTab
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.moveTab
import com.oruke.tagloom.editor.renameTab
import com.oruke.tagloom.editor.selectTab
import com.oruke.tagloom.editor.visibleBlockDepth
import com.oruke.tagloom.editor.visibleBlocksInTab
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.workspace_action_cancel
import tagloom.app.shared.generated.resources.editor_action_rename
import tagloom.app.shared.generated.resources.editor_tabs_delete
import tagloom.app.shared.generated.resources.editor_tabs_delete_cascade
import tagloom.app.shared.generated.resources.editor_tabs_delete_unpack
import tagloom.app.shared.generated.resources.editor_tabs_duplicate
import tagloom.app.shared.generated.resources.editor_tabs_move_left
import tagloom.app.shared.generated.resources.editor_tabs_move_right
import tagloom.app.shared.generated.resources.editor_tabs_rename_hint
import tagloom.app.shared.generated.resources.editor_tabs_untitled
import org.jetbrains.compose.resources.pluralStringResource
import tagloom.app.shared.generated.resources.editor_tabs_content_count

/**
 * 渲染 Stitch 定稿的 Tabs 容器，并把活动页正文真正放入容器边框内。
 *
 * @param state 编辑器状态。
 * @param databaseController Database Block 可用的目录与写入控制器。
 * @param block 当前 Tabs 容器 Block。
 * @param cursorRequest 外部焦点请求。
 * @param commandMenuBlockId 当前强制打开 Slash 菜单的 Block。
 * @param onCursorRequest 请求光标移动。
 * @param onCursorConsumed 已消费光标请求。
 * @param onCommandMenuRequest 请求指定 Block 打开命令菜单。
 * @param onCommandMenuDismiss 关闭命令菜单。
 * @param onCreateDatabaseBlock 请求创建并绑定 Database。
 * @param onOpenNote 从 Database 投影打开原始 Note。
 * @param onSaveRequest 保存快捷键动作。
 */
@Composable
internal fun TabsBlockEditor(
    state: TagloomEditorState,
    databaseController: DatabaseEditorController?,
    block: Block,
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
    val note = state.selectedDocument?.note ?: return
    val tabs = note.blocks
        .filter { child -> !child.deleted && child.parentId == block.id && child.type == BlockTypes.TAB }
        .sortedWith(compareBy(Block::orderKey, { child -> child.id.value }))
    val activeTabId = state.activeTabId(block.id)
    var renamingTabId by remember(block.id) { mutableStateOf<BlockId?>(null) }
    var deleteRequest by remember(block.id) { mutableStateOf<BlockId?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TAB_STRIP_HEIGHT)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                TabHeader(
                    state = state,
                    tabsBlock = block,
                    tab = tab,
                    selected = tab.id == activeTabId,
                    renaming = tab.id == renamingTabId,
                    onRenameStart = { renamingTabId = tab.id },
                    onRenameEnd = { renamingTabId = null },
                    onDeleteRequest = {
                        if (tabHasUserContent(tab.id, note.blocks)) {
                            deleteRequest = tab.id
                        } else {
                            state.deleteTab(block.id, tab.id, cascade = true)
                        }
                    },
                )
            }
            QuietTabAction("+", stringResource(Res.string.editor_tabs_add)) {
                state.addTab(block.id)?.let { tabId -> renamingTabId = tabId }
            }
        }
        if (renamingTabId != null) {
            Text(
                stringResource(Res.string.editor_tabs_rename_hint),
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        deleteRequest?.let { tabId ->
            TabDeleteConfirmation(
                blockCount = tabUserContentCount(tabId, note.blocks),
                onCancel = { deleteRequest = null },
                onDeleteContents = {
                    state.deleteTab(block.id, tabId, cascade = true)
                    deleteRequest = null
                },
                onMoveContents = {
                    state.deleteTab(block.id, tabId, cascade = false)
                    deleteRequest = null
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            state.visibleBlocksInTab(block.id, note).forEach { child ->
                EditorBlockRow(
                    state = state,
                    databaseController = databaseController,
                    block = child,
                    depth = state.visibleBlockDepth(child, note),
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
            }
        }
    }
}

/**
 * 渲染一个页签及悬停时出现的下拉菜单入口。
 *
 * @param state 编辑器状态。
 * @param tabsBlock 所属 Tabs 容器。
 * @param tab 当前页签。
 * @param selected 是否为会话活动页签。
 * @param renaming 是否正在内联重命名。
 * @param onRenameStart 开始重命名。
 * @param onRenameEnd 完成或取消重命名。
 * @param onDeleteRequest 请求安全删除。
 */
@Composable
private fun TabHeader(
    state: TagloomEditorState,
    tabsBlock: Block,
    tab: Block,
    selected: Boolean,
    renaming: Boolean,
    onRenameStart: () -> Unit,
    onRenameEnd: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val content = tab.content as? TabBlockContent ?: return
    val untitled = stringResource(Res.string.editor_tabs_untitled)
    val find = rememberNoteSearchDecoration(NoteSearchTarget(NoteSearchField.TAB, tab.id), editable = renaming)
    val interactionSource = remember(tab.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember(tab.id) { mutableStateOf(false) }
    var draft by remember(tab.id, renaming) { mutableStateOf(content.title.plainText) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    if (renaming) {
        LaunchedEffect(tab.id) { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .widthIn(min = 68.dp, max = 220.dp)
            .height(28.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp),
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !renaming,
            ) {
                if (!selected) {
                    state.selectTab(tabsBlock.id, tab.id)
                } else {
                    menuOpen = !menuOpen
                }
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (renaming) {
            Box(Modifier.widthIn(min = 68.dp, max = 160.dp).horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = draft,
                    visualTransformation = find.transformation(),
                    onTextLayout = find.onTextLayout,
                    onValueChange = { value -> draft = value },
                    modifier = Modifier
                        .widthIn(min = 68.dp)
                        .height(28.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp)
                        .focusRequester(focusRequester)
                        .then(find.modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else when (event.key) {
                                Key.Enter -> {
                                    state.renameTab(tab.id, draft.ifBlank { untitled })
                                    onRenameEnd()
                                    true
                                }

                                Key.Escape -> {
                                    onRenameEnd()
                                    true
                                }

                                else -> false
                            }
                        },
                    textStyle = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                )
            }
        } else {
            // 页签保持紧凑宽度，长标题在自身视口滚动，查找能显示末尾的匹配字符。
            Box(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                Text(
                    text = find.highlight(AnnotatedString(content.title.plainText)),
                    modifier = find.modifier,
                    onTextLayout = find.onTextLayout,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Box {
            if (!renaming && (selected || menuOpen)) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .height(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TagloomChevronDown(Modifier.size(12.dp))
                }
            }
            TagloomDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.widthIn(min = 160.dp),
            ) {
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_action_rename),
                    leadingIcon = { Text("✎", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onRenameStart()
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_tabs_duplicate),
                    leadingIcon = { Text("▣", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        state.duplicateTab(tabsBlock.id, tab.id)
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_tabs_move_left),
                    leadingIcon = { Text("←", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        state.moveTab(tabsBlock.id, tab.id, BlockMoveDirection.UP)
                        menuOpen = false
                    },
                )
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_tabs_move_right),
                    leadingIcon = { Text("→", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        state.moveTab(tabsBlock.id, tab.id, BlockMoveDirection.DOWN)
                        menuOpen = false
                    },
                )
                TagloomMenuDivider()
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_tabs_delete),
                    leadingIcon = {
                        Text(
                            "♲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    destructive = true,
                    onClick = {
                        onDeleteRequest()
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * 渲染不抢占视觉层级的页签图标动作。
 *
 * @param label 可见符号。
 * @param description 当前语言的无障碍动作名称。
 * @param onClick 点击动作。
 */
@Composable
private fun QuietTabAction(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .widthIn(min = 24.dp)
            .height(24.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 在 Tabs 内部渲染非模态删除确认条。
 *
 * @param blockCount 将受影响的用户内容 Block 数量。
 * @param onCancel 取消删除。
 * @param onDeleteContents 连同内容删除。
 * @param onMoveContents 把内容移到容器外后删除页签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabDeleteConfirmation(
    blockCount: Int,
    onCancel: () -> Unit,
    onDeleteContents: () -> Unit,
    onMoveContents: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "⚠  ${pluralStringResource(Res.plurals.editor_tabs_content_count, blockCount, blockCount)}",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium
        )
        Text(stringResource(Res.string.workspace_action_cancel), modifier = Modifier.clickable(onClick = onCancel), style = MaterialTheme.typography.labelMedium)
        TabDeleteAction(stringResource(Res.string.editor_tabs_delete_cascade), destructive = true, filled = false, onClick = onDeleteContents)
        TabDeleteAction(stringResource(Res.string.editor_tabs_delete_unpack), destructive = false, filled = true, onClick = onMoveContents)
    }
}

/**
 * 渲染安全删除条中的明确结果按钮。
 *
 * @param label 动作结果文本。
 * @param destructive 是否会连同正文删除。
 * @param filled 是否使用主色填充。
 * @param onClick 点击动作。
 */
@Composable
private fun TabDeleteAction(
    label: String,
    destructive: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.heightIn(min = 30.dp)
            .background(if (filled) color else MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else color,
        )
    }
}

/**
 * 判断页签是否包含需要保护的用户正文。
 *
 * @param tabId 页签 ID。
 * @param blocks Note 全部 Block。
 * @return 存在非空文本、代码或结构化 Block 时为 true。
 */
private fun tabHasUserContent(
    tabId: BlockId,
    blocks: List<Block>,
): Boolean = tabUserContentCount(tabId, blocks) > 0

/**
 * 统计一个页签后代中需要保护的用户内容 Block。
 *
 * @param tabId 页签 ID。
 * @param blocks Note 全部 Block。
 * @return 非空文本、代码或结构化 Block 数量。
 */
private fun tabUserContentCount(
    tabId: BlockId,
    blocks: List<Block>,
): Int {
    val byId = blocks.associateBy(Block::id)
    return blocks.count { block ->
        if (block.deleted || block.type == BlockTypes.TAB || block.type == BlockTypes.TABS) {
            false
        } else {
            var parentId = block.parentId
            var inside = false
            val visited = mutableSetOf<BlockId>()
            while (parentId != null && visited.add(parentId)) {
                if (parentId == tabId) {
                    inside = true
                    break
                }
                parentId = byId[parentId]?.parentId
            }
            inside && when (val content = block.content) {
                is CodeBlockContent -> content.text.isNotBlank()
                else -> block.editableRichText()?.plainText?.isNotBlank() ?: true
            }
        }
    }
}

/** Tabs 顶部固定条高度。 */
private val TAB_STRIP_HEIGHT = 32.dp
