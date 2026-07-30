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
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

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
        sourceTag != null -> parts += stringResource(Res.string.editor_property_from_type, sourceTag.name)
        document.note.manualPropertyBindings.any { manual -> manual.propertyId == property.propertyId } -> {
            parts += stringResource(Res.string.editor_property_current_note)
        }
    }
    when (property.valueSource) {
        EffectivePropertyValueSource.NOTE_VALUE -> parts += storedValueSourceLabel(state, document, property)
        EffectivePropertyValueSource.DYNAMIC_DEFAULT -> parts += stringResource(Res.string.editor_property_dynamic_default)
        EffectivePropertyValueSource.SNAPSHOT_PENDING -> {
            parts += stringResource(Res.string.editor_property_snapshot_pending)
        }
        EffectivePropertyValueSource.NONE -> Unit
    }
    if (binding?.required == true) {
        parts += if (property.value == null) {
            stringResource(Res.string.editor_property_required_missing)
        } else {
            stringResource(Res.string.editor_property_required)
        }
    }
    if (binding?.readonly == true) parts += stringResource(Res.string.editor_tag_readonly)
    if (binding?.inheritable == false) parts += stringResource(Res.string.editor_property_current_type_only)
    if (property.conflicted) parts += stringResource(Res.string.editor_property_constraints_conflict)
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
@Composable
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
            tagName?.let { name ->
                stringResource(Res.string.editor_property_snapshot_from_type, name)
            } ?: stringResource(Res.string.editor_property_type_snapshot)
        }
        PropertyValueSource.SYSTEM -> stringResource(Res.string.editor_property_system_value)
        PropertyValueSource.USER, null -> if (stored?.overridden == true) {
            stringResource(Res.string.editor_property_note_override)
        } else {
            stringResource(Res.string.editor_property_note_value)
        }
    }
}
