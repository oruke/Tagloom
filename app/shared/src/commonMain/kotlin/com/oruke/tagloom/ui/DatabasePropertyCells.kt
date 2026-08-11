package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
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
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                property.options.firstOrNull { option -> option.id == (value as? JsonPrimitive)?.contentOrNull }?.label
                    ?: stringResource(Res.string.database_group_empty),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.database_group_empty)) },
                onClick = { onCommit(null); open = false },
            )
            property.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
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
        TextButton(onClick = { open = true }) {
            Text(label.ifBlank { stringResource(Res.string.database_group_empty) })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.database_group_empty)) },
                onClick = { onCommit(null); open = false },
            )
            property.options.forEach { option ->
                val selected = option.id in selectedIds
                DropdownMenuItem(
                    text = { Text("${if (selected) "✓ " else ""}${option.label}") },
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
            TextButton(
                onClick = {
                    val next = when {
                        draft.isBlank() -> null
                        propertyType == PropertyTypes.NUMBER -> draft.toDoubleOrNull()?.let(::JsonPrimitive)
                        else -> JsonPrimitive(draft)
                    }
                    if (draft.isBlank() || next != null) onCommit(next)
                },
            ) {
                Text("✓")
            }
        }
    }
}
