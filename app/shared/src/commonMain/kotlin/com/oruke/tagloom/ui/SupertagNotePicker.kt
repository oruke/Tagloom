package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.findSupertags
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 在窗口正中或窄屏底部搜索超级标签，确认之前不会创建任何笔记。
 * @param state 已解锁目录。
 * @param compact 窄屏时使用底部面板。
 * @param onSelect 确认选择并创建笔记。
 * @param onCreate 创建新超级标签，预填检索词。
 * @param onManage 打开管理页。
 * @param onDismiss 取消本次新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupertagNotePicker(
    state: TagloomEditorState,
    compact: Boolean,
    onSelect: (TagDefinition) -> Unit,
    onCreate: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (compact) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SupertagPickerContent(state, compact, onSelect, onCreate, onManage, onDismiss)
        }
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("supertag-picker"),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp
            ) {
                SupertagPickerContent(state, compact, onSelect, onCreate, onManage, onDismiss)
            }
        }
    }
}

/**
 * 渲染桌面与窄屏共用的搜索、键盘导航、空结果和管理入口。
 * @param state 已解锁目录。
 * @param compact 是否隐藏桌面快捷键说明。
 * @param onSelect 确认当前候选。
 * @param onCreate 创建匹配名称的超级标签。
 * @param onManage 打开目录管理。
 * @param onDismiss 取消创建。
 */
@Composable
private fun SupertagPickerContent(
    state: TagloomEditorState, compact: Boolean, onSelect: (TagDefinition) -> Unit,
    onCreate: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(query) { mutableStateOf(0) }
    val preset = if (LocalWorkspaceLinks.current?.available == true && state.tagDefinitions.none { it.id == com.oruke.tagloom.core.workspace.LinkNoteSchemas.TYPE_ID }) {
        listOf(com.oruke.tagloom.core.workspace.LinkNoteSchemas.tag(stringResource(Res.string.link_type)))
            .filter { query.isBlank() || it.name.contains(query, true) || "link".contains(query, true) }
    } else emptyList()
    val matches = state.findSupertags(query) + preset
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(
        selected,
        query
    ) { if (matches.isNotEmpty()) list.animateScrollToItem(selected.coerceAtMost(matches.lastIndex)) }
    val inputKeys = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.Escape -> {
                onDismiss(); true
            }

            Key.DirectionDown -> {
                selected = (selected + 1).coerceAtMost((matches.size - 1).coerceAtLeast(0)); true
            }

            Key.DirectionUp -> {
                selected = (selected - 1).coerceAtLeast(0); true
            }

            Key.Enter -> {
                matches.getOrNull(selected)?.let(onSelect); true
            }

            else -> false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.editor_action_new_note), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(Res.string.supertag_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onDismiss) {
                Icon(
                    painterResource(Res.drawable.ic_close),
                    stringResource(Res.string.editor_action_close)
                )
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.padding(20.dp).fillMaxWidth().focusRequester(focus).then(inputKeys)
                .testTag("supertag-picker-search"),
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.supertag_search)) },
            leadingIcon = { Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(20.dp)) })
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 320.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            itemsIndexed(matches, key = { _, tag -> tag.id.value }) { index, tag ->
                Surface(
                    color = if (index == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(tag) }.padding(12.dp)
                            .testTag("supertag-picker-item-${tag.id.value}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SupertagIcon(tag.iconKey)
                        Column(Modifier.weight(1f)) {
                            Text(tag.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                supertagSummary(state, tag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (matches.isEmpty()) item {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.supertag_empty), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Res.string.supertag_empty_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { onCreate(query.trim()) }) {
                Icon(painterResource(Res.drawable.ic_plus), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.editor_tag_create))
            }
            TextButton(onClick = onManage) { Text(stringResource(Res.string.supertag_manage)) }
        }
        if (!compact) Text(
            stringResource(Res.string.supertag_keyboard_hint), Modifier.padding(start = 24.dp, bottom = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 根据实际属性绑定与父级生成一行摘要。
 * @param state 当前目录。
 * @param tag 待展示超级标签。
 * @return 本地化摘要，Note 明确不附带状态和日期。
 */
@Composable
internal fun supertagSummary(state: TagloomEditorState, tag: TagDefinition): String =
    if (tag.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) stringResource(Res.string.supertag_plain_summary)
    else stringResource(
        Res.string.supertag_summary, tag.propertyBindings.size,
        tag.parentTagIds.mapNotNull { id -> state.tagDefinitions.firstOrNull { it.id == id }?.name }.joinToString(" / ")
            .ifEmpty { "Note" })
