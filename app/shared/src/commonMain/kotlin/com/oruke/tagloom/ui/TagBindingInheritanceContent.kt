package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.TagBindingEditorProperties
import com.oruke.tagloom.editor.TagBindingEditorRow
import com.oruke.tagloom.editor.TagPropertyBindingDraft
import com.oruke.tagloom.editor.toEditorDraft
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 说明继承图损坏、缺失定义或未解决冲突及其修复方式，避免禁用保存却没有原因。
 * @param properties 当前表单解析后的属性与错误。
 */
@Composable
internal fun SupertagInheritanceNotice(properties: TagBindingEditorProperties) {
    val message = when {
        properties.inheritanceErrors.isNotEmpty() -> Res.string.supertag_inheritance_invalid
        properties.rows.any { it.definition == null } -> Res.string.supertag_inheritance_missing_property
        properties.rows.any { it.conflicted } -> Res.string.supertag_inheritance_conflicts
        else -> null
    }
    if (message != null) Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("supertag-inheritance-notice").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * 在折叠标题中持续显示真实来源和覆盖状态。
 * @param row 当前属性、父级来源及本级覆盖标识。
 */
@Composable
internal fun TagBindingOrigin(row: TagBindingEditorRow) {
    val text = if (row.inheritedProperty == null) {
        stringResource(Res.string.supertag_property_local)
    } else {
        stringResource(
            when {
                row.conflicted -> Res.string.supertag_property_conflict_from
                row.declaredLocally -> Res.string.supertag_property_override_from
                else -> Res.string.supertag_property_inherited_from
            },
            row.sources.joinToString(", ") { it.name },
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (row.conflicted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 要求用户明确选择父级作为本级覆盖的起点，不把领域解析器的临时候选冒充已确认默认值。
 * @param row 含全部竞争来源的冲突属性。
 * @param enabled 保存期间禁止确认冲突选项。
 * @param onChange 复制选中父级的完整绑定作为可继续编辑的本级草稿。
 */
@Composable
internal fun TagBindingConflictChoices(
    row: TagBindingEditorRow,
    enabled: Boolean,
    onChange: (TagPropertyBindingDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.supertag_inheritance_choose_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.sources.forEach { source ->
            val binding = source.propertyBindings.single { it.propertyId == row.draft.propertyId }
            OutlinedButton(
                onClick = { onChange(binding.toEditorDraft(row.definition)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
                    .testTag("supertag-inherit-${row.draft.propertyId.value}-${source.id.value}"),
            ) {
                Text(stringResource(Res.string.supertag_inheritance_use_source, source.name))
            }
        }
    }
}
