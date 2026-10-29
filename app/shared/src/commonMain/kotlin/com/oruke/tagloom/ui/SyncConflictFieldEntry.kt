package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.platform.WorkspaceSyncConflict
import com.oruke.tagloom.platform.WorkspaceSyncConflictField
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 按一个字段或 Block 展示双版本和就地确认，确认范围不会扩大到整份历史对象。
 * @param conflict 所属对象的冲突历史及审阅 revision。
 * @param field 当前可独立选择的冲突项。
 * @param enabled 当前会话允许写入且没有在途操作。
 * @param onRestore 对此冲突项采用第一份或第二份版本。
 */
@Composable
internal fun SyncConflictFieldEntry(
    conflict: WorkspaceSyncConflict,
    field: WorkspaceSyncConflictField,
    enabled: Boolean,
    onRestore: (Boolean) -> Unit,
) {
    var selectedVersion by remember(conflict.id, conflict.currentRevision, field.id, field.resolved) {
        mutableStateOf<Boolean?>(null)
    }
    Column(Modifier.fillMaxWidth().testTag("sync-conflict-field-${conflict.id}-${field.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(syncConflictFieldLabel(field.labelKey), style = MaterialTheme.typography.titleSmall)
        field.detailLabel?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (field.resolved) {
            Text(stringResource(Res.string.sync_conflict_field_resolved), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SyncConflictVersion(false, "", syncConflictFieldContent(field.localContent, field.labelKey), field.localDeleted, enabled && !field.resolved,
            { selectedVersion = false }, missing = field.localMissing, block = field.localBlock)
        SyncConflictVersion(true, "", syncConflictFieldContent(field.remoteContent, field.labelKey), field.remoteDeleted, enabled && !field.resolved,
            { selectedVersion = true }, missing = field.remoteMissing, block = field.remoteBlock)
        selectedVersion?.let { useRemote ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .testTag("sync-conflict-field-confirm-${conflict.id}-${field.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sync_conflict_field_confirm_description), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { selectedVersion = null; onRestore(useRemote) }, enabled = enabled && !field.resolved) {
                        Text(stringResource(Res.string.sync_conflict_confirm))
                    }
                    TextButton(onClick = { selectedVersion = null }) { Text(stringResource(Res.string.sync_conflict_cancel)) }
                }
            }
        }
    }
}

/**
 * 对已知开关和默认策略使用产品文案，用户填写的普通文本保持原样。
 * @param content 保留版本的可读原始值。
 * @param key 明确语义的业务字段类别。
 * @return 当前语言下的开关、策略说明或原始用户内容。
 */
@Composable
private fun syncConflictFieldContent(content: String, key: String): String {
    if (key in setOf("REQUIRED", "READONLY", "INHERITABLE")) {
        return when (content) {
            "true" -> stringResource(Res.string.sync_conflict_value_yes)
            "false" -> stringResource(Res.string.sync_conflict_value_no)
            else -> content
        }
    }
    if (key == "DEFAULT_POLICY") {
        return when (content.lowercase()) {
            "dynamic" -> stringResource(Res.string.editor_default_policy_dynamic)
            "snapshot" -> stringResource(Res.string.editor_default_policy_snapshot)
            "none" -> stringResource(Res.string.editor_default_policy_none)
            else -> content
        }
    }
    return content
}

/**
 * 将平台提供的字段类别映射为产品文案，未知类别不展示内部协议路径。
 * @param key 不包含内容的业务字段类别。
 * @return 随当前界面语言更新的字段名称。
 */
@Composable
private fun syncConflictFieldLabel(key: String): String = stringResource(when (key) {
    "TITLE" -> Res.string.sync_conflict_field_title
    "NAME" -> Res.string.sync_conflict_field_name
    "ICON", "ICON_KEY" -> Res.string.sync_conflict_field_icon
    "DESCRIPTION" -> Res.string.sync_conflict_field_description
    "PROPERTY" -> Res.string.sync_conflict_field_property
    "MANUAL_PROPERTY_BINDING", "PROPERTY_BINDING" -> Res.string.sync_conflict_field_binding
    "PROPERTY_STYLE" -> Res.string.sync_conflict_field_style
    "BLOCK", "TEMPLATE_BLOCK" -> Res.string.sync_conflict_field_block
    "ANNOTATION" -> Res.string.sync_conflict_field_annotation
    "DEFAULT_TAB" -> Res.string.sync_conflict_field_default_tab
    "OPTIONS", "OPTION" -> Res.string.sync_conflict_field_option
    "PARENT", "PARENT_TAGS" -> Res.string.sync_conflict_field_parent
    "QUERY", "FILTER", "SORT", "PRESENTATION" -> Res.string.sync_conflict_field_query
    "TAGS" -> Res.string.sync_conflict_field_tags
    "TYPE" -> Res.string.sync_conflict_field_type
    "DEFAULT_VALUE" -> Res.string.sync_conflict_field_default_value
    "REQUIRED" -> Res.string.sync_conflict_field_required
    "READONLY" -> Res.string.sync_conflict_field_readonly
    "INHERITABLE" -> Res.string.sync_conflict_field_inheritable
    "DEFAULT_POLICY" -> Res.string.sync_conflict_field_default_policy
    "ORDER" -> Res.string.sync_conflict_field_order
    "OPTION_LABEL" -> Res.string.sync_conflict_field_option_label
    "OPTION_COLOR" -> Res.string.sync_conflict_field_option_color
    "OPTION_PARENT" -> Res.string.sync_conflict_field_option_parent
    else -> Res.string.sync_conflict_field_other
})
