package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.oruke.tagloom.platform.WorkspaceRecentWorkspace
import com.oruke.tagloom.platform.WorkspaceStorageMode
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/** 欢迎页的访问步骤，存储模式独立选择，两种模式共享新建和恢复流程。 */
private enum class WorkspaceWelcomeStep {
    /** 使用本机登记打开最近工作区。 */
    RECENT,
    /** 选择存储模式及新建或恢复。 */
    CHOOSE,
    /** 新建并保存恢复密钥。 */
    CREATE,
    /** 凭恢复密钥连接已有工作区。 */
    RESTORE,
}

/**
 * 提供本地和服务器两种模式的对称新建、恢复入口；敏感草稿不进入保存状态。
 * @param recentWorkspace 本机最近工作区，没有时进入模式选择。
 * @param busy 当前是否正在认证或创建。
 * @param errorMessage 本地操作的脱敏错误。
 * @param onOpenRecent 使用设备保护材料打开最近工作区。
 * @param onUnlock 使用本机 PIN 解锁。
 * @param onCreate 选择本地文件夹创建。
 * @param onRecover 选择既有本地文件夹并使用恢复密钥认证。
 * @param onRestoreBackup 使用恢复密钥导入本地备份。
 * @param serverRecovery 服务器能力与脱敏错误状态。
 * @param onRestoreServer 凭服务器地址和恢复密钥恢复。
 * @param onCreateServer 使用服务器地址、登记访问码和可选 PIN 新建。
 */
