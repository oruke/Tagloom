package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.FilterExpr
import com.oruke.tagloom.core.model.FilterExprKinds
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.QueryPredicateOperators
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.ViewDefinition
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 渲染与搜索分离的结构化数据集过滤入口。
 *
 * 桌面端使用锚定弹层，紧凑窗口使用底部操作面板；两端都编辑同一份 View QuerySpec。
 *
 * @param controller 数据集目录与写入控制器。
 * @param view 当前视图定义。
 * @param compact 是否使用触控优先的紧凑布局。
 */
@Composable
internal fun DatasetFilterButton(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    compact: Boolean,
) {
    var open by remember(view.id) { mutableStateOf(false) }
    val active = view.query.filter.kind != FilterExprKinds.TRUE
    Box {
        DatasetToolbarAction(
            label = if (active) "≡  筛选  1" else "≡  筛选",
            active = active,
            compact = compact,
            onClick = { open = true },
        )
        if (!compact) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.widthIn(min = 360.dp, max = 420.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                DatasetFilterEditor(controller, view) { open = false }
            }
        }
    }
    if (compact && open) {
        Dialog(onDismissRequest = { open = false }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    DatasetFilterEditor(controller, view) { open = false }
                }
            }
        }
    }
}

/**
 * 渲染字段、运算符和值组成的单条件过滤编辑器。
 *
 * @param controller 数据集目录与写入控制器。
 * @param view 当前视图定义。
 * @param onClose 保存、清除或取消后的关闭动作。
 */
@Composable
private fun DatasetFilterEditor(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    onClose: () -> Unit,
) {
    val properties = controller.propertyDefinitions(view.databaseId)
    val choices = remember(view.databaseId, properties) { datasetFilterFields(properties) }
    val original = view.query.filter
    val initialField = original.field ?: FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE)
    var field by remember(view.id, original) { mutableStateOf(initialField) }
    var operator by remember(view.id, original) {
        mutableStateOf(original.operator ?: defaultDatasetFilterOperator(field, properties))
    }
    var value by remember(view.id, original) {
        mutableStateOf(original.value?.toString()?.trim('"') ?: original.text.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val selectedProperty = field.propertyId?.let { propertyId ->
        properties.firstOrNull { property -> property.id == propertyId }
    }
    val operators = datasetFilterOperators(selectedProperty)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("过滤条件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("仅当前视图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DatasetChoiceButton(
            label = choices.firstOrNull { choice -> choice.field == field }?.label ?: "标题",
            choices = choices.map { choice -> choice.field to choice.label },
            onSelect = { selected ->
                field = selected
                operator = defaultDatasetFilterOperator(selected, properties)
                value = ""
            },
        )
        DatasetChoiceButton(
            label = operators.firstOrNull { choice -> choice.first == operator }?.second ?: "包含",
            choices = operators,
            onSelect = { selected -> operator = selected },
        )
        if (operator !in setOf(QueryPredicateOperators.IS_EMPTY, QueryPredicateOperators.IS_NOT_EMPTY)) {
            if (selectedProperty?.type in setOf(PropertyTypes.SELECT, PropertyTypes.MULTI_SELECT)) {
                DatasetChoiceButton(
                    label = selectedProperty?.options.orEmpty().firstOrNull { option -> option.id == value }?.label ?: "选择选项",
                    choices = selectedProperty?.options.orEmpty().map { option -> option.id to option.label },
                    onSelect = { selected -> value = selected },
                )
            } else {
                DatasetFilterValueField(value = value, onValueChange = { updated -> value = updated })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (original.kind != FilterExprKinds.TRUE) {
                TextButton(
                    onClick = {
                        scope.launch { controller.updateView(view.id) { current -> current.withDatasetFilter(FilterExpr()) } }
                        onClose()
                    },
                ) { Text("清除") }
            }
            TextButton(onClick = onClose) { Text("取消") }
            TextButton(
                enabled = operator in setOf(QueryPredicateOperators.IS_EMPTY, QueryPredicateOperators.IS_NOT_EMPTY) || value.isNotBlank(),
                onClick = {
                    val expr = FilterExpr(
                        kind = FilterExprKinds.PREDICATE,
                        field = field,
                        operator = operator,
                        value = value.takeIf { operator !in setOf(QueryPredicateOperators.IS_EMPTY, QueryPredicateOperators.IS_NOT_EMPTY) }
                            ?.let(::JsonPrimitive),
                        values = if (operator in setOf(QueryPredicateOperators.IN, QueryPredicateOperators.CONTAINS_ANY)) {
                            listOf(JsonPrimitive(value))
                        } else {
                            emptyList()
                        },
                    )
                    scope.launch { controller.updateView(view.id) { current -> current.withDatasetFilter(expr) } }
                    onClose()
                },
            ) { Text("应用") }
        }
    }
}

/**
 * 渲染结构化过滤器中的文本值输入。
 *
 * @param value 当前文本。
 * @param onValueChange 文本变化动作。
 */
@Composable
private fun DatasetFilterValueField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("输入值", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                inner()
            }
        },
    )
}

