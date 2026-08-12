package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染单选或多选属性的已选 chip、搜索菜单与可选自由创建入口。
 *
 * @param state 当前编辑器状态。
 * @param document 当前 Note 文档。
 * @param definition select 类属性定义。
 * @param effectiveValue 已解析默认值后的当前展示值。
 * @param editable 当前类型约束是否允许编辑。
 * @param multiple 是否允许同时选择多个选项。
 * @param onCreateOption 请求先持久化再选择一个自由输入选项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetadataSelectProperty(
    state: TagloomEditorState,
    document: EditorDocument,
    definition: PropertyDefinition,
    effectiveValue: JsonElement?,
    editable: Boolean,
    multiple: Boolean,
    onCreateOption: (PropertyId, String) -> Unit,
) {
    var menuOpen by remember(document.id, definition.id) { mutableStateOf(false) }
    var query by remember(document.id, definition.id) { mutableStateOf("") }
    val selectedIds = if (multiple) {
        effectiveValue.stringListContent().toSet()
    } else {
        effectiveValue.stringContent().takeIf(String::isNotEmpty)?.let(::setOf).orEmpty()
    }
    val visibleOptions = definition.options.filter { option ->
        query.isBlank() || option.label.contains(query, ignoreCase = true)
    }
    Box {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            selectedIds.forEach { selectedId ->
                val option = definition.options.firstOrNull { current -> current.id == selectedId }
                PropertyOptionChip(
                    label = option?.label ?: selectedId.take(12),
                    colorToken = option?.colorToken,
                    enabled = editable,
                    onClick = { menuOpen = true },
                )
            }
            if (selectedIds.isEmpty() || multiple) {
                MetadataInlineAction(
                    label = if (selectedIds.isEmpty()) {
                        stringResource(Res.string.editor_property_select)
                    } else {
                        "+"
                    },
                    enabled = editable,
                ) { menuOpen = true }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                query = ""
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PopoverSearchField(
                value = query,
                placeholder = stringResource(Res.string.editor_property_search_or_enter_option),
                requestFocus = menuOpen,
                onValueChange = { value -> query = value },
            )
            visibleOptions.forEach { option ->
                val selected = option.id in selectedIds
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Text(if (selected) "✓" else "") },
                    onClick = {
                        if (multiple) {
                            val next = if (selected) selectedIds - option.id else selectedIds + option.id
                            state.propertyEditor.updateMultiSelect(definition.id, next.sorted())
                        } else {
                            state.propertyEditor.updateSelect(definition.id, option.id.takeUnless { selected })
                            menuOpen = false
                            query = ""
                        }
                    },
                )
            }
            val normalized = query.trim()
            val canCreate = definition.optionInputMode == PropertyOptionInputMode.FREE &&
                normalized.isNotEmpty() && definition.options.none { option ->
                    option.label.equals(normalized, ignoreCase = true)
                }
            if (canCreate) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.editor_property_create_option, normalized)) },
                    leadingIcon = { Text("+") },
                    onClick = {
                        menuOpen = false
                        query = ""
                        onCreateOption(definition.id, normalized)
                    },
                )
            }
            if (visibleOptions.isEmpty() && !canCreate) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (definition.optionInputMode == PropertyOptionInputMode.FIXED && definition.options.isEmpty()) {
                                stringResource(Res.string.editor_property_fixed_options_required)
                            } else {
                                stringResource(Res.string.editor_property_no_matching_options)
                            },
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

/**
 * 渲染一个已选属性选项；点击打开同一属性的选择菜单。
 *
 * @param label 选项显示名称。
 * @param colorToken 选项语义色 token。
 * @param enabled 是否允许打开选择菜单。
 * @param onClick 打开菜单动作。
 */
@Composable
private fun PropertyOptionChip(
    label: String,
    colorToken: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = optionChipColors(colorToken)
    Surface(
        modifier = Modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        color = colors.first,
        contentColor = colors.second,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 把稳定选项色 token 映射为主题内的低面积语义色。
 *
 * @param colorToken 选项定义中的语义 token。
 * @return chip 背景色与前景色。
 */
@Composable
internal fun optionChipColors(colorToken: String?): Pair<Color, Color> = when (colorToken) {
    "priority-high" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    "status-active", "status-success" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 安全读取多选属性中的稳定选项 ID。
 *
 * @return 忽略非字符串成员后的选项 ID。
 */
private fun JsonElement?.stringListContent(): List<String> = runCatching {
    this?.jsonArray?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }.orEmpty()
}.getOrDefault(emptyList())

/**
 * 安全读取单选属性中的稳定选项 ID。
 *
 * @return 字符串选项 ID；类型不匹配时为空。
 */
private fun JsonElement?.stringContent(): String = runCatching {
    this?.jsonPrimitive?.contentOrNull.orEmpty()
}.getOrDefault("")