@Composable
internal fun WorkspaceWelcomePane(
    recentWorkspace: WorkspaceRecentWorkspace?,
    busy: Boolean,
    errorMessage: WorkspaceUiMessage?,
    onOpenRecent: () -> Unit,
    onUnlock: (String) -> Unit,
    onCreate: (String?) -> Unit,
    onRecover: (String, String?) -> Unit,
    onRestoreBackup: (String, String?) -> Unit,
    serverRecovery: WorkspaceServerRecoveryController,
    onRestoreServer: (String, String, String?) -> Unit,
    onCreateServer: (String, String, String?) -> Unit,
) {
    var step by remember(recentWorkspace) {
        mutableStateOf(if (recentWorkspace == null) WorkspaceWelcomeStep.CHOOSE else WorkspaceWelcomeStep.RECENT)
    }
    var mode by remember { mutableStateOf(WorkspaceStorageMode.LOCAL) }
    var pin by remember(step, mode) { mutableStateOf("") }
    var confirmedPin by remember(step, mode) { mutableStateOf("") }
    var pinEnabled by remember(step, mode) { mutableStateOf(false) }
    var key by remember(step, mode) { mutableStateOf("") }
    var bootstrap by remember(step, mode) { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val server = mode == WorkspaceStorageMode.SERVER
    val pinValid = pin.length in 6..12 && pin.all(Char::isDigit)

    CenteredWorkspaceCard {
        Text(stringResource(Res.string.workspace_app_name), style = MaterialTheme.typography.headlineLarge)
        if (step == WorkspaceWelcomeStep.RECENT) {
            val recent = requireNotNull(recentWorkspace)
            Text(stringResource(Res.string.workspace_access_continue_to, recent.displayName), style = MaterialTheme.typography.titleMedium)
            if (recent.requiresPin) {
                WorkspacePinTextField(pin, { pin = it }, stringResource(Res.string.workspace_access_pin_label), !busy)
                Button(onClick = { onUnlock(pin) }, enabled = !busy && pinValid, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.workspace_access_unlock))
                }
            } else {
                Text(stringResource(Res.string.workspace_access_auto_open_description))
                Button(onClick = onOpenRecent, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.workspace_access_open))
                }
            }
            TextButton(onClick = { step = WorkspaceWelcomeStep.CHOOSE }, enabled = !busy) {
                Text(stringResource(Res.string.workspace_access_use_another))
            }
        } else {
            if (serverRecovery.available) WorkspaceModeSelector(mode, { mode = it }, !busy)
            when (step) {
                WorkspaceWelcomeStep.CHOOSE -> {
                    Text(stringResource(Res.string.workspace_mode_choose_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { step = WorkspaceWelcomeStep.CREATE }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("workspace-create")) {
                        Text(stringResource(Res.string.server_recovery_create_new))
                    }
                    OutlinedButton(onClick = { step = WorkspaceWelcomeStep.RESTORE }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("workspace-restore")) {
                        Text(stringResource(if (server) Res.string.server_recovery_restore else Res.string.workspace_mode_restore_local))
                    }
                    if (recentWorkspace != null) TextButton(onClick = { step = WorkspaceWelcomeStep.RECENT }, enabled = !busy) {
                        Text(stringResource(Res.string.workspace_access_back))
                    }
                }
                WorkspaceWelcomeStep.CREATE, WorkspaceWelcomeStep.RESTORE -> {
                    val creating = step == WorkspaceWelcomeStep.CREATE
                    Text(stringResource(if (creating) Res.string.workspace_access_create_title else if (server) Res.string.server_recovery_restore else Res.string.workspace_mode_restore_local),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when {
                        server && creating -> Res.string.workspace_mode_create_server_description
                        server -> Res.string.server_recovery_restore_description
                        creating -> Res.string.workspace_access_create_description
                        else -> Res.string.workspace_mode_restore_local_description
                    }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (server) ServerRecoveryAddressField(address, { address = it }, !busy)
                    if (server && creating) ServerRegistrationField(bootstrap, { bootstrap = it }, !busy)
                    if (!creating) OutlinedTextField(
                        value = key, onValueChange = { key = it }, minLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag("server-recovery-key"),
                        label = { Text(stringResource(Res.string.workspace_access_recovery_key_label)) },
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                    )
                    OptionalPinSetup(
                        pinEnabled, { pinEnabled = it; if (!it) { pin = ""; confirmedPin = "" } },
                        pin, { pin = it }, confirmedPin, { confirmedPin = it }, !busy,
                    )
                    val ready = !busy && (!server || address.isNotBlank()) &&
                        (!creating || !server || bootstrap.isNotBlank()) && (creating || key.isNotBlank()) &&
                        (!pinEnabled || pinValid && pin == confirmedPin)
                    Button(onClick = {
                        val protection = pin.takeIf { pinEnabled }
                        when {
                            creating && server -> onCreateServer(address, bootstrap, protection)
                            creating -> onCreate(protection)
                            server -> onRestoreServer(address, key, protection)
                            else -> onRecover(key, protection)
                        }
                    }, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("workspace-access-submit")) {
                        Text(stringResource(when {
                            creating -> Res.string.workspace_access_create
                            server -> Res.string.server_recovery_restore
                            else -> Res.string.workspace_mode_select_folder_restore
                        }))
                    }
                    if (!creating && !server) TextButton(
                        onClick = { onRestoreBackup(key, pin.takeIf { pinEnabled }) }, enabled = ready,
                    ) { Text(stringResource(Res.string.workspace_access_restore_backup)) }
                    TextButton(onClick = { step = WorkspaceWelcomeStep.CHOOSE }, enabled = !busy) {
                        Text(stringResource(Res.string.workspace_access_back))
                    }
                }
                WorkspaceWelcomeStep.RECENT -> Unit
            }
        }
        if (step != WorkspaceWelcomeStep.CHOOSE) {
            val protected = if (step == WorkspaceWelcomeStep.RECENT) recentWorkspace?.requiresPin == true else pinEnabled
            Text(stringResource(if (protected) Res.string.workspace_access_pin_security_note else Res.string.workspace_access_device_security_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        errorMessage?.let { Text(it.localizedText(), color = MaterialTheme.colorScheme.error) }
        if (server) serverRecovery.errorCode?.let { ServerRecoveryError(it) }
    }
}
