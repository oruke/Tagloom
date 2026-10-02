package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.platform.WorkspaceStorageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 设置当前设备的工作区模式，首次连接确认后自动更新服务器副本。
 * @param workspace 当前已解锁工作区控制器。
 * @param scope 跟随宿主的操作作用域。
 * @param onBack 返回设置列表。
 */
@Composable
internal fun ServerRecoverySettingsPane(workspace: WorkspaceUiController, scope: CoroutineScope, onBack: () -> Unit) {
    val controller = workspace.serverRecovery
    var address by remember { mutableStateOf("") }
    var bootstrap by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(WorkspaceStorageMode.LOCAL) }
    val status = controller.status
    val needsKey = status == null || status.requiresRecoveryKey
    LaunchedEffect(controller) { scope.launch { controller.refresh() } }
    LaunchedEffect(status) { status?.let { address = it.serverUrl } }
    LaunchedEffect(controller.loaded, status?.mode) {
        if (controller.loaded) selectedMode = status?.mode ?: WorkspaceStorageMode.LOCAL
    }
    LaunchedEffect(controller.published) {
        if (controller.published) { bootstrap = ""; recoveryKey = "" }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack, enabled = !workspace.busy) { Text(stringResource(Res.string.server_recovery_back)) }
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.workspace_mode_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(Res.string.workspace_mode_settings_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (workspace.busy || controller.updating) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (controller.loaded) {
                    WorkspaceModeSelector(selectedMode, { mode ->
                        if (mode == WorkspaceStorageMode.SERVER || status?.mode != WorkspaceStorageMode.SERVER) selectedMode = mode
                        else scope.launch {
                            controller.useLocalMode()
                            selectedMode = controller.status?.mode ?: WorkspaceStorageMode.LOCAL
                        }
                    }, !workspace.busy)
                    Text(stringResource(if (selectedMode == WorkspaceStorageMode.LOCAL) Res.string.workspace_mode_local_description
                        else Res.string.workspace_mode_server_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedMode == WorkspaceStorageMode.SERVER) {
                        ServerRecoveryAddressField(address, { address = it }, !workspace.busy, readOnly = status?.registered == true)
                        status?.uploadedAt?.let { time ->
                            Text(stringResource(Res.string.server_recovery_uploaded_at, currentDateTimeSettings().format(time)), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (status?.registered != true) ServerRegistrationField(bootstrap, { bootstrap = it }, !workspace.busy)
                        if (needsKey) {
                            if (status?.requiresRecoveryKey == true && status.registered) Text(stringResource(Res.string.server_recovery_key_rotated))
                            OutlinedTextField(
                                value = recoveryKey, onValueChange = { recoveryKey = it }, minLines = 2,
                                label = { Text(stringResource(Res.string.workspace_access_recovery_key_label)) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().testTag("server-recovery-key"), enabled = !workspace.busy,
                            )
                        }
                        Button(
                            onClick = { scope.launch { controller.publish(address, bootstrap, recoveryKey) } },
                        enabled = !workspace.busy && !controller.updating && address.isNotBlank() && (!needsKey || recoveryKey.isNotBlank()),
                            modifier = Modifier.testTag("server-recovery-publish"),
                        ) {
                            Text(stringResource(if (status?.mode == WorkspaceStorageMode.SERVER) Res.string.server_recovery_update else Res.string.workspace_mode_enable_server))
                        }
                        if (status?.mode == WorkspaceStorageMode.SERVER) Text(
                            stringResource(if (controller.updating) Res.string.workspace_mode_updating else if (status.pendingChanges)
                                Res.string.workspace_mode_pending else Res.string.workspace_mode_automatic_enabled),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!workspace.busy) {
                    OutlinedButton(onClick = { scope.launch { controller.refresh() } }) { Text(stringResource(Res.string.server_recovery_retry)) }
                }
                if (controller.published && selectedMode == WorkspaceStorageMode.SERVER) Text(stringResource(Res.string.server_recovery_success), color = MaterialTheme.colorScheme.primary)
                controller.errorCode?.let { ServerRecoveryError(it) }
            }
        }
    }
}

/**
 * 新建和切换服务器模式时输入部署者登记访问码，明文不进入保存状态。
 * @param value 当前输入。
 * @param onValueChange 更新当前表单草稿。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun ServerRegistrationField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { Text(stringResource(Res.string.server_recovery_bootstrap)) },
        supportingText = { Text(stringResource(Res.string.server_recovery_bootstrap_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("server-recovery-bootstrap"), enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
    )
}

/**
 * 渲染恢复与上传共用的服务器地址输入。
 * @param value 当前服务器地址。
 * @param onValueChange 更新输入草稿。
 * @param enabled 是否可编辑，已配置设备禁止静默切换服务器。
 * @param readOnly 已配置地址保持可阅读与复制，但禁止直接变更。
 */
@Composable
internal fun ServerRecoveryAddressField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, readOnly: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled, readOnly = readOnly, singleLine = true,
        label = { Text(stringResource(Res.string.server_recovery_address)) },
        supportingText = { Text(stringResource(Res.string.server_recovery_address_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("server-recovery-address"))
}

/**
 * 映射有限错误分类，不展示底层响应、绝对路径或密钥材料。
 * @param code 经过控制器分类的稳定错误码。
 */
@Composable
internal fun ServerRecoveryError(code: String) {
    val resource = when (code) {
        "invalid_server_url" -> Res.string.server_recovery_invalid_address
        "authentication_failed" -> Res.string.server_recovery_authentication_failed
        "recovery_key_invalid" -> Res.string.server_recovery_invalid_key
        "recovery_key_required" -> Res.string.server_recovery_key_rotated
        "registration_required" -> Res.string.workspace_mode_registration_required
        "recovery_revision_changed" -> Res.string.server_recovery_conflict
        "recovery_archive_too_large" -> Res.string.server_recovery_too_large
        "recovery_archive_integrity_failed" -> Res.string.server_recovery_integrity_failed
        else -> Res.string.server_recovery_failed
    }
    Text(stringResource(resource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
