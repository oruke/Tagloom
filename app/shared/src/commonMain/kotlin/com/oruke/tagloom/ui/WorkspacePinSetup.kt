package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染可选 PIN 的开关、说明和二次确认输入。
 *
 * 未启用 PIN 时，平台使用当前设备的系统保护材料自动打开工作区；因此该组件不能
 * 通过隐藏输入框的方式暗示 PIN 是必填步骤。
 *
 * @param pinEnabled 是否启用 PIN 保护。
 * @param onPinEnabledChange 用户切换 PIN 保护时的回调。
 * @param pin 当前 PIN 草稿。
 * @param onPinChange PIN 输入变化回调。
 * @param confirmedPin 确认 PIN 草稿。
 * @param onConfirmedPinChange 确认 PIN 输入变化回调。
 * @param enabled 是否允许修改当前选择。
 */
@Composable
internal fun OptionalPinSetup(
    pinEnabled: Boolean,
    onPinEnabledChange: (Boolean) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    confirmedPin: String,
    onConfirmedPinChange: (String) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = pinEnabled,
                onCheckedChange = onPinEnabledChange,
                enabled = enabled,
            )
            Text(stringResource(Res.string.workspace_access_pin_optional_label))
        }
        Text(
            stringResource(Res.string.workspace_access_pin_optional_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pinEnabled) {
            WorkspacePinTextField(
                value = pin,
                onValueChange = onPinChange,
                label = stringResource(Res.string.workspace_access_pin_label),
                enabled = enabled,
            )
            WorkspacePinTextField(
                value = confirmedPin,
                onValueChange = onConfirmedPinChange,
                label = stringResource(Res.string.workspace_access_pin_confirm_label),
                enabled = enabled,
            )
        }
    }
}

/**
 * 渲染数字 PIN 输入框，并在视觉上隐藏输入内容。
 *
 * @param value 当前 PIN 草稿。
 * @param onValueChange 输入变化回调。
 * @param label 已本地化输入框标签。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun WorkspacePinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter(Char::isDigit).take(MAXIMUM_PIN_LENGTH)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        enabled = enabled,
    )
}

/** 本机 PIN 允许的最大数字长度。 */
private const val MAXIMUM_PIN_LENGTH: Int = 12
