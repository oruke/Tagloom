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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceRecentWorkspace
import com.oruke.tagloom.platform.WorkspaceRecoveryMessage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 根据工作区锁定状态渲染最近工作区 PIN、Recovery Key、恢复或编辑界面。
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

    LaunchedEffect(controller) {
        controller.initialize()
    }

    when (controller.phase) {
        WorkspaceUiPhase.INITIALIZING -> WorkspaceLoadingPane()
        WorkspaceUiPhase.WELCOME -> WorkspaceWelcomePane(
            recentWorkspace = controller.recentWorkspace,
            busy = controller.busy,
            errorMessage = controller.errorMessage,
            onOpenRecent = { coroutineScope.launch { controller.openRecentWorkspace() } },
            onUnlock = { pin -> coroutineScope.launch { controller.unlockRecentWorkspace(pin) } },
            onCreate = { pin -> coroutineScope.launch { controller.prepareCreation(pin) } },
            onRecover = { recoveryKey, pin ->
                coroutineScope.launch { controller.unlockWithRecovery(recoveryKey, pin) }
            },
            onRestoreBackup = { recoveryKey, pin ->
                coroutineScope.launch { controller.restoreBackup(recoveryKey, pin) }
            },
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
            onEnablePin = { pin ->
                coroutineScope.launch { controller.enablePin(pin) }
            },
            onChangePin = { currentPin, newPin ->
                coroutineScope.launch { controller.changePin(currentPin, newPin) }
            },
            onDisablePin = { currentPin ->
                coroutineScope.launch { controller.disablePin(currentPin) }
            },
            onRotateMasterKey = { currentPin ->
                coroutineScope.launch { controller.rotateMasterKey(currentPin) }
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
 * 表示锁定页当前展示的本机访问动作。
 */
private enum class WorkspaceAccessMode {
    /** 打开最近登记工作区。 */
    RECENT,

    /** 选择创建或连接工作区。 */
    CHOOSE,

    /** 创建新工作区并选择是否启用本机 PIN。 */
    CREATE,

    /** 使用 Recovery Key 连接或恢复工作区，并选择是否启用本机 PIN。 */
    RECOVER,
}

/**
 * 渲染最近工作区优先的锁定入口，并把 Recovery Key 操作后置到显式连接流程。
 *
 * @param recentWorkspace 本机最近登记工作区；为空时显示首次使用入口。
 * @param busy 是否正在执行本机访问或工作区认证。
 * @param errorMessage 当前可安全展示的本地化错误。
 * @param onOpenRecent 使用 Windows 帐户保护材料重新打开最近工作区。
 * @param onUnlock 使用 PIN 解锁最近登记工作区。
 * @param onCreate 使用可选 PIN 准备新工作区创建。
 * @param onRecover 使用 Recovery Key 和可选 PIN 连接既有工作区。
 * @param onRestoreBackup 使用 Recovery Key 和可选 PIN 恢复密文备份。
 */
@Composable
private fun WorkspaceWelcomePane(
    recentWorkspace: WorkspaceRecentWorkspace?,
    busy: Boolean,
    errorMessage: WorkspaceUiMessage?,
    onOpenRecent: () -> Unit,
    onUnlock: (String) -> Unit,
    onCreate: (String?) -> Unit,
    onRecover: (String, String?) -> Unit,
    onRestoreBackup: (String, String?) -> Unit,
) {
    var mode by remember(recentWorkspace) {
        mutableStateOf(if (recentWorkspace == null) WorkspaceAccessMode.CHOOSE else WorkspaceAccessMode.RECENT)
    }
    var pin by remember { mutableStateOf("") }
    var confirmedPin by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var pinEnabled by remember(mode) { mutableStateOf(false) }
    val pinValid = pin.length in 6..12 && pin.all(Char::isDigit)
    val pinProtectionActive = when (mode) {
        WorkspaceAccessMode.RECENT -> recentWorkspace?.requiresPin == true
        WorkspaceAccessMode.CHOOSE -> false
        WorkspaceAccessMode.CREATE, WorkspaceAccessMode.RECOVER -> pinEnabled
    }

    CenteredWorkspaceCard {
        Text(stringResource(Res.string.workspace_app_name), style = MaterialTheme.typography.headlineLarge)
        when (mode) {
            WorkspaceAccessMode.RECENT -> {
                val workspace = requireNotNull(recentWorkspace)
                Text(
                    stringResource(Res.string.workspace_access_continue_to, workspace.displayName),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (workspace.requiresPin) {
                    WorkspacePinTextField(
                        value = pin,
                        onValueChange = { value -> pin = value },
                        label = stringResource(Res.string.workspace_access_pin_label),
                        enabled = !busy,
                    )
                    Button(
                        onClick = { onUnlock(pin) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && pinValid,
                    ) {
                        Text(stringResource(Res.string.workspace_access_unlock))
                    }
                } else {
                    Text(
                        stringResource(Res.string.workspace_access_auto_open_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenRecent,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(stringResource(Res.string.workspace_access_open))
                    }
                }
                TextButton(
                    onClick = {
                        pin = ""
                        confirmedPin = ""
                        recoveryKey = ""
                        mode = WorkspaceAccessMode.RECOVER
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(Res.string.workspace_access_use_another))
                }
            }

            WorkspaceAccessMode.CHOOSE -> {
                Text(
                    stringResource(Res.string.workspace_access_create_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { mode = WorkspaceAccessMode.CREATE },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(Res.string.workspace_access_create))
                }
                OutlinedButton(
                    onClick = { mode = WorkspaceAccessMode.RECOVER },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(Res.string.workspace_access_recover))
                }
            }

            WorkspaceAccessMode.CREATE -> {
                Text(
                    stringResource(Res.string.workspace_access_create_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(Res.string.workspace_access_create_description))
                OptionalPinSetup(
                    pinEnabled = pinEnabled,
                    onPinEnabledChange = { value ->
                        pinEnabled = value
                        if (!value) {
                            pin = ""
                            confirmedPin = ""
                        }
                    },
                    pin = pin,
                    onPinChange = { value -> pin = value },
                    confirmedPin = confirmedPin,
                    onConfirmedPinChange = { value -> confirmedPin = value },
                    enabled = !busy,
                )
                Button(
                    onClick = { onCreate(pin.takeIf { pinEnabled }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && (!pinEnabled || pinValid && pin == confirmedPin),
                ) {
                    Text(stringResource(Res.string.workspace_access_create))
                }
                TextButton(
                    onClick = { mode = if (recentWorkspace == null) WorkspaceAccessMode.CHOOSE else WorkspaceAccessMode.RECENT },
                    enabled = !busy,
                ) {
                    Text(stringResource(Res.string.workspace_access_back))
                }
            }

            WorkspaceAccessMode.RECOVER -> {
                Text(
                    stringResource(Res.string.workspace_access_recover_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(Res.string.workspace_access_recover_description))
                OutlinedTextField(
                    value = recoveryKey,
                    onValueChange = { value -> recoveryKey = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.workspace_access_recovery_key_label)) },
                    minLines = 2,
                    enabled = !busy,
                )
                OptionalPinSetup(
                    pinEnabled = pinEnabled,
                    onPinEnabledChange = { value ->
                        pinEnabled = value
                        if (!value) {
                            pin = ""
                            confirmedPin = ""
                        }
                    },
                    pin = pin,
                    onPinChange = { value -> pin = value },
                    confirmedPin = confirmedPin,
                    onConfirmedPinChange = { value -> confirmedPin = value },
                    enabled = !busy,
                )
                Button(
                    onClick = { onRecover(recoveryKey, pin.takeIf { pinEnabled }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && recoveryKey.isNotBlank() && (!pinEnabled || pinValid && pin == confirmedPin),
                ) {
                    Text(stringResource(Res.string.workspace_access_recover))
                }
                OutlinedButton(
                    onClick = { onRestoreBackup(recoveryKey, pin.takeIf { pinEnabled }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && recoveryKey.isNotBlank() && (!pinEnabled || pinValid && pin == confirmedPin),
                ) {
                    Text(stringResource(Res.string.workspace_access_restore_backup))
                }
                TextButton(
                    onClick = { mode = if (recentWorkspace == null) WorkspaceAccessMode.CHOOSE else WorkspaceAccessMode.RECENT },
                    enabled = !busy,
                ) {
                    Text(stringResource(Res.string.workspace_access_back))
                }
            }
        }
        Text(
            stringResource(
                if (pinProtectionActive) {
                    Res.string.workspace_access_pin_security_note
                } else {
                    Res.string.workspace_access_windows_security_note
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Text(
                stringResource(Res.string.workspace_access_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errorMessage?.let { message ->
            Text(message.localizedText(), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 展示一次性 Recovery Key，并要求用户明确确认后才允许落盘。
 *
 * @param recoveryKey 带版本和校验码的恢复文本。
 * @param busy 是否正在创建工作区。
 * @param errorMessage 当前可安全展示的本地化错误。
 * @param onConfirm 确认已离线保存。
 * @param onCancel 放弃创建。
 */
@Composable
private fun RecoveryKeyConfirmationPane(
    recoveryKey: String,
    busy: Boolean,
    errorMessage: WorkspaceUiMessage?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    CenteredWorkspaceCard {
        Text(stringResource(Res.string.workspace_recovery_save_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(Res.string.workspace_recovery_save_description))
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
            Text(stringResource(Res.string.workspace_recovery_confirm_saved))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onConfirm, enabled = confirmed && !busy) {
                Text(stringResource(Res.string.workspace_recovery_confirm_create))
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(Res.string.workspace_action_cancel))
            }
        }
        errorMessage?.let { message -> Text(message.localizedText(), color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * 在读取最近工作区登记时显示无交互加载状态，避免首次渲染闪现创建入口。
 */
@Composable
private fun WorkspaceLoadingPane() {
    CenteredWorkspaceCard {
        Text(stringResource(Res.string.workspace_app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(Res.string.workspace_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 在事务恢复进入安全模式时隐藏正文编辑，只展示脱敏恢复结果和锁定入口。
 *
 * @param messages 脱敏恢复动作。
 * @param busy 是否正在锁定。
 * @param errorMessage 当前可安全展示的本地化错误。
 * @param statusMessage 当前可安全展示的本地化成功提示。
 * @param onBackup 创建密文备份动作。
 * @param onLock 锁定动作。
 */
@Composable
private fun ReadOnlyRecoveryPane(
    messages: List<WorkspaceRecoveryMessage>,
    busy: Boolean,
    errorMessage: WorkspaceUiMessage?,
    statusMessage: WorkspaceUiMessage?,
    onBackup: () -> Unit,
    onLock: () -> Unit,
) {
    CenteredWorkspaceCard {
        Text(stringResource(Res.string.workspace_recovery_required_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(Res.string.workspace_recovery_required_description))
        messages.forEach { message ->
            Text(stringResource(Res.string.workspace_recovery_message_item, message.localizedText()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBackup, enabled = !busy) {
                Text(stringResource(Res.string.workspace_backup_create))
            }
            Button(onClick = onLock, enabled = !busy) {
                Text(stringResource(Res.string.workspace_lock))
            }
        }
        statusMessage?.let { message ->
            Text(message.localizedText(), color = MaterialTheme.colorScheme.primary)
        }
        errorMessage?.let { message ->
            Text(message.localizedText(), color = MaterialTheme.colorScheme.error)
        }
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
