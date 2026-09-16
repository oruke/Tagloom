package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.editor.TagPropertyBindingDraft
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.buildTagDefinitionDraft
import com.oruke.tagloom.editor.toConfirmedBinding
import com.oruke.tagloom.editor.toEditorDraft
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染超级标签的多父继承选择器。
 *
 * @param state 当前 schema 状态，用于把 ID 映射为名称。
 * @param parentTagIds 当前显式父标签。
 * @param candidates 可选择父标签。
 * @param menuOpen 菜单是否展开。
 * @param editable 是否允许修改父超级标签。
 * @param onMenuOpenChange 切换菜单展开状态。
 * @param onToggle 增删一个父标签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagParentEditor(
    state: TagloomEditorState,
    parentTagIds: List<TagId>,
    candidates: List<TagDefinition>,
    menuOpen: Boolean,
    editable: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onToggle: (TagId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.editor_tag_inherits_from), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (parentTagIds.isEmpty()) {
                SchemaPill(
                    if (editable) {
                        stringResource(Res.string.editor_tag_default_parent)
                    } else {
                        stringResource(Res.string.editor_tag_root_type)
                    },
                    emphasized = false,
                    onClick = {},
                )
            } else {
                parentTagIds.forEach { parentId ->
                    val name = state.tagDefinitions.firstOrNull { definition -> definition.id == parentId }?.name
                        ?: parentId.value
                    SchemaPill("$name  ×", emphasized = true) { onToggle(parentId) }
                }
            }
            if (editable) Box {
                TextButton(onClick = { onMenuOpenChange(true) }) {
                    Text(stringResource(Res.string.editor_tag_add_parent))
                }
                TagloomDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    candidates.forEach { definition ->
                        val selected = definition.id in parentTagIds
                        TagloomMenuItem(
                            text = definition.name,
                            leadingIcon = if (selected) {
                                {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null,
                            selected = selected,
                            onClick = { onToggle(definition.id) },
                        )
                    }
                }
            }
        }
        Text(
            if (editable) {
                stringResource(Res.string.editor_tag_inheritance_notice)
            } else {
                stringResource(Res.string.editor_tag_root_inheritance_notice)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 以不可交互列表展示 Note 根超级标签绑定的基础属性。
 *
 * @param bindings 根超级标签当前属性绑定。
 * @param definitions 工作区属性定义目录。
 */
@Composable
internal fun ReadOnlyTypeProperties(
    bindings: List<TagPropertyBindingDraft>,
    definitions: List<PropertyDefinition>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bindings.forEach { binding ->
            val definition = definitions.firstOrNull { property -> property.id == binding.propertyId }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(definition?.name ?: binding.propertyId.value, style = MaterialTheme.typography.bodyMedium)
                Text(
                    definition?.type?.localizedDisplayName()
                        ?: stringResource(Res.string.editor_common_missing_definition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 为标签定义中的既有绑定建立 UI 草稿，并保留损坏引用供用户显式处理。
 *
 * @param bindings 既有标签绑定。
 * @param definitions 当前工作区属性目录。
 * @return 与原绑定顺序一致的草稿。
 */
internal fun createBindingDrafts(
    bindings: List<TagPropertyBinding>,
    definitions: List<PropertyDefinition>,
): List<TagPropertyBindingDraft> = bindings.map { binding ->
    val definition = definitions.firstOrNull { property -> property.id == binding.propertyId }
    if (definition != null) {
        binding.toEditorDraft(definition)
    } else {
        TagPropertyBindingDraft(
            propertyId = binding.propertyId,
            required = binding.required,
            readonly = binding.readonly,
            inheritable = binding.inheritable,
            defaultPolicy = binding.defaultPolicy,
            defaultValueText = binding.defaultValue?.toString().orEmpty(),
            order = binding.order,
            styleOverride = binding.styleOverride,
        )
    }
}
