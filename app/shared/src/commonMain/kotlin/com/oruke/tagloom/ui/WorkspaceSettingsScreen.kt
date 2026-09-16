package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染侧栏和窄屏共用的设置导航按钮。
 *
 * @param selected 当前是否位于设置页面。
 * @param onClick 打开设置页面。
 * @param modifier 外部尺寸与留白。
 */
@Composable
internal fun WorkspaceSettingsEntry(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Res.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.workspace_settings_title), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 展示当前设备 PIN 和工作区备份、安全设置，业务操作交由会话控制器执行。
 *
 * @param requiresPin 当前设备是否已启用 PIN。
 * @param busy 是否正在执行工作区操作；此时阻止重复触发安全动作。
 * @param statusMessage 最近一次操作的非敏感结果。
 * @param onBack 返回之前的笔记及其草稿。
 * @param onManagePin 打开启用、更换或停用 PIN 的认证流程。
 * @param onBackup 选择位置并创建加密备份。
 * @param onRotateMasterKey 打开主密钥轮换确认流程。
 * @param onLock 锁定当前工作区。
 */
@Composable
internal fun WorkspaceSettingsScreen(
    requiresPin: Boolean,
    busy: Boolean,
    statusMessage: WorkspaceUiMessage?,
    onBack: () -> Unit,
    onManagePin: () -> Unit,
    onBackup: () -> Unit,
    onRotateMasterKey: () -> Unit,
    onLock: () -> Unit,
) {
    var dateTimeOpen by remember { mutableStateOf(false) }
    if (dateTimeOpen) {
        DateTimeSettingsScreen(onBack = { dateTimeOpen = false })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Icon(
                    painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Text(stringResource(Res.string.workspace_settings_back))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(Res.drawable.ic_settings), contentDescription = null,
                        modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(Res.string.workspace_settings_title), style = MaterialTheme.typography.headlineLarge)
                }
                Text(
                    stringResource(Res.string.workspace_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                statusMessage?.let { message ->
                    Text(
                        message.localizedText(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SettingsActionRow(
                    icon = Res.drawable.ic_calendar,
                    title = stringResource(Res.string.datetime_title),
                    description = stringResource(Res.string.datetime_entry_description),
                    action = stringResource(Res.string.datetime_configure),
                    enabled = true,
                    onClick = { dateTimeOpen = true },
                )
                Text(
                    stringResource(Res.string.workspace_settings_device_section),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                SettingsActionRow(
                    icon = Res.drawable.ic_keypad,
                    title = stringResource(Res.string.workspace_access_pin_label),
                    description = stringResource(
                        if (requiresPin) Res.string.workspace_settings_pin_enabled else Res.string.workspace_settings_pin_disabled,
                    ),
                    action = stringResource(
                        if (requiresPin) Res.string.workspace_settings_manage_pin else Res.string.workspace_pin_enable_confirm,
                    ),
                    enabled = !busy,
                    onClick = onManagePin,
                )
                SettingsActionRow(
                    icon = Res.drawable.ic_lock,
                    title = stringResource(Res.string.workspace_lock),
                    description = stringResource(Res.string.workspace_settings_lock_description),
                    action = stringResource(Res.string.workspace_lock),
                    enabled = !busy,
                    onClick = onLock,
                )
                Text(
                    stringResource(Res.string.workspace_settings_data_section),
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                SettingsActionRow(
                    icon = Res.drawable.ic_archive,
                    title = stringResource(Res.string.workspace_settings_backup_title),
                    description = stringResource(Res.string.workspace_settings_backup_description),
                    action = stringResource(Res.string.workspace_backup_create),
                    enabled = !busy,
                    onClick = onBackup,
                )
                SettingsActionRow(
                    icon = Res.drawable.ic_key,
                    title = stringResource(Res.string.workspace_settings_master_key_title),
                    description = stringResource(Res.string.workspace_settings_master_key_description),
                    action = stringResource(Res.string.workspace_menu_rotate_master_key),
                    enabled = !busy,
                    onClick = onRotateMasterKey,
                )
            }
        }
    }
}

/**
 * 用分隔线组织单项设置；窄屏将动作移至说明下方，避免挤压文字。
 *
 * @param icon 表示设置用途的装饰性线性图标。
 * @param title 设置名称。
 * @param description 当前状态、作用范围或操作说明。
 * @param action 明确的操作名称。
 * @param enabled 是否允许触发操作。
 * @param onClick 交给会话执行的动作。
 */
@Composable
private fun SettingsActionRow(
    icon: DrawableResource,
    title: String,
    description: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            if (maxWidth < 520.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsActionDescription(icon, title, description)
                    OutlinedButton(
                        onClick = onClick, enabled = enabled,
                        modifier = Modifier.padding(start = 32.dp).heightIn(min = 40.dp),
                    ) {
                        Text(action)
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsActionDescription(icon, title, description, Modifier.weight(1f))
                    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
                        Text(action)
                    }
                }
            }
        }
    }
}

/**
 * 展示设置名称及说明，支持系统字体缩放与自然换行。
 *
 * @param icon 不重复朗读设置名称的装饰性图标。
 * @param title 设置名称。
 * @param description 操作说明或当前状态。
 * @param modifier 分配给文本的布局空间。
 */
@Composable
private fun SettingsActionDescription(
    icon: DrawableResource,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(icon), contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
