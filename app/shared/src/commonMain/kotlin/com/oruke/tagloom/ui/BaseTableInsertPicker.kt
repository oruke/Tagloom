package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.workspace_action_cancel
import tagloom.app.shared.generated.resources.editor_action_insert
import tagloom.app.shared.generated.resources.editor_base_table_columns
import tagloom.app.shared.generated.resources.editor_base_table_insert_description
import tagloom.app.shared.generated.resources.editor_base_table_insert_dimensions
import tagloom.app.shared.generated.resources.editor_base_table_insert_hint
import tagloom.app.shared.generated.resources.editor_base_table_insert_title
import tagloom.app.shared.generated.resources.editor_base_table_rows

/**
 * 渲染 `/table` 的 8x8 预览与精确行列设置器。
 *
 * @param initialRows 默认行数。
 * @param initialColumns 默认列数。
 * @param onCreate 确认创建动作，依次传入行数和列数。
 * @param onCancel 关闭尺寸选择器。
 */
@Composable
internal fun BaseTableInsertPicker(
    initialRows: Int = 3,
    initialColumns: Int = 4,
    onCreate: (Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var rows by remember { mutableIntStateOf(initialRows) }
    var columns by remember { mutableIntStateOf(initialColumns) }
    Column(Modifier.width(TABLE_PICKER_WIDTH)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(Res.string.editor_base_table_insert_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(Res.string.editor_base_table_insert_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(34.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(stringResource(Res.string.editor_base_table_insert_dimensions, columns, rows), style = MaterialTheme.typography.bodySmall)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(216.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(PICKER_SIZE) { rowIndex ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(PICKER_SIZE) { columnIndex ->
                            TableSizeCell(
                                selected = rowIndex < rows && columnIndex < columns,
                                onHover = {
                                    rows = rowIndex + 1
                                    columns = columnIndex + 1
                                },
                                onClick = { onCreate(rowIndex + 1, columnIndex + 1) },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PickerStepper(stringResource(Res.string.editor_base_table_columns), columns, { columns = (columns - 1).coerceAtLeast(1) }, { columns = (columns + 1).coerceAtMost(20) })
            PickerStepper(stringResource(Res.string.editor_base_table_rows), rows, { rows = (rows - 1).coerceAtLeast(1) }, { rows = (rows + 1).coerceAtMost(20) })
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.editor_base_table_insert_hint),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PickerFooterButton(stringResource(Res.string.workspace_action_cancel), filled = false, onClick = onCancel)
            Spacer(Modifier.width(6.dp))
            PickerFooterButton(stringResource(Res.string.editor_action_insert), filled = true) { onCreate(rows, columns) }
        }
    }
}

/**
 * 渲染尺寸预览中的一个方格。
 *
 * @param selected 是否位于当前预览范围内。
 * @param onHover 悬停更新预览动作。
 * @param onClick 点击立即创建动作。
 */
@Composable
private fun TableSizeCell(
    selected: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            onHover()
        }
    }
    Box(
        Modifier.size(22.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
    )
}

/**
 * 渲染尺寸选择器的单个紧凑步进控件。
 *
 * @param label 维度名称。
 * @param value 当前数值。
 * @param onDecrease 减少动作。
 * @param onIncrease 增加动作。
 */
@Composable
private fun PickerStepper(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.widthIn(min = 94.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        PickerStepButton("−", onDecrease)
        Text(value.toString(), modifier = Modifier.width(20.dp), style = MaterialTheme.typography.labelMedium)
        PickerStepButton("+", onIncrease)
    }
}

/**
 * 渲染有细边框的行列步进按钮。
 *
 * @param label 加号或减号。
 * @param onClick 点击动作。
 */
@Composable
private fun PickerStepButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 渲染尺寸选择器底部的取消或确认按钮。
 *
 * @param label 按钮文本。
 * @param filled 是否使用主色填充。
 * @param onClick 点击动作。
 */
@Composable
private fun PickerFooterButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.heightIn(min = 28.dp).widthIn(min = 58.dp)
            .background(
                if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(3.dp),
            )
            .border(
                1.dp,
                if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(3.dp),
            )
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 预览网格的固定边长。 */
private const val PICKER_SIZE: Int = 8

/** Stitch 2048 图按 2x 桌面比例还原后的弹层宽度。 */
private val TABLE_PICKER_WIDTH = 384.dp
