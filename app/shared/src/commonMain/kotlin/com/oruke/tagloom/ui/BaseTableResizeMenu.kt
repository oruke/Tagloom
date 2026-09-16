package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.baseTableResizeWouldHideData
import com.oruke.tagloom.editor.resizeBaseTable
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_action_apply
import tagloom.app.shared.generated.resources.editor_base_table_columns
import tagloom.app.shared.generated.resources.editor_base_table_dimensions
import tagloom.app.shared.generated.resources.editor_base_table_resize
import tagloom.app.shared.generated.resources.editor_base_table_rows
import tagloom.app.shared.generated.resources.editor_base_table_shrink_notice

/**
 * 渲染右下角批量尺寸菜单。
 *
 * @param state 编辑器状态。
 * @param block 表格 Block。
 * @param currentRows 当前活动行数。
 * @param currentColumns 当前活动列数。
 * @param enabled 当前 schema 是否可写。
 */
@Composable
internal fun BaseTableResizeMenu(
    state: TagloomEditorState,
    block: Block,
    currentRows: Int,
    currentColumns: Int,
    enabled: Boolean,
) {
    var menuOpen by remember(block.id) { mutableStateOf(false) }
    var rows by remember(block.id, menuOpen) { mutableIntStateOf(currentRows) }
    var columns by remember(block.id, menuOpen) { mutableIntStateOf(currentColumns) }
    Box {
        TableIconAction("↘", stringResource(Res.string.editor_base_table_resize), { menuOpen = true }, enabled)
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(Res.string.editor_base_table_dimensions), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                DimensionStepper(stringResource(Res.string.editor_base_table_rows), rows, { rows = (rows - 1).coerceAtLeast(1) }, { rows = (rows + 1).coerceAtMost(20) })
                DimensionStepper(stringResource(Res.string.editor_base_table_columns), columns, { columns = (columns - 1).coerceAtLeast(1) }, { columns = (columns + 1).coerceAtMost(20) })
                if (state.baseTableResizeWouldHideData(block.id, rows, columns)) {
                    Text(
                        stringResource(Res.string.editor_base_table_shrink_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        stringResource(Res.string.editor_action_apply),
                        modifier = Modifier.clickable {
                            state.resizeBaseTable(block.id, rows, columns)
                            menuOpen = false
                        }.padding(6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 渲染行数或列数的紧凑步进器。
 *
 * @param label 维度名称。
 * @param value 当前值。
 * @param onDecrease 减少动作。
 * @param onIncrease 增加动作。
 */
@Composable
private fun DimensionStepper(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("−", modifier = Modifier.clickable(onClick = onDecrease).padding(4.dp))
        Text(value.toString(), modifier = Modifier.width(28.dp), style = MaterialTheme.typography.bodyMedium)
        Text("+", modifier = Modifier.clickable(onClick = onIncrease).padding(4.dp))
    }
}