/**
 * 渲染过滤器中的下拉选择控件。
 *
 * @param label 当前选项名称。
 * @param choices 可选值与名称。
 * @param onSelect 选择动作。
 */
@Composable
private fun <T> DatasetChoiceButton(
    label: String,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (value, choiceLabel) ->
                DropdownMenuItem(
                    text = { Text(choiceLabel) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 渲染数据集工具条动作，紧凑布局使用符合触控尺寸的命中区域。
 *
 * @param label 动作名称。
 * @param active 是否存在已应用状态。
 * @param compact 是否使用触控优先尺寸。
 * @param onClick 点击动作。
 */
@Composable
internal fun DatasetToolbarAction(
    label: String,
    active: Boolean = false,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        modifier = Modifier.heightIn(min = if (compact) 40.dp else 34.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 描述结构化过滤器中的一个字段选项。 */
private data class DatasetFilterField(
    /** 稳定字段引用。 */
    val field: FieldRef,
    /** 用户可见名称。 */
    val label: String,
)

/**
 * 返回过滤器可选择的系统字段与属性字段。
 *
 * @param properties 数据集可用属性。
 * @return 标题位于首位的字段列表。
 */
private fun datasetFilterFields(properties: List<PropertyDefinition>): List<DatasetFilterField> = listOf(
    DatasetFilterField(FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE), "标题"),
) + properties.map { property -> DatasetFilterField(FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id), property.name) }

/**
 * 返回字段首次选择时使用的过滤运算符。
 *
 * @param field 稳定字段引用。
 * @param properties 数据集可用属性。
 * @return 与字段类型匹配的默认运算符。
 */
private fun defaultDatasetFilterOperator(
    field: FieldRef,
    properties: List<PropertyDefinition>,
): String = when (properties.firstOrNull { property -> property.id == field.propertyId }?.type) {
    PropertyTypes.SELECT -> QueryPredicateOperators.EQ
    PropertyTypes.MULTI_SELECT -> QueryPredicateOperators.CONTAINS_ANY
    else -> QueryPredicateOperators.CONTAINS
}

/**
 * 返回指定属性可用的首版过滤运算符。
 *
 * @param property 可空属性；为空表示标题字段。
 * @return 稳定运算符与本地显示名称。
 */
private fun datasetFilterOperators(property: PropertyDefinition?): List<Pair<String, String>> = when (property?.type) {
    PropertyTypes.SELECT -> listOf(
        QueryPredicateOperators.EQ to "等于",
        QueryPredicateOperators.NE to "不等于",
        QueryPredicateOperators.IS_EMPTY to "为空",
        QueryPredicateOperators.IS_NOT_EMPTY to "不为空",
    )
    PropertyTypes.MULTI_SELECT -> listOf(
        QueryPredicateOperators.CONTAINS_ANY to "包含任一",
        QueryPredicateOperators.IS_EMPTY to "为空",
        QueryPredicateOperators.IS_NOT_EMPTY to "不为空",
    )
    PropertyTypes.NUMBER,
    PropertyTypes.DATE,
    PropertyTypes.DATETIME,
    -> listOf(
        QueryPredicateOperators.EQ to "等于",
        QueryPredicateOperators.GT to "晚于 / 大于",
        QueryPredicateOperators.LT to "早于 / 小于",
        QueryPredicateOperators.IS_EMPTY to "为空",
    )
    else -> listOf(
        QueryPredicateOperators.CONTAINS to "包含",
        QueryPredicateOperators.EQ to "等于",
        QueryPredicateOperators.IS_EMPTY to "为空",
        QueryPredicateOperators.IS_NOT_EMPTY to "不为空",
    )
}

/**
 * 返回替换过滤条件后的数据集视图。
 *
 * @param filter 新的类型化过滤 AST。
 * @return 查询其他配置保持不变的新视图。
 */
private fun ViewDefinition.withDatasetFilter(filter: FilterExpr): ViewDefinition =
    copy(query = query.copy(filter = filter))
