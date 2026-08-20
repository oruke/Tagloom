package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_group_empty

/**
 * 渲染固定选项单选单元格。
 *
 * @param property 单选属性定义。
 * @param value 当前选项 ID。
 * @param onCommit 提交新属性值；空值表示清除。
 */
@Composable
internal fun DatabaseSelectCell(
    property: PropertyDefinition,
    value: JsonElement?,
    onCommit: (JsonElement?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedOption = property.options.firstOrNull { option -> option.id == (value as? JsonPrimitive)?.contentOrNull }
    val colors = optionChipColors(selectedOption?.colorToken)
    Box {
        Box(
            modifier = Modifier.background(colors.first, RoundedCornerShape(4.dp)).clickable { open = true }
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                selectedOption?.label ?: stringResource(Res.string.database_group_empty),
                style = MaterialTheme.typography.labelMedium,
                color = colors.second,
            )
        }
        TagloomDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            TagloomMenuItem(
                text = stringResource(Res.string.database_group_empty),
                selected = selectedOption == null,
                onClick = { onCommit(null); open = false },
            )
            if (property.options.isNotEmpty()) {
                TagloomMenuDivider()
            }
            property.options.forEach { option ->
                val selected = option.id == (value as? JsonPrimitive)?.contentOrNull
                TagloomMenuItem(
                    text = option.label,
                    selected = selected,
                    trailingIcon = if (selected) {
                        { Text("✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = { onCommit(JsonPrimitive(option.id)); open = false },
                )
            }
        }
    }
}

/**
 * 渲染固定选项多选单元格，并按属性选项顺序保存稳定 JSON 数组。
 *
 * @param property 多选属性定义。
 * @param value 当前选项 ID 数组。
 * @param onCommit 提交新数组；空集合表示清除。
 */
@Composable
internal fun DatabaseMultiSelectCell(
    property: PropertyDefinition,
    value: JsonElement?,
    onCommit: (JsonElement?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedIds = (value as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()
    val label = property.options.filter { option -> option.id in selectedIds }.joinToString(", ") { option -> option.label }
    Box {
        Box(
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .clickable { open = true }.padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.ifBlank { stringResource(Res.string.database_group_empty) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TagloomDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            TagloomMenuItem(
                text = stringResource(Res.string.database_group_empty),
                selected = selectedIds.isEmpty(),
                onClick = { onCommit(null); open = false },
            )
            if (property.options.isNotEmpty()) {
                TagloomMenuDivider()
            }
            property.options.forEach { option ->
                val selected = option.id in selectedIds
                TagloomMenuItem(
                    text = option.label,
                    selected = selected,
                    leadingIcon = {
                        Text(
                            if (selected) "✓" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        val nextIds = if (selected) selectedIds - option.id else selectedIds + option.id
                        val nextValue = property.options
                            .filter { candidate -> candidate.id in nextIds }
                            .map { candidate -> JsonPrimitive(candidate.id) }
                            .let(::JsonArray)
                            .takeUnless(JsonArray::isEmpty)
                        onCommit(nextValue)
                    },
                )
            }
        }
    }
}

/**
 * 渲染带显式确认的文本、数字、日期或日期时间单元格，避免每个按键都创建加密事务。
 *
 * @param value 当前无 Locale 文本值。
 * @param propertyType 属性类型；标题列为空。
 * @param onCommit 提交新 JSON 值；空文本表示清除。
 */
@Composable
internal fun DatabaseTextCell(
    value: String,
    propertyType: String?,
    onCommit: (JsonElement?) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = draft,
            onValueChange = { incoming -> draft = incoming },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (draft != value) {
            Box(
                modifier = Modifier.width(24.dp).height(24.dp).clickable {
                    val next = when {
                        draft.isBlank() -> null
                        propertyType == PropertyTypes.NUMBER -> draft.toDoubleOrNull()?.let(::JsonPrimitive)
                        else -> JsonPrimitive(draft)
                    }
                    if (draft.isBlank() || next != null) onCommit(next)
                },
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
