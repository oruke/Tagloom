package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 搜索平台实际支持的时区，使用稳定 ID 保存，默认项持续跟随系统。
 * @param controller 当前设备设置与平台目录。
 * @param onDismiss 取消选择。
 * @param onSelect 确认时区 ID；空值代表跟随系统。
 */
@Composable
internal fun DateTimeZonePicker(
    controller: DateTimeSettingsController,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val zones = remember(controller.system.localeTag) { controller.environment.platform.timeZones(controller.system) }
    val filtered = remember(zones, query) {
        val search = query.trim().replace('_', ' ')
        zones.filter { it.id.replace('_', ' ').contains(search, true) || it.name.contains(search, true) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.datetime_choose_zone)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.datetime_search_zone)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("datetime-zone-search")
                )
                TextButton(
                    onClick = { onSelect(null) },
                    modifier = Modifier.fillMaxWidth().testTag("datetime-zone-system")
                ) {
                    Text(stringResource(Res.string.datetime_system_value, controller.system.zoneId))
                }
                if (filtered.isEmpty()) Text(stringResource(Res.string.datetime_zone_empty))
                LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                    items(filtered, key = { it.id }) { zone ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(zone.id) }.padding(vertical = 4.dp)
                                .testTag("datetime-zone-${zone.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = controller.preferences.zoneId == zone.id, onClick = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(zone.id, style = MaterialTheme.typography.bodyMedium)
                                if (zone.name != zone.id) Text(
                                    zone.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.workspace_action_cancel)) } },
    )
}
