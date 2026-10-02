package com.oruke.tagloom.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.TagloomAppState
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.platform.WorkspaceRecentWorkspace
import com.oruke.tagloom.platform.WorkspaceRecoveryMessage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 根据工作区锁定状态渲染最近工作区 PIN、Recovery Key、恢复或编辑界面。
 *
 * @param capabilities 当前宿主提供的跨平台能力集合。
 * @param documentFileGateway 可选文档导出能力。
 * @param appState 宿主可跨配置变更保留的共享内存状态。
 */
@Composable
fun WorkspaceLifecycleScreen(
    capabilities: TagloomCapabilities,
    documentFileGateway: DocumentFileGateway? = null,
    appState: TagloomAppState? = null,
) {
    val compositionScope = rememberCoroutineScope()
    val currentState = appState ?: remember(capabilities) {
        TagloomAppState(capabilities, compositionScope)
    }
    val controller = currentState.controller
    val coroutineScope = currentState.operationScope

    LaunchedEffect(controller) {
        coroutineScope.launch { controller.initialize() }
    }

    when (controller.phase) {
        WorkspaceUiPhase.INITIALIZING -> WorkspaceLoadingPane()
        WorkspaceUiPhase.WELCOME -> WorkspaceWelcomePane(
            recentWorkspace = controller.recentWorkspace,
            busy = controller.busy,
            errorMessage = controller.errorMessage,
            serverRecovery = controller.serverRecovery,
            onRestoreServer = { address, key, pin ->
                coroutineScope.launch { controller.serverRecovery.restore(address, key, pin) }
            },
            onOpenRecent = { coroutineScope.launch { controller.openRecentWorkspace() } },
            onUnlock = { pin -> coroutineScope.launch { controller.unlockRecentWorkspace(pin) } },
            onCreate = { pin -> coroutineScope.launch { controller.prepareCreation(pin) } },
            onCreateServer = { address, bootstrap, pin ->
                coroutineScope.launch { controller.serverRecovery.prepareCreation(address, bootstrap, pin) }
            },
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
            coroutineScope = coroutineScope,
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
internal fun CenteredWorkspaceCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = { content() },
            )
        }
    }
}
