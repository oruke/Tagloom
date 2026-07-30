package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.platform.DocumentFileGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 在解锁会话中复用编辑器页头承载搜索与工作区维护入口。
 *
 * 安全、备份和查询能力保留为按需浮层，不再额外占据一整条常驻工具栏。
 *
 * @param controller 工作区 UI 控制器。
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
    val coroutineScope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var managePin by remember { mutableStateOf(false) }
    var rotateMasterKey by remember { mutableStateOf(false) }
    var queryName by remember { mutableStateOf("") }
    var saveQueryDialog by remember { mutableStateOf(false) }
    var workspaceMenuOpen by remember { mutableStateOf(false) }
    var searchPaletteOpen by remember { mutableStateOf(false) }
    val selectedDocument = controller.editorState.selectedDocument

    LaunchedEffect(selectedDocument?.id, selectedDocument?.note, selectedDocument?.dirty, controller.busy) {
        if (selectedDocument?.dirty == true && !controller.busy) {
            delay(AUTO_SAVE_DELAY_MILLIS)
            onSave()
        }
    }

    Box(Modifier.fillMaxSize()) {
        TagloomWorkspaceScreen(
            state = controller.editorState,
            documentFileGateway = documentFileGateway,
            onSaveRequest = onSave,
            onSearchRequest = {
                workspaceMenuOpen = false
                searchPaletteOpen = true
            },
            onWorkspaceMenuRequest = {
                searchPaletteOpen = false
                workspaceMenuOpen = true
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
        WorkspaceSearchPopover(
            controller = controller,
            expanded = searchPaletteOpen,
            onDismiss = { searchPaletteOpen = false },
            onSearch = onSearch,
            onSaveQuery = { saveQueryDialog = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 48.dp),
        )
        WorkspaceMaintenanceMenu(
            controller = controller,
            expanded = workspaceMenuOpen,
            onDismiss = { workspaceMenuOpen = false },
            actions = WorkspaceMaintenanceActions(
                onSave = onSave,
                onSaveQuery = { saveQueryDialog = true },
                onRunSavedQuery = { queryId ->
                    onRunSavedQuery(queryId)
                    searchPaletteOpen = true
                },
                onBackup = onBackup,
                onChangePin = { managePin = true },
                onRotateMasterKey = { rotateMasterKey = true },
                onDelete = { confirmDelete = true },
                onLock = onLock,
            ),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 24.dp),
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

/**
 * 收集当前与新本机 PIN，并且只允许用户输入 6 至 12 位数字。
 *
 * @param onDismiss 关闭动作。
 * @param onConfirm 已满足长度和一致性约束的确认动作。
 * @param onDisable 已验证当前 PIN 后停用 PIN 的动作。
 */
@Composable
private fun PinUpdateDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onDisable: (String) -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val currentPinValid = currentPin.length in 6..12 && currentPin.all(Char::isDigit)
    val newPinValid = newPin.length in 6..12 && newPin.all(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_pin_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.workspace_pin_change_description))
                WorkspacePinTextField(
                    value = currentPin,
                    onValueChange = { value -> currentPin = value },
                    label = stringResource(Res.string.workspace_pin_current_label),
                    enabled = true,
                )
                WorkspacePinTextField(
                    value = newPin,
                    onValueChange = { value -> newPin = value },
                    label = stringResource(Res.string.workspace_pin_new_label),
                    enabled = true,
                )
                WorkspacePinTextField(
                    value = confirmation,
                    onValueChange = { value -> confirmation = value },
                    label = stringResource(Res.string.workspace_pin_confirm_label),
                    enabled = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentPin, newPin) },
                enabled = currentPinValid && newPinValid && newPin == confirmation,
            ) { Text(stringResource(Res.string.workspace_pin_change_confirm)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onDisable(currentPin) },
                    enabled = currentPinValid,
                ) {
                    Text(stringResource(Res.string.workspace_pin_disable_confirm))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.workspace_action_cancel))
                }
            }
        },
    )
}

/**
 * 为未启用 PIN 的当前设备设置新的 PIN。
 *
 * @param onDismiss 关闭动作。
 * @param onConfirm 已满足长度和一致性约束的确认动作。
 */
@Composable
private fun PinEnableDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val pinValid = pin.length in 6..12 && pin.all(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_pin_enable_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.workspace_pin_enable_description))
                WorkspacePinTextField(
                    value = pin,
                    onValueChange = { value -> pin = value },
                    label = stringResource(Res.string.workspace_pin_new_label),
                    enabled = true,
                )
                WorkspacePinTextField(
                    value = confirmation,
                    onValueChange = { value -> confirmation = value },
                    label = stringResource(Res.string.workspace_pin_confirm_label),
                    enabled = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pinValid && pin == confirmation,
            ) {
                Text(stringResource(Res.string.workspace_pin_enable_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.workspace_action_cancel))
            }
        },
    )
}

/**
 * 在轮换主密钥前按当前本机访问模式重新认证高熵材料。
 *
 * @param requiresPin 当前设备是否已启用 PIN。
 * @param onDismiss 关闭动作。
 * @param onConfirm 已完成必要 PIN 输入后的轮换动作；未启用 PIN 时参数为空。
 */
@Composable
private fun MasterKeyRotationDialog(
    requiresPin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    val currentPinValid = currentPin.length in 6..12 && currentPin.all(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_master_rotation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.workspace_master_rotation_description))
                if (requiresPin) {
                    WorkspacePinTextField(
                        value = currentPin,
                        onValueChange = { value -> currentPin = value },
                        label = stringResource(Res.string.workspace_pin_current_label),
                        enabled = true,
                    )
                } else {
                    Text(
                        stringResource(Res.string.workspace_master_rotation_windows_account_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentPin.takeIf { requiresPin }) },
                enabled = !requiresPin || currentPinValid,
            ) { Text(stringResource(Res.string.workspace_master_rotation_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.workspace_action_cancel))
            }
        },
    )
}

/**
 * 强制用户确认已离线保存轮换后的新 Recovery Key，再清除界面明文。
 *
 * @param recoveryKey 带版本和校验码、仅展示一次的恢复文本。
 * @param onConfirmedSaved 用户明确确认后的清除动作。
 */
@Composable
private fun RotatedRecoveryKeyDialog(
    recoveryKey: String,
    onConfirmedSaved: () -> Unit,
) {
    var confirmedSaved by remember(recoveryKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.workspace_recovery_rotated_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.workspace_recovery_rotated_description))
                SelectionContainer {
                    Text(recoveryKey, style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = confirmedSaved,
                        onCheckedChange = { checked -> confirmedSaved = checked },
                    )
                    Text(stringResource(Res.string.workspace_recovery_rotated_confirm_saved))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmedSaved,
                enabled = confirmedSaved,
            ) { Text(stringResource(Res.string.workspace_action_done)) }
        },
    )
}
