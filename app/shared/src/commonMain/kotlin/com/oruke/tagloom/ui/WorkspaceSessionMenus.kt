package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.ObjectId

/**
 * 渲染页头搜索图标触发的已确认内容搜索浮层。
 *
 * @param controller 当前工作区搜索状态。
 * @param expanded 是否展开。
 * @param onDismiss 关闭动作。
 * @param onSearch 执行搜索动作。
 * @param onSaveQuery 打开保存查询对话框。
 * @param modifier 浮层锚点位置。
 */
@Composable
internal fun WorkspaceSearchPopover(
    controller: WorkspaceUiController,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onSaveQuery: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.width(360.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                ) {
                    BasicTextField(
                        value = controller.searchText,
                        onValueChange = { value -> controller.searchText = value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .onPreviewKeyEvent { event ->
                                if (
                                    event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    controller.searchText.isNotBlank()
                                ) {
                                    onSearch()
                                    true
                                } else {
                                    false
                                }
                            },
                        enabled = !controller.busy,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (controller.searchText.isBlank()) {
                                    Text(
                                        "搜索已确认内容 · Enter",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                controller.searchHits.take(8).forEach { hit ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        hit.snippet,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onDismiss()
                                if (controller.editorState.documents.any { document -> document.id == hit.noteId }) {
                                    controller.editorState.selectDocument(hit.noteId)
                                }
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (controller.searchHits.isEmpty()) "仅搜索已确认快照" else "${controller.searchHits.size} 个结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSaveQuery, enabled = controller.searchText.isNotBlank()) {
                        Text("保存查询")
                    }
                }
            }
        }
    }
}

/**
 * 渲染页头更多图标触发的工作区维护菜单。
 *
 * @param controller 当前工作区和保存查询状态。
 * @param expanded 是否展开。
 * @param onDismiss 关闭动作。
 * @param actions 菜单业务动作。
 * @param modifier 浮层锚点位置。
 */
@Composable
internal fun WorkspaceMaintenanceMenu(
    controller: WorkspaceUiController,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: WorkspaceMaintenanceActions,
    modifier: Modifier,
) {
    /**
     * 关闭菜单后执行动作，避免状态切换期间残留浮层。
     *
     * @param action 待执行动作。
     */
    fun invokeAndDismiss(action: () -> Unit) {
        onDismiss()
        action()
    }

    Box(modifier) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(text = { Text("立即保存    Ctrl+S") }, onClick = {
                invokeAndDismiss(actions.onSave)
            })
            DropdownMenuItem(
                text = { Text("保存当前搜索") },
                enabled = controller.searchText.isNotBlank(),
                onClick = { invokeAndDismiss(actions.onSaveQuery) },
            )
            controller.savedQueries.take(6).forEach { saved ->
                DropdownMenuItem(text = { Text("运行：${saved.query.name}") }, onClick = {
                    invokeAndDismiss { actions.onRunSavedQuery(saved.id) }
                })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("创建密文备份") }, onClick = {
                invokeAndDismiss(actions.onBackup)
            })
            DropdownMenuItem(text = { Text("更换密码") }, onClick = {
                invokeAndDismiss(actions.onChangePassword)
            })
            DropdownMenuItem(text = { Text("轮换主密钥") }, onClick = {
                invokeAndDismiss(actions.onRotateMasterKey)
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("删除当前笔记") }, onClick = {
                invokeAndDismiss(actions.onDelete)
            })
            DropdownMenuItem(text = { Text("锁定工作区") }, onClick = {
                invokeAndDismiss(actions.onLock)
            })
        }
    }
}

/**
 * 汇总工作区维护菜单的业务动作，避免菜单拥有外部状态。
 */
internal data class WorkspaceMaintenanceActions(
    /** 立即确认当前笔记。 */
    val onSave: () -> Unit,
    /** 保存当前搜索条件。 */
    val onSaveQuery: () -> Unit,
    /** 运行一个已确认保存查询。 */
    val onRunSavedQuery: (ObjectId) -> Unit,
    /** 创建密文备份。 */
    val onBackup: () -> Unit,
    /** 打开更换密码流程。 */
    val onChangePassword: () -> Unit,
    /** 打开主密钥轮换流程。 */
    val onRotateMasterKey: () -> Unit,
    /** 打开当前笔记删除确认。 */
    val onDelete: () -> Unit,
    /** 锁定当前工作区。 */
    val onLock: () -> Unit,
)
