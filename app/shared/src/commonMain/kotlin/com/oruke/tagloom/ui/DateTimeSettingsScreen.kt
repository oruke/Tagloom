package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.time.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 编辑当前设备的日期时间显示偏好，预置选项即时保存，自定义格式校验后应用。
 * @param onBack 返回设置主页，不影响当前笔记草稿。
 */
@Composable
internal fun DateTimeSettingsScreen(onBack: () -> Unit) {
    val controller = currentDateTimeSettings()
    val scope = rememberCoroutineScope()
    val preferences = controller.preferences
    val system = controller.system
    var zonePicker by remember { mutableStateOf(false) }
    var pattern by remember(preferences.customDatePattern) { mutableStateOf(preferences.customDatePattern) }
    val patternValid = pattern.isValidDateDisplayPattern()
    val enabled = controller.loaded && !controller.saving
    val previewPreferences =
        if (preferences.dateFormat == DateDisplayFormat.CUSTOM && patternValid) preferences.copy(customDatePattern = pattern) else preferences
    LaunchedEffect(Unit) { controller.refreshSystem() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) {
                Icon(
                    painterResource(Res.drawable.ic_arrow_back),
                    stringResource(Res.string.datetime_back)
                )
            }
            Icon(painterResource(Res.drawable.ic_calendar), null, Modifier.padding(horizontal = 8.dp).size(24.dp))
            Text(stringResource(Res.string.datetime_title), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        if (controller.failed) Text(
            stringResource(Res.string.datetime_save_failed),
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        Column(
            Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState()).padding(24.dp).testTag("datetime-settings"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(Res.string.datetime_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.datetime_preview), style = MaterialTheme.typography.labelLarge)
                    Text(
                        controller.environment.platform.format(
                            controller.previewTimestamp,
                            false,
                            previewPreferences,
                            system
                        ),
                        Modifier.testTag("datetime-preview"), style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        preferences.zoneId ?: system.zoneId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.datetime_zone), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(
                    onClick = { zonePicker = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("datetime-zone")
                ) {
                    Text(
                        preferences.zoneId ?: stringResource(Res.string.datetime_system_value, system.zoneId),
                        Modifier.weight(1f)
                    )
                    TagloomChevronDown()
                }
            }
            DateTimeChoice(
                stringResource(Res.string.datetime_date_format), "datetime-date-format", preferences.dateFormat,
                DateDisplayFormat.entries.map { it to it.localizedDateFormatName() }, enabled,
            ) { next -> scope.launch { controller.save(preferences.copy(dateFormat = next)) } }
            if (preferences.dateFormat == DateDisplayFormat.CUSTOM) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = pattern, onValueChange = { pattern = it },
                        modifier = Modifier.fillMaxWidth().testTag("datetime-custom-pattern"),
                        label = { Text(stringResource(Res.string.datetime_custom_pattern)) },
                        singleLine = true, isError = !patternValid, enabled = enabled,
                        supportingText = { Text(stringResource(if (patternValid) Res.string.datetime_pattern_hint else Res.string.datetime_pattern_invalid)) },
                    )
                    TextButton(
                        onClick = { scope.launch { controller.save(preferences.copy(customDatePattern = pattern)) } },
                        enabled = enabled && patternValid && pattern != preferences.customDatePattern,
                        modifier = Modifier.align(Alignment.End).testTag("datetime-apply-pattern"),
                    ) { Text(stringResource(Res.string.editor_action_apply)) }
                }
            }
            DateTimeChoice(
                stringResource(Res.string.datetime_hour_cycle), "datetime-hour-cycle", preferences.hourCycle,
                DisplayHourCycle.entries.map { it to it.localizedHourCycleName() }, enabled,
            ) { next -> scope.launch { controller.save(preferences.copy(hourCycle = next)) } }
            Text(
                stringResource(Res.string.datetime_date_only_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            TextButton(
                onClick = {
                    scope.launch {
                        if (controller.save(DateTimePreferences())) pattern = controller.preferences.customDatePattern
                    }
                },
                enabled = enabled, modifier = Modifier.testTag("datetime-reset"),
            ) { Text(stringResource(Res.string.datetime_reset)) }
        }
    }
    if (zonePicker) DateTimeZonePicker(controller, onDismiss = { zonePicker = false }) { zone ->
        zonePicker = false
        scope.launch { controller.save(preferences.copy(zoneId = zone)) }
    }
}

/**
 * 渲染同一设置表单中的单选下拉，不用固定文字宽度限制本地化文案。
 * @param title 字段标签。
 * @param tag 测试及自动化语义标识。
 * @param value 当前确认值。
 * @param options 可选值及已本地化名称。
 * @param enabled 是否允许改动。
 * @param onSelect 用户选择事件。
 */
@Composable
private fun <T> DateTimeChoice(
    title: String,
    tag: String,
    value: T,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(tag)
            ) {
                Text(options.first { it.first == value }.second, Modifier.weight(1f))
                TagloomChevronDown()
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (option, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(option) })
                }
            }
        }
    }
}

/** @return 当前日期格式选项的界面名称。 */
@Composable
private fun DateDisplayFormat.localizedDateFormatName(): String = stringResource(
    when (this) {
        DateDisplayFormat.SYSTEM -> Res.string.datetime_system
        DateDisplayFormat.ISO -> Res.string.datetime_date_iso
        DateDisplayFormat.DAY_FIRST -> Res.string.datetime_date_day_first
        DateDisplayFormat.MONTH_FIRST -> Res.string.datetime_date_month_first
        DateDisplayFormat.CUSTOM -> Res.string.datetime_date_custom
    }
)

/** @return 当前时间制选项的界面名称。 */
@Composable
private fun DisplayHourCycle.localizedHourCycleName(): String = stringResource(
    when (this) {
        DisplayHourCycle.SYSTEM -> Res.string.datetime_system
        DisplayHourCycle.TWELVE -> Res.string.datetime_hour_12
        DisplayHourCycle.TWENTY_FOUR -> Res.string.datetime_hour_24
    }
)
