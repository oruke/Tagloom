package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.domain.property.EffectiveProperty
import com.oruke.tagloom.core.domain.property.EffectivePropertyValueSource
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState

/**
 * 在属性值下方展示对象类型来源、默认策略、笔记覆盖和约束状态。
 *
 * @param state 当前工作区 schema 状态。
 * @param document 当前笔记。
 * @param property 已解析有效属性。
 */
@Composable
internal fun PropertyProvenance(
    state: TagloomEditorState,
    document: EditorDocument,
    property: EffectiveProperty,
) {
    val binding = property.binding
    val parts = mutableListOf<String>()
    val sourceTag = property.sourceTagId?.let { tagId ->
        state.tagDefinitions.firstOrNull { definition -> definition.id == tagId }
    }
    when {
        sourceTag != null -> parts += "来自类型 ${sourceTag.name}"
        document.note.manualPropertyBindings.any { manual -> manual.propertyId == property.propertyId } -> parts += "仅当前笔记"
    }
    when (property.valueSource) {
        EffectivePropertyValueSource.NOTE_VALUE -> parts += storedValueSourceLabel(state, document, property)
        EffectivePropertyValueSource.DYNAMIC_DEFAULT -> parts += "动态默认值"
        EffectivePropertyValueSource.SNAPSHOT_PENDING -> parts += "等待写入快照"
        EffectivePropertyValueSource.NONE -> Unit
    }
    if (binding?.required == true) parts += if (property.value == null) "必填未填写" else "必填"
    if (binding?.readonly == true) parts += "只读"
    if (binding?.inheritable == false) parts += "仅当前类型"
    if (property.conflicted) parts += "约束冲突"
    if (parts.isEmpty()) return

    Text(
        text = parts.distinct().joinToString(" · "),
        modifier = Modifier.padding(top = 1.dp, bottom = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (property.conflicted || (binding?.required == true && property.value == null)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * 解释笔记快照中实际属性值的来源。
 *
 * @param state 当前工作区 schema 状态。
 * @param document 当前笔记。
 * @param property 已解析属性。
 * @return 笔记覆盖、类型快照或系统值说明。
 */
private fun storedValueSourceLabel(
    state: TagloomEditorState,
    document: EditorDocument,
    property: EffectiveProperty,
): String {
    val stored = document.note.propertyValues[property.propertyId]
    return when (stored?.source) {
        PropertyValueSource.TAG_SNAPSHOT -> {
            val tagName = stored.sourceTagId?.let { tagId ->
                state.tagDefinitions.firstOrNull { definition -> definition.id == tagId }?.name
            }
            tagName?.let { name -> "快照自类型 $name" } ?: "类型快照"
        }
        PropertyValueSource.SYSTEM -> "系统值"
        PropertyValueSource.USER, null -> if (stored?.overridden == true) "笔记覆盖" else "笔记值"
    }
}
