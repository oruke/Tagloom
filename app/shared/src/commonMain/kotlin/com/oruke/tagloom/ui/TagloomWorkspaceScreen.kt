package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import com.oruke.tagloom.editor.search.NoteSearchState
import com.oruke.tagloom.ui.search.handleNoteSearchKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.applyFreePropertyOption
import com.oruke.tagloom.editor.bindPropertyToTag
import com.oruke.tagloom.editor.planFreePropertyOption
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.setPropertyAttached
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.platform.DocumentExportRequest
import com.oruke.tagloom.platform.DocumentFileGateway
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_default_name
import tagloom.app.shared.generated.resources.database_operation_failed
import tagloom.app.shared.generated.resources.editor_action_new_note
import tagloom.app.shared.generated.resources.editor_empty_description
import tagloom.app.shared.generated.resources.editor_empty_title
import tagloom.app.shared.generated.resources.editor_export_failed
import tagloom.app.shared.generated.resources.editor_export_html_name
import tagloom.app.shared.generated.resources.editor_export_markdown_name
import tagloom.app.shared.generated.resources.editor_export_pdf_name
import tagloom.app.shared.generated.resources.editor_export_success
import tagloom.app.shared.generated.resources.editor_export_unavailable
import tagloom.app.shared.generated.resources.editor_export_word_name

/**
 * 渲染左侧导航与中央页面组成的 Tagloom 工作区。
 *
 * 标签和属性属于当前文档内容，固定显示在标题下方，不再占用常驻右侧栏。
 *
 * @param state 编辑器状态。
 * @param databaseController Database Block 使用的目录与写入控制器。
 * @param documentFileGateway 平台文件选择与文档编码能力。
 * @param onSaveRequest 可选立即保存动作；内存演示环境为空操作。
 * @param onWorkspaceMenuRequest 可选笔记操作菜单入口。
 * @param onSaveTagDefinition 可选工作区标签 schema 持久化动作；为空时只更新内存演示。
 * @param onSavePropertyDefinition 可选工作区属性 schema 持久化动作；为空时只更新内存演示。
 * @param onCreatePropertyOption 可选自由选项持久化动作；为空时只更新内存演示。
 * @param saving 当前是否正在写入加密快照。
 * @param modifier 外部布局修饰符。
 * @param searchMenu 页头搜索浮层内容。
 * @param workspaceMenu 页头笔记操作菜单内容。
 * @param settingsContent 独立设置页内容；接收返回当前笔记的动作，预览时为空。
 */
