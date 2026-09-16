package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.time.TemporalInputError
import com.oruke.tagloom.time.TemporalInputException
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 显示本地化日期值并用明确确认的编辑流程提交，显示偏好变化不产生属性写入。
 * @param value 原始协议值，空文本代表尚未填写。
 * @param dateOnly 是否为不做时区转换的纯日期。
 * @param editable 是否允许编辑。
 * @param onCommit 确认后的标准 ISO 值，空文本表示清除。
 * @param modifier 外部尺寸和自动化语义。
 */
@Composable
internal fun TemporalValueField(
    value: String,
    dateOnly: Boolean,
    editable: Boolean,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = currentDateTimeSettings()
    var editing by remember { mutableStateOf(false) }
    Text(
        text = controller.format(value, dateOnly).ifBlank { "—" },
        modifier = modifier.fillMaxWidth().clickable(enabled = editable, role = Role.Button) { editing = true }
            .padding(vertical = 5.dp),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (editing) TemporalValueDialog(value, dateOnly, controller, { editing = false }, onCommit)
}

/**
 * 编辑时冻结地区规则，避免输入过程中系统变化改变解析语义；未修改时直接关闭以保留原始精度和偏移。
 * @param original 权威协议值。
 * @param dateOnly 是否为纯日期。
 * @param controller 当前本机显示能力。
 * @param onDismiss 关闭编辑。
 * @param onCommit 确认新协议值。
 */
@Composable
private fun TemporalValueDialog(
    original: String,
    dateOnly: Boolean,
    controller: DateTimeSettingsController,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit
) {
    val preferences = remember { controller.preferences }
    val system = remember { controller.system }
    val platform = controller.environment.platform
    val initial = remember { platform.format(original, dateOnly, preferences, system) }
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<TemporalInputError?>(null) }
    val focus = remember { FocusRequester() }
    val example = remember {
        platform.format(
            if (dateOnly) controller.previewTimestamp.substringBefore('T') else controller.previewTimestamp,
            dateOnly,
            preferences,
            system
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (dateOnly) Res.string.datetime_edit_date else Res.string.datetime_edit_timestamp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    draft, { draft = it; error = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("temporal-value-input"),
                    singleLine = true, isError = error != null,
                    label = { Text(stringResource(if (dateOnly) Res.string.datetime_edit_date else Res.string.datetime_edit_timestamp)) },
                )
                Text(
                    stringResource(Res.string.datetime_value_example, example),
                    style = MaterialTheme.typography.bodySmall
                )
                if (!dateOnly) Text(
                    preferences.zoneId ?: system.zoneId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { reason ->
                    Text(
                        stringResource(
                            when (reason) {
                                TemporalInputError.INVALID -> Res.string.datetime_invalid
                                TemporalInputError.NONEXISTENT -> Res.string.datetime_nonexistent
                                TemporalInputError.AMBIGUOUS -> Res.string.datetime_ambiguous
                            }
                        ), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (draft == initial) onDismiss()
                    else try {
                        onCommit(platform.parse(draft, dateOnly, preferences, system))
                        onDismiss()
                    } catch (failure: TemporalInputException) {
                        error = failure.reason
                    } catch (_: IllegalArgumentException) {
                        error = TemporalInputError.INVALID
                    }
                },
                modifier = Modifier.testTag("temporal-value-confirm")
            ) { Text(stringResource(Res.string.workspace_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.workspace_action_cancel)) } },
    )
}
