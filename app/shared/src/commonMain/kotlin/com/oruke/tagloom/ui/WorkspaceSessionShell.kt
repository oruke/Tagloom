package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.platform.DocumentFileGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.workspace_action_cancel
import tagloom.app.shared.generated.resources.workspace_action_save
import tagloom.app.shared.generated.resources.workspace_mode_error_hint
import tagloom.app.shared.generated.resources.workspace_note_delete_confirm
import tagloom.app.shared.generated.resources.workspace_note_delete_description
import tagloom.app.shared.generated.resources.workspace_note_delete_title
import tagloom.app.shared.generated.resources.workspace_search_name_label
import tagloom.app.shared.generated.resources.workspace_search_save_title

/**
 * 在解锁会话中编排笔记操作、搜索及独立设置页面。
 *
 * 设置动作复用既有认证对话框和控制器，切换页面不会丢弃当前文档草稿。
 *
 * @param controller 工作区 UI 控制器。
 * @param coroutineScope 宿主操作作用域，避免配置变更取消正在提交的业务操作。
 * @param documentFileGateway 可选文档导出网关。
 * @param onSave 保存当前 Note。
 * @param onDelete 删除当前 Note。
 * @param onSearch 查询已确认索引。
 * @param onSaveQuery 保存当前全文查询。
 * @param onRunSavedQuery 运行已确认保存查询。
 * @param onBackup 创建当前确认点密文备份。
 * @param onEnablePin 为当前设备启用 PIN。
 * @param onChangePin 更换当前设备 PIN。
 * @param onDisablePin 停用当前设备 PIN。
 * @param onRotateMasterKey 轮换主密钥、权威信封和 Recovery Key。
 * @param onLock 锁定工作区。
 */