@Composable
internal fun TagloomWorkspaceScreen(
    state: TagloomEditorState,
    databaseController: DatabaseEditorController? = null,
    documentFileGateway: DocumentFileGateway? = null,
    onSaveRequest: () -> Unit = {},
    onWorkspaceMenuRequest: (() -> Unit)? = null,
    onSaveTagDefinition: ((TagDefinition, Boolean) -> Unit)? = null,
    onSavePropertyDefinition: ((PropertyDefinition, PropertyDefinitionDestination) -> Unit)? = null,
    onCreatePropertyOption: ((PropertyId, String) -> Unit)? = null,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
    searchMenu: (@Composable () -> Unit)? = null,
    workspaceMenu: (@Composable () -> Unit)? = null,
    settingsContent: (@Composable (() -> Unit) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocusRequester = remember { FocusRequester() }
    // 页面选择随会话保存；编辑器对象继续保留草稿，锁定后整个会话 UI 会被销毁。
    var settingsSelected by rememberSaveable { mutableStateOf(false) }
    val exportUnavailableMessage = stringResource(Res.string.editor_export_unavailable)
    val exportFailedMessage = stringResource(Res.string.editor_export_failed)
    val markdownName = stringResource(Res.string.editor_export_markdown_name)
    val htmlName = stringResource(Res.string.editor_export_html_name)
    val pdfName = stringResource(Res.string.editor_export_pdf_name)
    val wordName = stringResource(Res.string.editor_export_word_name)
    val defaultDatabaseName = stringResource(Res.string.database_default_name)
    val databaseOperationFailed = stringResource(Res.string.database_operation_failed)
    val exportSuccessMessages = mapOf(
        com.oruke.tagloom.platform.DocumentExportFormat.MARKDOWN to stringResource(
            Res.string.editor_export_success,
            markdownName,
        ),
        com.oruke.tagloom.platform.DocumentExportFormat.HTML to stringResource(
            Res.string.editor_export_success,
            htmlName,
        ),
        com.oruke.tagloom.platform.DocumentExportFormat.PDF to stringResource(
            Res.string.editor_export_success,
            pdfName,
        ),
        com.oruke.tagloom.platform.DocumentExportFormat.WORD to stringResource(
            Res.string.editor_export_success,
            wordName,
        ),
    )
    val document = state.selectedDocument
    val noteSearch = remember(document?.id) { NoteSearchState(state) }
    val workspaceFocus = remember { FocusRequester() }
    val closeNoteSearch = { noteSearch.close(); workspaceFocus.requestFocus(); Unit }
    val saveTagDefinition = onSaveTagDefinition ?: { definition: TagDefinition, attach: Boolean ->
        state.upsertTagDefinition(definition)
        if (attach) state.setAppliedObjectType(definition.id)
    }
    val savePropertyDefinition =
        onSavePropertyDefinition ?: { definition: PropertyDefinition, destination: PropertyDefinitionDestination ->
            state.upsertPropertyDefinition(definition)
            when (destination) {
                PropertyDefinitionDestination.CurrentNote -> state.setPropertyAttached(definition.id, attached = true)
                PropertyDefinitionDestination.Workspace -> Unit
                is PropertyDefinitionDestination.Tag -> {
                    state.upsertTagDefinition(state.bindPropertyToTag(destination.tagId, definition.id))
                }
            }
        }
    val createPropertyOption = onCreatePropertyOption ?: { propertyId: PropertyId, label: String ->
        val plan = state.planFreePropertyOption(propertyId, label)
        state.applyFreePropertyOption(plan.definition, plan.optionId)
    }
    val createDocument = {
        settingsSelected = false
        state.createDocument()
    }

    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNavigation = maxWidth >= 720.dp
            val navigationModifier = Modifier.fillMaxSize().focusRequester(workspaceFocus).focusable().onPreviewKeyEvent { event ->
                if (handleNoteSearchKeyEvent(event, noteSearch, document != null && !settingsSelected, closeNoteSearch)) {
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown || (!event.isCtrlPressed && !event.isMetaPressed)) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.N -> {
                        createDocument()
                        true
                    }

                    Key.K -> {
                        if (showNavigation) {
                            searchFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            Row(navigationModifier) {
                if (showNavigation) {
                    WorkspaceSidebar(
                        state = state,
                        searchFocusRequester = searchFocusRequester,
                        onCreateDocument = createDocument,
                        onSelectDocument = { documentId ->
                            state.selectDocument(documentId)
                            settingsSelected = false
                        },
                        onOpenSettings = settingsContent?.let { { settingsSelected = true } },
                        settingsSelected = settingsSelected,
                        modifier = Modifier.width(232.dp),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface)) {
                    if (!showNavigation && settingsContent != null && !settingsSelected) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = createDocument) {
                                Text(stringResource(Res.string.editor_action_new_note))
                            }
                            Box(Modifier.weight(1f))
                            WorkspaceSettingsEntry(selected = false, onClick = { settingsSelected = true })
                        }
                    }
                    if (settingsSelected && settingsContent != null) {
                        settingsContent { settingsSelected = false }
                    } else if (document == null) {
                        EmptyWorkspace(
                            onCreate = createDocument,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        EditorPane(
                            state = state,
                            document = document,
                            search = noteSearch,
                            onCloseSearch = closeNoteSearch,
                            databaseController = databaseController,
                            onCreateDatabaseBlock = createDatabase@{ blockId ->
                                val documentId = state.selectedDocumentId ?: return@createDatabase
                                val controller = databaseController ?: return@createDatabase
                                coroutineScope.launch {
                                    controller.createDatabaseBlock(
                                        documentId = documentId,
                                        blockId = blockId,
                                        name = defaultDatabaseName,
                                        sourceTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
                                    )
                                    if (controller.operationFailed) {
                                        snackbarHostState.showSnackbar(databaseOperationFailed)
                                    }
                                }
                            },
                            onExport = { format ->
                                val gateway = documentFileGateway
                                if (gateway == null) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(exportUnavailableMessage)
                                    }
                                } else {
                                    coroutineScope.launch {
                                        val exported = runCatching {
                                            gateway.exportDocument(
                                                DocumentExportRequest(
                                                    suggestedBaseName = document.note.title.toSafeFileBaseName(),
                                                    note = document.note,
                                                    format = format,
                                                ),
                                            )
                                        }.getOrElse {
                                            snackbarHostState.showSnackbar(exportFailedMessage)
                                            false
                                        }
                                        if (exported) {
                                            snackbarHostState.showSnackbar(requireNotNull(exportSuccessMessages[format]))
                                        }
                                    }
                                }
                            },
                            onSaveRequest = onSaveRequest,
                            onWorkspaceMenuRequest = onWorkspaceMenuRequest,
                            onSaveTagDefinition = saveTagDefinition,
                            onSavePropertyDefinition = savePropertyDefinition,
                            onCreatePropertyOption = createPropertyOption,
                            saving = saving,
                            modifier = Modifier.fillMaxSize(),
                            searchMenu = searchMenu,
                            workspaceMenu = workspaceMenu,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

/**
 * 渲染尚无选中文档时的直接创建入口。
 *
 * @param onCreate 新建笔记动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
private fun EmptyWorkspace(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.editor_empty_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(Res.string.editor_empty_description),
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) { Text(stringResource(Res.string.editor_action_new_note)) }
        }
    }
}

/**
 * 把笔记标题收敛为不含路径语义的建议文件基础名。
 *
 * @return 非空且不含扩展名的建议文件名。
 */
private fun String.toSafeFileBaseName(): String {
    val safe = trim()
        .map { character -> if (character.isLetterOrDigit() || character in "-_ ") character else '_' }
        .joinToString(separator = "")
        .trim()
        .ifBlank { "tagloom-note" }
        .take(80)
    return safe
}
