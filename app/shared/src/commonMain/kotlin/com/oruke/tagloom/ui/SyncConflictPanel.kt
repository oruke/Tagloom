package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.platform.WorkspaceSyncConflict
import com.oruke.tagloom.platform.WorkspaceSyncConflictBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 在工作区模式设置中展示纵向冲突历史入口，不复制服务器配置或创建独立导航。
 * @param controller 当前已解锁会话的同步和历史状态。
 * @param scope 随页面宿主取消的操作作用域。
 * @param enabled 工作区没有在途前台操作时允许恢复或刷新；后台同步由事务锁和 revision 门禁协调。
 */
@Composable
internal fun SyncConflictPanel(controller: WorkspaceServerRecoveryController, scope: CoroutineScope, enabled: Boolean) {
    var expanded by remember(controller) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("sync-conflicts"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(Res.string.sync_conflicts_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.sync_conflicts_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if ((controller.status?.conflictsCount ?: 0) > 0) {
            Text(stringResource(Res.string.sync_conflicts_pending, controller.status?.conflictsCount ?: 0), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = {
                expanded = !expanded
                if (expanded) scope.launch { controller.loadConflicts() }
            },
            enabled = enabled,
            modifier = Modifier.testTag("sync-conflicts-toggle"),
        ) {
            Text(stringResource(if (expanded) Res.string.sync_conflicts_hide else Res.string.sync_conflicts_show))
        }
        if (expanded) {
            if (controller.conflictsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (controller.conflictsLoaded && controller.conflicts.isEmpty()) {
                Text(stringResource(Res.string.sync_conflicts_empty), style = MaterialTheme.typography.bodyMedium)
            }
            controller.conflicts.forEach { conflict ->
                key(conflict.id) {
                    SyncConflictEntry(conflict, enabled && !controller.conflictsLoading) { useRemote, fieldId ->
                        scope.launch { controller.resolveConflict(conflict, useRemote, fieldId) }
                    }
                }
            }
            OutlinedButton(
                onClick = { scope.launch { controller.loadConflicts() } },
                enabled = enabled && !controller.conflictsLoading,
                modifier = Modifier.testTag("sync-conflicts-refresh"),
            ) { Text(stringResource(Res.string.sync_conflicts_refresh)) }
        }
    }
}

/**
 * 先展开具体冲突项，再就地确认；完整双版本仍保留为可展开的只读历史。
 * @param conflict 已认证的完整双版本历史与当前对象 revision。
 * @param enabled 是否可以发起版本恢复。
 * @param onRestore 以版本方向及可选字段身份创建新的业务写入。
 */
@Composable
private fun SyncConflictEntry(conflict: WorkspaceSyncConflict, enabled: Boolean, onRestore: (Boolean, String?) -> Unit) {
    var expanded by remember(conflict.id) { mutableStateOf(false) }
    var selectedVersion by remember(conflict.id, conflict.currentRevision, conflict.resolved) { mutableStateOf<Boolean?>(null) }
    var historyExpanded by remember(conflict.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("sync-conflict-${conflict.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(
            onClick = { expanded = !expanded; selectedVersion = null },
            modifier = Modifier.fillMaxWidth().testTag("sync-conflict-toggle-${conflict.id}"),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    conflict.localTitle.ifBlank { conflict.remoteTitle }.ifBlank { stringResource(Res.string.sync_conflict_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        if (conflict.resolved) Res.string.sync_conflict_resolved else Res.string.sync_conflict_unresolved,
                        currentDateTimeSettings().format(conflict.createdAt),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (expanded) {
            if (conflict.fields.isNotEmpty()) {
                Text(stringResource(Res.string.sync_conflict_fields_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                conflict.fields.forEach { field ->
                    SyncConflictFieldEntry(conflict, field, enabled && !conflict.resolved) { useRemote ->
                        onRestore(useRemote, field.id)
                    }
                }
                TextButton(onClick = { historyExpanded = !historyExpanded },
                    modifier = Modifier.testTag("sync-conflict-history-${conflict.id}")) {
                    Text(stringResource(if (historyExpanded) Res.string.sync_conflict_history_hide else Res.string.sync_conflict_history_show))
                }
            }
            if (conflict.fields.isEmpty() || historyExpanded) {
                SyncConflictVersion(
                    false, conflict.localTitle, conflict.localContent, conflict.localDeleted,
                    enabled && !conflict.resolved, if (conflict.fields.isEmpty()) ({ selectedVersion = false }) else null,
                )
                SyncConflictVersion(
                    true, conflict.remoteTitle, conflict.remoteContent, conflict.remoteDeleted,
                    enabled && !conflict.resolved, if (conflict.fields.isEmpty()) ({ selectedVersion = true }) else null,
                )
            }
            selectedVersion?.let { useRemote ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("sync-conflict-confirm-${conflict.id}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(Res.string.sync_conflict_confirm_description), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { selectedVersion = null; onRestore(useRemote, null) }, enabled = enabled) {
                            Text(stringResource(Res.string.sync_conflict_confirm))
                        }
                        TextButton(onClick = { selectedVersion = null }) { Text(stringResource(Res.string.sync_conflict_cancel)) }
                    }
                }
            }
        }
    }
}

/**
 * 以可选择的完整文字展示保留版本，明确删除状态，不裁断正文或伪称当前本机来源。
 * @param second 是否为第二份保留版本。
 * @param title 该版本的可读对象标题。
 * @param content 该版本完整正文或定义内容。
 * @param deleted 是否为对象 tombstone。
 * @param enabled 是否允许选择该版本恢复。
 * @param onSelect 请求就地展示恢复确认；为空时仅供查看历史。
 * @param missing 此版本不存在该字段，与空内容区分。
 * @param block 完整 Block 的可读结构摘要；普通字段及完整对象历史为空。
 */
@Composable
internal fun SyncConflictVersion(
    second: Boolean,
    title: String,
    content: String,
    deleted: Boolean,
    enabled: Boolean,
    onSelect: (() -> Unit)?,
    missing: Boolean = false,
    block: WorkspaceSyncConflictBlock? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (second) Res.string.sync_conflict_version_two else Res.string.sync_conflict_version_one), style = MaterialTheme.typography.titleSmall)
        block?.let { SyncConflictBlockSummary(it) }
        if (deleted) Text(stringResource(if (block != null) Res.string.sync_conflict_block_deleted else Res.string.sync_conflict_deleted),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(if (missing) stringResource(Res.string.sync_conflict_field_missing)
                    else content.ifBlank { stringResource(Res.string.sync_conflict_empty_content) }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (onSelect != null) {
            OutlinedButton(onClick = onSelect, enabled = enabled) {
                Text(stringResource(if (second) Res.string.sync_conflict_use_two else Res.string.sync_conflict_use_one))
            }
        }
    }
}
