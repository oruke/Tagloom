package com.oruke.tagloom.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.applyFreePropertyOption
import com.oruke.tagloom.editor.bindPropertyToTag
import com.oruke.tagloom.editor.createDocumentWithSupertag
import com.oruke.tagloom.editor.createTagDefinitionDraft
import com.oruke.tagloom.editor.planFreePropertyOption
import com.oruke.tagloom.editor.search.NoteSearchState
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.setPropertyAttached
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.platform.DocumentExportFormat
import com.oruke.tagloom.platform.DocumentExportRequest
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.ui.search.handleNoteSearchKeyEvent
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染单左栏导航、中央页面（编辑器 / 全部笔记 / 全屏 Agent）与右侧 Agent 伴随分栏组成的 Tagloom 工作区。
 */
@Composable
internal fun TagloomWorkspaceScreen(
    state: TagloomEditorState,
    supertagController: WorkspaceSupertagController? = null,
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
    onRequestAgentSettings: (() -> Unit)? = null,
    storageMode: WorkspaceStorageMode = WorkspaceStorageMode.LOCAL,
) {
    val linkController = LocalWorkspaceLinks.current
    val agentController = LocalWorkspaceAgentHarness.current
    val linkSavedMessage = stringResource(Res.string.link_saved)
    val linkReusedMessage = stringResource(Res.string.link_reused)
    val linkUndoLabel = stringResource(Res.string.link_undo)
    val linkUndoFailed = stringResource(Res.string.link_undo_failed)
    ConfigureEditorContentNames(state)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(linkController?.undoCreation) {
        if (linkController?.undoCreation == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            if (linkController.reused) linkReusedMessage else linkSavedMessage,
            linkUndoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && !linkController.undoLastCreation()) {
            snackbarHostState.showSnackbar(linkUndoFailed)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    var settingsSelected by rememberSaveable { mutableStateOf(false) }
    var librarySelected by rememberSaveable { mutableStateOf(false) }
    var tagRequest by remember { mutableStateOf<SupertagEditRequest?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var createAfterTag by remember { mutableStateOf(false) }
    val tagController = remember(state, supertagController) { supertagController ?: WorkspaceSupertagController(null, state) }

    val dismissSpecialPages = {
        linkController?.collection?.close()
        settingsSelected = false
        librarySelected = false
        tagRequest = null
        createAfterTag = false
    }
    val openTag: (TagDefinition, Boolean) -> Unit = { definition, attach ->
        if (!tagController.busy) {
            tagController.clearError()
            settingsSelected = false
            tagRequest = SupertagEditRequest(definition, attach)
        }
    }
    val createWithTag: (TagDefinition) -> Unit = { definition ->
        pickerOpen = false
        dismissSpecialPages()
        agentController?.fullScreenAgentActive = false
        agentController?.allNotesPageActive = false
        if (definition.id == LinkNoteSchemas.TYPE_ID && linkController?.available == true) {
            linkController.begin()
        } else {
            state.createDocumentWithSupertag(definition.id)
            state.newNoteTitleFocusId = state.selectedDocumentId
            state.selectedDocumentId?.let { agentController?.promoteRecentNote(it) }
        }
    }
    val openAgentSettingsAction = {
        dismissSpecialPages()
        onRequestAgentSettings?.invoke()
        if (settingsContent != null) settingsSelected = true
    }

    val exportUnavailableMessage = stringResource(Res.string.editor_export_unavailable)
    val exportFailedMessage = stringResource(Res.string.editor_export_failed)
    val defaultDatabaseName = stringResource(Res.string.database_default_name)
    val databaseOperationFailed = stringResource(Res.string.database_operation_failed)
    val exportSuccessMessages = mapOf(
        DocumentExportFormat.MARKDOWN to stringResource(Res.string.editor_export_success, stringResource(Res.string.editor_export_markdown_name)),
        DocumentExportFormat.HTML to stringResource(Res.string.editor_export_success, stringResource(Res.string.editor_export_html_name)),
        DocumentExportFormat.PDF to stringResource(Res.string.editor_export_success, stringResource(Res.string.editor_export_pdf_name)),
        DocumentExportFormat.WORD to stringResource(Res.string.editor_export_success, stringResource(Res.string.editor_export_word_name)),
    )
    val document = state.selectedDocument
    val noteSearch = remember(document?.id) { NoteSearchState(state) }
    val workspaceFocus = remember { FocusRequester() }
    val closeNoteSearch = { noteSearch.close(); workspaceFocus.requestFocus(); Unit }

    val saveTagDefinition = onSaveTagDefinition ?: { definition: TagDefinition, attach: Boolean ->
        state.upsertTagDefinition(definition)
        if (attach) state.setAppliedObjectType(definition.id)
    }
    val savePropertyDefinition = onSavePropertyDefinition ?: { definition: PropertyDefinition, destination: PropertyDefinitionDestination ->
        state.upsertPropertyDefinition(definition)
        when (destination) {
            PropertyDefinitionDestination.CurrentNote -> state.setPropertyAttached(definition.id, attached = true)
            PropertyDefinitionDestination.Workspace -> Unit
            is PropertyDefinitionDestination.Tag -> state.upsertTagDefinition(state.bindPropertyToTag(destination.tagId, definition.id))
        }
    }
    val createPropertyOption = onCreatePropertyOption ?: { propertyId: PropertyId, label: String ->
        val plan = state.planFreePropertyOption(propertyId, label)
        state.applyFreePropertyOption(plan.definition, plan.optionId)
    }
    val createDocument = { if (!tagController.busy) pickerOpen = true; Unit }

    CompositionLocalProvider(LocalSupertagEditor provides openTag) {
        Box(modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val showDesktopShortcutSearch = maxWidth >= 720.dp
                val inOverlaySubpage = settingsSelected || librarySelected || tagRequest != null || linkController?.collection?.visible == true
                val isEditingNoteView = document != null && !inOverlaySubpage &&
                    agentController?.fullScreenAgentActive != true && agentController?.allNotesPageActive != true

                val navigationModifier = Modifier
                    .fillMaxSize()
                    .focusRequester(workspaceFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (handleNoteSearchKeyEvent(event, noteSearch, isEditingNoteView, closeNoteSearch)) {
                            return@onPreviewKeyEvent true
                        }
                        if (event.type != KeyEventType.KeyDown || (!event.isCtrlPressed && !event.isMetaPressed)) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.N -> { createDocument(); true }
                            Key.K -> if (showDesktopShortcutSearch) { searchFocusRequester.requestFocus(); true } else false
                            Key.J -> if (agentController?.available == true) {
                                dismissSpecialPages()
                                agentController.toggleCompanionPane()
                                true
                            } else false
                            else -> false
                        }
                    }

                WorkspaceDualFormStage(
                    maxWidth = maxWidth,
                    state = state,
                    navigationModifier = navigationModifier,
                    inOverlaySubpage = inOverlaySubpage,
                    onCreateDocument = createDocument,
                    onOpenLinks = if (linkController?.available == true) ({
                        dismissSpecialPages()
                        linkController.collection.showAll()
                    }) else null,
                    onOpenSupertags = {
                        if (!tagController.busy) {
                            dismissSpecialPages()
                            librarySelected = true
                            tagController.clearError()
                        }
                    },
                    onOpenSettings = settingsContent?.let {
                        { if (!tagController.busy) { dismissSpecialPages(); settingsSelected = true } }
                    },
                    onOpenAgentSettings = openAgentSettingsAction,
                    onSaveRequest = onSaveRequest,
                    sidebarContent = { sidebarModifier, onCloseDrawer ->
                        WorkspaceSidebar(
                            state = state,
                            searchFocusRequester = searchFocusRequester,
                            onCreateDocument = { onCloseDrawer(); createDocument() },
                            onSelectDocument = { documentId ->
                                onCloseDrawer()
                                if (!tagController.busy) {
                                    dismissSpecialPages()
                                    if (agentController != null) agentController.selectNoteFromSidebarRecent(documentId)
                                    else state.selectDocument(documentId)
                                }
                            },
                            onOpenSettings = settingsContent?.let {
                                { onCloseDrawer(); if (!tagController.busy) { dismissSpecialPages(); settingsSelected = true } }
                            },
                            settingsSelected = settingsSelected,
                            onOpenSupertags = {
                                onCloseDrawer()
                                if (!tagController.busy) {
                                    dismissSpecialPages(); librarySelected = true; tagController.clearError()
                                }
                            },
                            supertagsSelected = librarySelected || tagRequest != null,
                            modifier = sidebarModifier,
                            storageMode = storageMode,
                            onOpenLinks = if (linkController?.available == true) ({
                                onCloseDrawer(); dismissSpecialPages(); linkController.collection.showAll()
                            }) else null,
                            linksSelected = linkController?.collection?.visible == true,
                            onOpenAllNotes = agentController?.let { ac ->
                                { onCloseDrawer(); dismissSpecialPages(); ac.openAllNotesPage() }
                            },
                            allNotesSelected = !inOverlaySubpage && agentController?.allNotesPageActive == true && agentController.fullScreenAgentActive != true,
                            onDismissSpecialPages = { onCloseDrawer(); dismissSpecialPages() },
                        )
                    },
                ) { isCompact, showNavButton, onToggleNav ->
                    val editing = tagRequest
                    when {
                        linkController?.collection?.visible == true -> {
                            LinkCollectionPane(state, linkController) { id ->
                                linkController.collection.close()
                                state.selectDocument(id)
                                agentController?.promoteRecentNote(id)
                            }
                        }
                        editing != null -> {
                            SupertagEditorScreen(
                                state, editing, tagController,
                                onBack = { tagRequest = null; createAfterTag = false },
                                onSave = { definition, properties ->
                                    coroutineScope.launch {
                                        if (tagController.save(definition, properties, editing.attachToSelected)) {
                                            tagRequest = null
                                            if (createAfterTag) {
                                                createAfterTag = false; createWithTag(definition)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        librarySelected -> {
                            SupertagLibraryScreen(
                                state, tagController,
                                onCreate = { openTag(state.createTagDefinitionDraft(""), false) },
                                onEdit = { openTag(it, false) },
                                onBack = { librarySelected = false },
                            )
                        }
                        settingsSelected && settingsContent != null -> {
                            settingsContent { settingsSelected = false }
                        }
                        agentController?.fullScreenAgentActive == true -> {
                            AgentWorkspaceScreen(
                                controller = agentController,
                                editorState = state,
                                savedQueries = agentController.savedQueriesSnapshot,
                                enabledModels = agentController.enabledModels,
                                isCompactViewport = isCompact,
                                showMenuButton = showNavButton,
                                onOpenMenu = onToggleNav,
                                onOpenSettings = openAgentSettingsAction,
                                onSaveEditorChanges = onSaveRequest,
                            )
                        }
                        agentController?.allNotesPageActive == true -> {
                            AllNotesDatabaseScreen(
                                state = state,
                                onOpenNote = { noteId ->
                                    agentController.allNotesPageActive = false
                                    state.selectDocument(noteId)
                                    agentController.promoteRecentNote(noteId)
                                },
                                onCreateNote = createDocument,
                                onToggleCompanion = if (agentController.available) agentController::toggleCompanionPane else null,
                                companionActive = agentController.companionVisible,
                            )
                        }
                        document == null -> {
                            EmptyWorkspace(onCreate = createDocument, modifier = Modifier.fillMaxSize())
                        }
                        else -> {
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
                                        if (controller.operationFailed) snackbarHostState.showSnackbar(databaseOperationFailed)
                                    }
                                },
                                onExport = { format ->
                                    val gateway = documentFileGateway
                                    if (gateway == null) {
                                        coroutineScope.launch { snackbarHostState.showSnackbar(exportUnavailableMessage) }
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
                                            if (exported) snackbarHostState.showSnackbar(requireNotNull(exportSuccessMessages[format]))
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

                if (pickerOpen) {
                    SupertagNotePicker(
                        state, !showDesktopShortcutSearch,
                        onSelect = createWithTag,
                        onCreate = { name ->
                            pickerOpen = false; createAfterTag = true
                            openTag(state.createTagDefinitionDraft(name), false)
                        },
                        onManage = {
                            pickerOpen = false; dismissSpecialPages(); librarySelected = true
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
                if (linkController != null) StandaloneLinkEntry(state, linkController)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

/** 渲染尚无选中文档时的直接创建入口。 */
@Composable
private fun EmptyWorkspace(onCreate: () -> Unit, modifier: Modifier = Modifier) {
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

/** 把笔记标题收敛为不含路径语义的建议文件基础名。 */
private fun String.toSafeFileBaseName(): String = trim()
    .map { character -> if (character.isLetterOrDigit() || character in "-_ ") character else '_' }
    .joinToString(separator = "")
    .trim()
    .ifBlank { "tagloom-note" }
    .take(80)