@Composable
internal fun WorkspaceSessionShell(
    controller: WorkspaceUiController,
    coroutineScope: CoroutineScope,
    documentFileGateway: DocumentFileGateway?,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onSearch: () -> Unit,
    onSaveQuery: (String) -> Unit,
    onRunSavedQuery: (ObjectId) -> Unit,
    onBackup: () -> Unit,
    onEnablePin: (String) -> Unit,
    onChangePin: (String, String) -> Unit,
    onDisablePin: (String) -> Unit,
    onRotateMasterKey: (String?) -> Unit,
    onLock: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var managePin by remember { mutableStateOf(false) }
    var rotateMasterKey by remember { mutableStateOf(false) }
    var queryName by remember { mutableStateOf("") }
    var saveQueryDialog by remember { mutableStateOf(false) }
    var workspaceMenuOpen by remember { mutableStateOf(false) }
    var searchPaletteOpen by remember { mutableStateOf(false) }
    var serverRecoveryOpen by remember { mutableStateOf(false) }
    val selectedDocument = controller.editorState.selectedDocument

    LaunchedEffect(controller) { controller.serverRecovery.observeAutomaticUpdates() }
    LaunchedEffect(controller) { controller.observeExternalChanges() }

    LaunchedEffect(selectedDocument?.id, selectedDocument?.draftVersion, selectedDocument?.dirty, controller.busy, controller.links.creating) {
        if (selectedDocument?.dirty == true && !controller.busy && !controller.links.creating) {
            delay(AUTO_SAVE_DELAY_MILLIS)
            if (controller.canAutoSaveSelected()) {
                onSave()
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalWorkspaceLinks provides controller.links,
        LocalPropertyOptionCatalog provides controller.propertyOptions,
    ) { Box(Modifier.fillMaxSize()) {
        TagloomWorkspaceScreen(
            state = controller.editorState,
            storageMode = controller.serverRecovery.status?.mode ?: com.oruke.tagloom.platform.WorkspaceStorageMode.LOCAL,
            supertagController = controller.supertags,
            databaseController = controller.databaseController,
            documentFileGateway = documentFileGateway,
            onSaveRequest = onSave,
            settingsContent = { onBack ->
                if (serverRecoveryOpen) ServerRecoverySettingsPane(controller, coroutineScope, onBack = { serverRecoveryOpen = false })
                else WorkspaceSettingsScreen(
                    requiresPin = controller.recentWorkspace?.requiresPin == true,
                    busy = controller.busy,
                    statusMessage = controller.statusMessage,
                    onBack = onBack,
                    onManagePin = { managePin = true },
                    onBackup = onBackup,
                    onRotateMasterKey = { rotateMasterKey = true },
                    onLock = onLock,
                    onServerRecovery = if (controller.serverRecovery.available) ({ serverRecoveryOpen = true }) else null,
                )
            },
            onWorkspaceMenuRequest = {
                searchPaletteOpen = false
                workspaceMenuOpen = true
            },
            searchMenu = {
                WorkspaceSearchPopover(
                    controller = controller,
                    expanded = searchPaletteOpen,
                    onDismiss = { searchPaletteOpen = false },
                    onSearch = onSearch,
                    onSaveQuery = { saveQueryDialog = true },
                )
            },
            workspaceMenu = {
                WorkspaceMaintenanceMenu(
                    controller = controller,
                    expanded = workspaceMenuOpen,
                    onDismiss = { workspaceMenuOpen = false },
                    actions = WorkspaceMaintenanceActions(
                        onSave = onSave,
                        onSearchWorkspace = { searchPaletteOpen = true },
                        onSaveQuery = { saveQueryDialog = true },
                        onRunSavedQuery = { queryId ->
                            onRunSavedQuery(queryId)
                            searchPaletteOpen = true
                        },
                        onDelete = { confirmDelete = true },
                        onLock = onLock,
                    ),
                )
            },
            onSaveTagDefinition = { definition, attach ->
                coroutineScope.launch { controller.saveTagDefinition(definition, attach) }
            },
            onSavePropertyDefinition = { definition, destination ->
                coroutineScope.launch { controller.savePropertyDefinition(definition, destination) }
            },
            onCreatePropertyOption = { propertyId, label ->
                coroutineScope.launch { controller.createFreePropertyOptionAndSelect(propertyId, label) }
            },
            saving = controller.busy && selectedDocument?.dirty == true,
            modifier = Modifier.fillMaxSize(),
        )
        controller.errorMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    message.localizedText(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        controller.serverRecovery.errorCode?.let { code ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
                    ServerRecoveryError(code)
                    Text(stringResource(tagloom.app.shared.generated.resources.Res.string.workspace_mode_error_hint),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    }
    if (saveQueryDialog) {
        AlertDialog(
            onDismissRequest = { saveQueryDialog = false },
            title = { Text(stringResource(Res.string.workspace_search_save_title)) },
            text = {
                OutlinedTextField(
                    value = queryName,
                    onValueChange = { value -> queryName = value },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.workspace_search_name_label)) },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveQuery(queryName)
                        queryName = ""
                        saveQueryDialog = false
                    },
                    enabled = queryName.isNotBlank(),
                ) { Text(stringResource(Res.string.workspace_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { saveQueryDialog = false }) {
                    Text(stringResource(Res.string.workspace_action_cancel))
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.workspace_note_delete_title)) },
            text = { Text(stringResource(Res.string.workspace_note_delete_description)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(Res.string.workspace_note_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.workspace_action_cancel))
                }
            },
        )
    }

    if (managePin) {
        if (controller.recentWorkspace?.requiresPin == true) {
            PinUpdateDialog(
                onDismiss = { managePin = false },
                onConfirm = { currentPin, newPin ->
                    managePin = false
                    onChangePin(currentPin, newPin)
                },
                onDisable = { currentPin ->
                    managePin = false
                    onDisablePin(currentPin)
                },
            )
        } else {
            PinEnableDialog(
                onDismiss = { managePin = false },
                onConfirm = { pin ->
                    managePin = false
                    onEnablePin(pin)
                },
            )
        }
    }

    if (rotateMasterKey) {
        MasterKeyRotationDialog(
            requiresPin = controller.recentWorkspace?.requiresPin == true,
            onDismiss = { rotateMasterKey = false },
            onConfirm = { currentPin ->
                rotateMasterKey = false
                onRotateMasterKey(currentPin)
            },
        )
    }

    controller.rotatedRecoveryKey?.let { recoveryKey ->
        RotatedRecoveryKeyDialog(
            recoveryKey = recoveryKey,
            onConfirmedSaved = controller::confirmRotatedRecoveryKeySaved,
        )
    }
}

/** 连续输入停止后触发加密快照保存的去抖时间。 */
private const val AUTO_SAVE_DELAY_MILLIS: Long = 900L
