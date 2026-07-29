package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
 * @param onChangePassword 更换工作区密码。
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
    onChangePassword: (String, String) -> Unit,
    onRotateMasterKey: (String, String) -> Unit,
    onLock: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
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
                onChangePassword = { changePassword = true },
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
                Text(message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (saveQueryDialog) {
        AlertDialog(
            onDismissRequest = { saveQueryDialog = false },
            title = { Text("保存当前搜索") },
            text = {
                OutlinedTextField(
                    value = queryName,
                    onValueChange = { value -> queryName = value },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("查询名称") },
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
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { saveQueryDialog = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这篇笔记？") },
            text = { Text("已保存笔记会写入 tombstone，并从当前索引移除；历史密文不会被静默销毁。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (changePassword) {
        CredentialUpdateDialog(
            title = "更换工作区密码",
            description = "只重包当前主密钥，不重写对象密文，也不会更换 Recovery Key。",
            confirmLabel = "确认更换",
            onDismiss = { changePassword = false },
            onConfirm = { oldPassword, newPassword ->
                changePassword = false
                onChangePassword(oldPassword, newPassword)
            },
        )
    }

    if (rotateMasterKey) {
        CredentialUpdateDialog(
            title = "轮换工作区主密钥",
            description = "这会重写全部权威密文，并使旧密码和旧 Recovery Key 失效。完成后必须保存新的 Recovery Key。",
            confirmLabel = "确认轮换",
            onDismiss = { rotateMasterKey = false },
            onConfirm = { currentPassword, newPassword ->
                rotateMasterKey = false
                onRotateMasterKey(currentPassword, newPassword)
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
 * 收集当前密码和两次新密码输入，供密码重包或主密钥轮换复用。
 *
 * @param title 操作标题。
 * @param description 操作影响说明。
 * @param confirmLabel 确认按钮文案。
 * @param onDismiss 关闭动作。
 * @param onConfirm 已满足长度和一致性约束的确认动作。
 */
@Composable
private fun CredentialUpdateDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description)
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { value -> oldPassword = value },
                    label = { Text("当前密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { value -> newPassword = value },
                    label = { Text("新密码（至少 10 个字符）") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { value -> confirmation = value },
                    label = { Text("确认新密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(oldPassword, newPassword) },
                enabled = oldPassword.isNotBlank() && newPassword.length >= 10 && newPassword == confirmation,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        title = { Text("保存新的 Recovery Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("旧 Recovery Key 已失效。请把下面的新密钥保存到工作区之外的离线位置。")
                SelectionContainer {
                    Text(recoveryKey, style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = confirmedSaved,
                        onCheckedChange = { checked -> confirmedSaved = checked },
                    )
                    Text("我已将新的 Recovery Key 离线保存")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmedSaved,
                enabled = confirmedSaved,
            ) { Text("完成") }
        },
    )
}
