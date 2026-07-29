package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.platform.WorkspaceGateway
import kotlinx.coroutines.launch

/**
 * 根据工作区锁定状态渲染创建、Recovery Key、解锁、恢复或编辑界面。
 *
 * @param workspaceGateway 当前平台真实工作区能力。
 * @param documentFileGateway 可选文档导出能力。
 */
@Composable
fun WorkspaceLifecycleScreen(
    workspaceGateway: WorkspaceGateway,
    documentFileGateway: DocumentFileGateway? = null,
) {
    val editorState = remember(workspaceGateway) {
        TagloomEditorState(
            idFactory = workspaceGateway::createOpaqueId,
            timestampProvider = workspaceGateway::currentUtcTimestamp,
        )
    }
    val controller = remember(workspaceGateway) { WorkspaceUiController(workspaceGateway, editorState) }
    val coroutineScope = rememberCoroutineScope()

    when (controller.phase) {
        WorkspaceUiPhase.WELCOME -> WorkspaceWelcomePane(
            busy = controller.busy,
            errorMessage = controller.errorMessage,
            onCreate = { password -> coroutineScope.launch { controller.prepareCreation(password) } },
            onUnlock = { password -> coroutineScope.launch { controller.unlockWithPassword(password) } },
            onRecover = { recoveryKey -> coroutineScope.launch { controller.unlockWithRecovery(recoveryKey) } },
            onRestoreBackup = { password -> coroutineScope.launch { controller.restoreBackup(password) } },
        )
        WorkspaceUiPhase.RECOVERY_CONFIRMATION -> RecoveryKeyConfirmationPane(
            recoveryKey = requireNotNull(controller.preparedRecoveryKey),
            busy = controller.busy,
            errorMessage = controller.errorMessage,
            onConfirm = { coroutineScope.launch { controller.confirmCreation() } },
            onCancel = controller::cancelCreation,
        )
        WorkspaceUiPhase.OPEN -> WorkspaceSessionShell(
            controller = controller,
            documentFileGateway = documentFileGateway,
            onSave = { coroutineScope.launch { controller.saveSelected() } },
            onDelete = { coroutineScope.launch { controller.deleteSelected() } },
            onSearch = { coroutineScope.launch { controller.search() } },
            onSaveQuery = { name -> coroutineScope.launch { controller.saveCurrentQuery(name) } },
            onRunSavedQuery = { objectId -> coroutineScope.launch { controller.runSavedQuery(objectId) } },
            onBackup = { coroutineScope.launch { controller.createBackup() } },
            onChangePassword = { oldPassword, newPassword ->
                coroutineScope.launch { controller.changePassword(oldPassword, newPassword) }
            },
            onRotateMasterKey = { currentPassword, newPassword ->
                coroutineScope.launch { controller.rotateMasterKey(currentPassword, newPassword) }
            },
            onLock = { coroutineScope.launch { controller.lock() } },
        )
        WorkspaceUiPhase.READ_ONLY -> ReadOnlyRecoveryPane(
            messages = controller.recoveryMessages,
            busy = controller.busy,
            errorMessage = controller.errorMessage,
            statusMessage = controller.statusMessage,
            onBackup = { coroutineScope.launch { controller.createBackup() } },
            onLock = { coroutineScope.launch { controller.lock() } },
        )
    }
}

/**
 * 渲染锁定状态下的新建、密码解锁和 Recovery Key 解锁入口。
 *
 * @param busy 是否正在执行平台操作。
 * @param errorMessage 可展示错误。
 * @param onCreate 新建工作区动作。
 * @param onUnlock 密码解锁动作。
 * @param onRecover Recovery Key 解锁动作。
 * @param onRestoreBackup 从密文备份恢复动作。
 */
@Composable
private fun WorkspaceWelcomePane(
    busy: Boolean,
    errorMessage: String?,
    onCreate: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onRecover: (String) -> Unit,
    onRestoreBackup: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmedPassword by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    CenteredWorkspaceCard {
        Text("Tagloom", style = MaterialTheme.typography.headlineLarge)
        Text("加密对象快照是权威状态。密码和 Recovery Key 都丢失后，系统没有后门。")
        OutlinedTextField(
            value = password,
            onValueChange = { value -> password = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("工作区密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = confirmedPassword,
            onValueChange = { value -> confirmedPassword = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("确认新工作区密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onCreate(password) },
                enabled = !busy && password.length >= 10 && password == confirmedPassword,
            ) { Text("创建工作区") }
            OutlinedButton(
                onClick = { onUnlock(password) },
                enabled = !busy && password.isNotBlank(),
            ) { Text("打开工作区") }
            OutlinedButton(
                onClick = { onRestoreBackup(password) },
                enabled = !busy && password.isNotBlank(),
            ) { Text("从备份恢复") }
        }
        Text("或使用离线 Recovery Key", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = recoveryKey,
            onValueChange = { value -> recoveryKey = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Recovery Key") },
            minLines = 2,
            enabled = !busy,
        )
        OutlinedButton(
            onClick = { onRecover(recoveryKey) },
            enabled = !busy && recoveryKey.isNotBlank(),
        ) { Text("使用 Recovery Key 解锁工作区") }
        errorMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        if (busy) {
            Text("正在执行密码派生与工作区校验……", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 展示一次性 Recovery Key，并要求用户明确确认后才允许落盘。
 *
 * @param recoveryKey 带版本和校验码的恢复文本。
 * @param busy 是否正在创建工作区。
 * @param errorMessage 可展示错误。
 * @param onConfirm 确认已离线保存。
 * @param onCancel 放弃创建。
 */
@Composable
private fun RecoveryKeyConfirmationPane(
    recoveryKey: String,
    busy: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    CenteredWorkspaceCard {
        Text("保存 Recovery Key", style = MaterialTheme.typography.headlineMedium)
        Text("它只显示这一次。请保存到独立离线介质，不要与工作区放在同一目录。")
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Text(
                    text = recoveryKey,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = confirmed,
                onCheckedChange = { value -> confirmed = value },
                enabled = !busy,
            )
            Text("我已将 Recovery Key 保存到独立离线介质")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onConfirm, enabled = confirmed && !busy) { Text("确认并创建") }
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
        }
        errorMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * 在事务恢复进入安全模式时隐藏正文编辑，只展示脱敏恢复结果和锁定入口。
 *
 * @param messages 脱敏恢复动作。
 * @param busy 是否正在锁定。
 * @param errorMessage 可展示错误。
 * @param statusMessage 备份成功提示。
 * @param onBackup 创建密文备份动作。
 * @param onLock 锁定动作。
 */
@Composable
private fun ReadOnlyRecoveryPane(
    messages: List<String>,
    busy: Boolean,
    errorMessage: String?,
    statusMessage: String?,
    onBackup: () -> Unit,
    onLock: () -> Unit,
) {
    CenteredWorkspaceCard {
        Text("工作区需要恢复", style = MaterialTheme.typography.headlineMedium)
        Text("系统无法证明新旧状态完整，已阻止编辑、搜索和外部入口。请保留隔离密文并先制作副本。")
        messages.forEach { message -> Text("• $message") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBackup, enabled = !busy) { Text("创建密文备份") }
            Button(onClick = onLock, enabled = !busy) { Text("锁定并返回") }
        }
        statusMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.primary) }
        errorMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * 把工作区生命周期卡片居中并限制桌面阅读宽度。
 *
 * @param content 卡片内容。
 */
@Composable
private fun CenteredWorkspaceCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = { content() },
            )
        }
    }
}
