package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 原位链接录入正文，Enter 确认、输入法组合态不提交，Esc 保留原段落并退出。
 * @param controller 当前链接业务编排。
 * @param state 当前笔记和目录。
 * @param onCursorRequest 确认后恢复来源正文焦点。
 */
@Composable
internal fun LinkEntryForm(
    controller: WorkspaceLinkController,
    state: TagloomEditorState,
    onCursorRequest: (EditorCursorTarget) -> Unit = {}
) {
    val draft = controller.entry ?: return
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var field by remember(draft.sourceId, draft.blockId) { mutableStateOf(TextFieldValue(draft.url)) }
    var groupMenu by remember { mutableStateOf(false) }
    val typeName = stringResource(Res.string.link_type)
    val groupName = stringResource(Res.string.link_group)
    val definition =
        state.propertyDefinitions.firstOrNull { it.id == LinkNoteSchemas.GROUP_ID } ?: LinkNoteSchemas.properties(
            groupName
        ).last()
    val url = LinkUrl.parse(draft.url)
    val duplicates = state.documents.filter {
        url != null && LinkNoteSchemas.TYPE_ID in it.note.directTagIds && LinkNotePolicy.address(it.note)?.value == url.value
    }
    val submit: () -> Unit = { scope.launch { controller.submit(typeName, groupName)?.let(onCursorRequest) } }
    // 选择分组的弹层会取得键盘焦点；关闭后回到网址，用户才能直接按 Enter 完成录入。
    LaunchedEffect(groupMenu) { if (!groupMenu) focus.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("link-entry")
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(Res.drawable.ic_link),
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(Res.string.link_add), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    controller.dismiss(); draft.blockId?.let {
                    onCursorRequest(
                        EditorCursorTarget(
                            it,
                            0
                        )
                    )
                }
                }, enabled = !controller.creating, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painterResource(Res.drawable.ic_close),
                        stringResource(Res.string.link_cancel),
                        Modifier.size(16.dp)
                    )
                }
            }
            OutlinedTextField(
                field,
                { field = it; controller.update(draft.copy(url = it.text, reuseId = null)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("link-url-input")
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || field.composition != null) false
                        else when (event.key) {
                            Key.Enter -> {
                                if (!controller.creating) submit(); true
                            }

                            Key.Escape -> {
                                controller.dismiss(); draft.blockId?.let {
                                    onCursorRequest(
                                        EditorCursorTarget(
                                            it,
                                            0
                                        )
                                    )
                                }; true
                            }

                            else -> false
                        }
                    },
                singleLine = true,
                enabled = !controller.creating,
                isError = controller.errorCode != null,
                label = { Text(stringResource(Res.string.link_address)) },
                placeholder = { Text(stringResource(Res.string.link_address_placeholder)) })
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    TextButton(
                        onClick = { groupMenu = true },
                        enabled = !controller.creating,
                        modifier = Modifier.testTag("link-entry-group")
                    ) {
                        Text(
                            PropertyOptionTree.label(definition.options, draft.groupId)
                                .ifBlank { stringResource(Res.string.link_ungrouped) },
                            style = MaterialTheme.typography.labelLarge
                        )
                        TagloomChevronDown(Modifier.padding(start = 8.dp).size(12.dp))
                    }
                    if (groupMenu) HierarchicalOptionPicker(
                        state,
                        definition,
                        draft.groupId,
                        onSelect = { controller.update(draft.copy(groupId = it)); groupMenu = false },
                        onDismiss = { groupMenu = false })
                }
                Text(
                    stringResource(Res.string.link_optional),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = submit, enabled = !controller.creating, modifier = Modifier.testTag("link-submit")) {
                    if (controller.creating) CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    ) else Text(stringResource(Res.string.link_add))
                }
            }
            if (duplicates.size > 1) {
                Text(stringResource(Res.string.link_duplicate_choose), style = MaterialTheme.typography.bodySmall)
                duplicates.forEach { document ->
                    TextButton(onClick = { controller.update(draft.copy(reuseId = document.id)) }) {
                        if (draft.reuseId == document.id) Icon(
                            painterResource(Res.drawable.ic_check),
                            null,
                            Modifier.size(16.dp)
                        )
                        Text(document.note.title)
                    }
                }
            }
            controller.errorCode?.let { code ->
                Text(
                    stringResource(
                        when (code) {
                            "link-url-invalid" -> Res.string.link_invalid_url
                            "revision_conflict" -> Res.string.link_revision_conflict
                            "link-duplicate-ambiguous" -> Res.string.link_duplicate_choose
                            else -> Res.string.link_create_failed
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("link-entry-error")
                )
            }
        }
    }
}

/**
 * 从新建类型或集合进入时复用同一录入表单，确认前不创建空 Note。
 * @param state 当前笔记目录。
 * @param controller 当前链接会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StandaloneLinkEntry(state: TagloomEditorState, controller: WorkspaceLinkController) {
    if (controller.entry?.sourceId != null || controller.entry == null) return
    val width =
        with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    if (width < 720.dp) ModalBottomSheet(
        onDismissRequest = controller::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) { LinkEntryForm(controller, state) }
    } else androidx.compose.ui.window.Dialog(
        onDismissRequest = controller::dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp)) { LinkEntryForm(controller, state) }
    }
}

/**
 * 展示共享层级属性的当前完整路径，复用同一选择器和 null 清空语义。
 * @param state 当前编辑器。
 * @param document 属性所属笔记。
 * @param definition 当前层级单选定义。
 * @param selectedId 当前有效选项或无值。
 * @param editable 是否允许修改。
 */
@Composable
internal fun HierarchicalPropertyValue(
    state: TagloomEditorState,
    document: com.oruke.tagloom.editor.EditorDocument,
    definition: PropertyDefinition,
    selectedId: String?,
    editable: Boolean
) {
    var expanded by remember(document.id, definition.id) { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = editable,
            modifier = Modifier.testTag("hierarchy-value-${definition.id.value}"),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                PropertyOptionTree.label(definition.options, selectedId)
                    .ifBlank { stringResource(Res.string.link_ungrouped) }, style = MaterialTheme.typography.bodySmall
            )
            TagloomChevronDown(Modifier.padding(start = 6.dp).size(12.dp))
        }
        if (expanded) HierarchicalOptionPicker(
            state, definition, selectedId,
            { state.propertyEditor.updateSelect(definition.id, it); expanded = false }, { expanded = false })
    }
}
