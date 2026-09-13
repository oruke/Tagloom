package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 收集当前与新本机 PIN，并且只允许用户输入 6 至 12 位数字。
 *
 * @param onDismiss 关闭动作。
 * @param onConfirm 已满足长度和一致性约束的确认动作。
 * @param onDisable 已验证当前 PIN 后停用 PIN 的动作。
 */
@Composable
internal fun PinUpdateDialog(
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
internal fun PinEnableDialog(
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
internal fun MasterKeyRotationDialog(
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
                        stringResource(Res.string.workspace_master_rotation_device_account_note),
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
internal fun RotatedRecoveryKeyDialog(
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
