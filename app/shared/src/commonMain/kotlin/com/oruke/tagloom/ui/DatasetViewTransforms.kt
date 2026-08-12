package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewField
import com.oruke.tagloom.core.model.ViewPresentationKinds

/**
 * 返回切换展示布局后的数据集视图。
 *
 * 看板首次启用时自动选择第一个单选属性，避免保存一个无法展示的空分组配置。
 *
 * @param kind 目标展示布局。
 * @param properties 当前数据集可用属性。
 * @return 保留查询与字段配置的新视图定义。
 */
internal fun ViewDefinition.withDatasetPresentationKind(
    kind: String,
    properties: List<PropertyDefinition>,
): ViewDefinition {
    require(kind in ViewPresentationKinds.EDITABLE) { "不支持的数据集展示布局" }
    val groupBy = if (kind == ViewPresentationKinds.KANBAN) {
        presentation.groupByPropertyId
            ?: properties.firstOrNull { property -> property.type == PropertyTypes.SELECT }?.id
    } else {
        presentation.groupByPropertyId
    }
    return copy(presentation = presentation.copy(kind = kind, groupByPropertyId = groupBy))
}

/**
 * 返回调整指定字段宽度后的数据集视图。
 *
 * @param field 目标稳定字段。
 * @param widthDp 新列宽，超出协议范围时会被限制。
 * @return 字段不存在时保持不变，否则返回更新后的视图。
 */
internal fun ViewDefinition.withDatasetFieldWidth(
    field: FieldRef,
    widthDp: Int,
): ViewDefinition = copy(
    presentation = presentation.copy(
        fields = presentation.fields.map { configured ->
            if (configured.field == field) configured.copy(width = widthDp.coerceIn(64, 720)) else configured
        },
    ),
)

/**
 * 返回把指定字段移动到目标下标后的数据集视图。
 *
 * 每次移动后重新生成紧凑稳定的 orderKey，确保字段面板连续操作得到确定顺序。
 *
 * @param field 目标稳定字段。
 * @param targetIndex 目标字段下标。
 * @return 字段不存在时保持不变，否则返回重新排序后的视图。
 */
private fun ViewDefinition.withDatasetFieldAt(
    field: FieldRef,
    targetIndex: Int,
): ViewDefinition {
    val currentIndex = presentation.fields.indexOfFirst { configured -> configured.field == field }
    if (currentIndex < 0) return this
    val reordered = presentation.fields.toMutableList()
    val moved = reordered.removeAt(currentIndex)
    reordered.add(targetIndex.coerceIn(0, reordered.size), moved)
    return copy(
        presentation = presentation.copy(
            fields = reordered.mapIndexed { index, configured ->
                configured.copy(orderKey = datasetFieldOrderKey(index))
            },
        ),
    )
}

/**
 * 返回把一个可见属性字段上移或下移后的数据集视图。
 *
 * 标题等系统字段不参加属性排序，隐藏字段也不会占用用户看到的移动步数；底层仍保留全部字段配置和列宽。
 *
 * @param field 需要移动的属性字段。
 * @param offset 相对可见属性顺序的位移，负数向上、正数向下。
 * @return 字段不存在、不可见或已经到达边界时保持不变，否则返回重新排序的视图。
 */
internal fun ViewDefinition.movedDatasetVisiblePropertyField(
    field: FieldRef,
    offset: Int,
): ViewDefinition {
    if (offset == 0 || field.kind != FieldRefKinds.PROPERTY) return this
    val visiblePropertyFields = presentation.fields.filter { configured ->
        configured.visible && configured.field.kind == FieldRefKinds.PROPERTY
    }
    val currentIndex = visiblePropertyFields.indexOfFirst { configured -> configured.field == field }
    if (currentIndex < 0) return this
    val targetIndex = (currentIndex + offset).coerceIn(0, visiblePropertyFields.lastIndex)
    if (targetIndex == currentIndex) return this
    val targetField = visiblePropertyFields[targetIndex].field
    val absoluteTargetIndex = presentation.fields.indexOfFirst { configured -> configured.field == targetField }
    return withDatasetFieldAt(field, absoluteTargetIndex)
}

/**
 * 返回切换指定字段可见性后的数据集视图。
 *
 * @param field 目标稳定字段。
 * @return 缺失字段会追加到末尾；隐藏字段重新显示时也移到末尾，方便用户继续用上移、下移确定位置。
 */
internal fun ViewDefinition.toggledDatasetField(field: FieldRef): ViewDefinition {
    val existing = presentation.fields.firstOrNull { configured -> configured.field == field }
    val fields = when {
        existing == null -> presentation.fields + ViewField(field, datasetFieldOrderKey(presentation.fields.size))
        existing.visible -> presentation.fields.map { configured ->
            if (configured.field == field) configured.copy(visible = false) else configured
        }
        else -> {
            val movedToEnd = presentation.fields.filterNot { configured -> configured.field == field } +
                existing.copy(visible = true)
            movedToEnd.mapIndexed { index, configured ->
                configured.copy(orderKey = datasetFieldOrderKey(index))
            }
        }
    }
    return copy(presentation = presentation.copy(fields = fields))
}

/**
 * 生成数据集字段使用的紧凑稳定顺序键。
 *
 * @param index 从零开始的字段下标。
 * @return 可按字典序排序的顺序键。
 */
private fun datasetFieldOrderKey(index: Int): String = "a${index.toString().padStart(6, '0')}"
