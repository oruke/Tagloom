package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.editor.TagPropertyBindingDraft
import com.oruke.tagloom.editor.TagBindingEditorRow
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染继承或本级属性的约束和默认策略，名称与类型始终使用只读文本。
 *
 * @param row 当前属性、继承来源与本级覆盖状态。
 * @param enabled 保存期间禁止继续修改绑定。
 * @param onChange 更新绑定草稿。
 * @param onRemove 移除本级绑定；继承项由此恢复父级设置。
 */
@Composable
internal fun TagBindingDraftEditor(
    row: TagBindingEditorRow,
    enabled: Boolean,
    onChange: (TagPropertyBindingDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val draft = row.draft
    val definition = row.definition
    var expanded by remember(draft.propertyId) { mutableStateOf(false) }
    val headerInteraction = remember(draft.propertyId) { MutableInteractionSource() }
    val hovered by headerInteraction.collectIsHoveredAsState()
    val focused by headerInteraction.collectIsFocusedAsState()
    val pressed by headerInteraction.collectIsPressedAsState()
    val headerColor = when {
        pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    val expansionState = stringResource(
        if (expanded) Res.string.supertag_property_expanded else Res.string.supertag_property_collapsed,
    )
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("supertag-property-${draft.propertyId.value}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
        ) {
            // 自绘轻量交互底色，避免默认 indication 形成紧贴文字的矩形灰条；键盘焦点保留清晰轮廓。
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(headerColor)
                    .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                    .hoverable(headerInteraction)
                    .clickable(interactionSource = headerInteraction, indication = null, role = Role.Button) {
                        expanded = !expanded
                    }
                    .semantics { stateDescription = expansionState }
                    .testTag("supertag-property-header-${draft.propertyId.value}")
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        definition?.name
                            ?: stringResource(Res.string.editor_tag_missing_property, draft.propertyId.value),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        definition?.type?.localizedDisplayName()
                            ?: stringResource(Res.string.editor_tag_schema_damaged),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (definition == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TagBindingOrigin(row)
                }
                TagloomChevronDown(Modifier.size(16.dp).rotate(if (expanded) 180f else 0f))
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (row.conflicted && definition != null) {
                        TagBindingConflictChoices(row, enabled, onChange)
                    } else if (definition != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BindingToggle(stringResource(Res.string.editor_tag_required), draft.required, enabled) { checked ->
                                onChange(draft.copy(required = checked))
                            }
                            BindingToggle(stringResource(Res.string.editor_tag_readonly), draft.readonly, enabled) { checked ->
                                onChange(draft.copy(readonly = checked))
                            }
                            BindingToggle(stringResource(Res.string.editor_tag_child_inherit), draft.inheritable, enabled) { checked ->
                                onChange(draft.copy(inheritable = checked))
                            }
                        }
                        DefaultPolicyEditor(draft, definition, enabled, onChange)
                        if (draft.readonly) Text(stringResource(Res.string.supertag_readonly_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.declaredLocally) TextButton(
                        onClick = onRemove,
                        enabled = enabled,
                        modifier = Modifier.testTag("supertag-property-remove-${draft.propertyId.value}"),
                    ) {
                        Text(stringResource(if (row.inheritedProperty != null) Res.string.supertag_restore_inheritance else Res.string.editor_action_remove))
                    }
                }
            }
        }
    }
}

/**
 * 渲染标签属性的默认策略与按 schema 约束的默认值输入。
 *
 * @param draft 当前绑定草稿。
 * @param definition 属性定义。
 * @param enabled 是否允许修改默认策略。
 * @param onChange 更新绑定草稿。
 */
@Composable
private fun DefaultPolicyEditor(
    draft: TagPropertyBindingDraft,
    definition: PropertyDefinition,
    enabled: Boolean,
    onChange: (TagPropertyBindingDraft) -> Unit,
) {
    var policyMenuOpen by remember(draft.propertyId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box {
            OutlinedButton(onClick = { policyMenuOpen = true }, enabled = enabled) {
                Text(
                    stringResource(
                        Res.string.editor_tag_default_value_prefix,
                        draft.defaultPolicy.localizedDisplayName(),
                    ),
                )
            }
            TagloomDropdownMenu(expanded = policyMenuOpen && enabled, onDismissRequest = { policyMenuOpen = false }) {
                DefaultValuePolicy.entries.forEach { policy ->
                    val selected = policy == draft.defaultPolicy
                    TagloomMenuItem(
                        text = policy.localizedDisplayName(),
                        leadingIcon = if (selected) {
                            { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        selected = selected,
                        onClick = {
                            onChange(draft.copy(defaultPolicy = policy))
                            policyMenuOpen = false
                        },
                    )
                }
            }
        }
        if (draft.defaultPolicy != DefaultValuePolicy.NONE) {
            DefaultValueInput(draft, definition, enabled, onChange)
            Text(
                if (draft.defaultPolicy == DefaultValuePolicy.DYNAMIC) {
                    stringResource(Res.string.editor_tag_default_dynamic_description)
                } else {
                    stringResource(Res.string.editor_tag_default_snapshot_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 按属性类型渲染默认值输入，选项类属性始终保存稳定选项 ID。
 *
 * @param draft 当前绑定草稿。
 * @param definition 属性定义。
 * @param enabled 是否允许编辑默认值。
 * @param onChange 更新默认值草稿。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultValueInput(
    draft: TagPropertyBindingDraft,
    definition: PropertyDefinition,
    enabled: Boolean,
    onChange: (TagPropertyBindingDraft) -> Unit,
) {
    when (definition.type) {
        PropertyTypes.DATE, PropertyTypes.DATETIME -> TemporalValueField(
            value = draft.defaultValueText,
            dateOnly = definition.type == PropertyTypes.DATE,
            editable = enabled,
            onCommit = { onChange(draft.copy(defaultValueText = it)) },
        )
        PropertyTypes.SELECT -> {
            var menuOpen by remember(draft.propertyId) { mutableStateOf(false) }
            val selected = definition.options.firstOrNull { option -> option.id == draft.defaultValueText }
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Text(selected?.label ?: stringResource(Res.string.editor_tag_select_default_option))
                }
                TagloomDropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false }) {
                    definition.options.forEach { option ->
                        val selectedOption = option.id == draft.defaultValueText
                        TagloomMenuItem(
                            text = option.label,
                            leadingIcon = if (selectedOption) {
                                { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            selected = selectedOption,
                            onClick = {
                                onChange(draft.copy(defaultValueText = option.id))
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

        PropertyTypes.MULTI_SELECT -> {
            val selectedIds = draft.defaultValueText.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                definition.options.forEach { option ->
                    val selected = option.id in selectedIds
                    SchemaPill(option.label, emphasized = selected, enabled = enabled) {
                        val next = if (selected) selectedIds - option.id else selectedIds + option.id
                        onChange(draft.copy(defaultValueText = next.sorted().joinToString(",")))
                    }
                }
            }
        }

        PropertyTypes.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SchemaPill(stringResource(Res.string.editor_boolean_true), emphasized = draft.defaultValueText == "true", enabled = enabled) {
                onChange(draft.copy(defaultValueText = "true"))
            }
            SchemaPill(stringResource(Res.string.editor_boolean_false), emphasized = draft.defaultValueText == "false", enabled = enabled) {
                onChange(draft.copy(defaultValueText = "false"))
            }
        }

        else -> OutlinedTextField(
            value = draft.defaultValueText,
            onValueChange = { value -> onChange(draft.copy(defaultValueText = value)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(Res.string.editor_tag_default_value)) },
            placeholder = {
                Text(stringResource(Res.string.editor_tag_input_default_value))
            },
            singleLine = true,
        )
    }
}

/**
 * 渲染标签属性约束的紧凑复选项。
 *
 * @param label 约束名称。
 * @param checked 当前状态。
 * @param enabled 是否允许切换约束。
 * @param onCheckedChange 更新状态。
 */
@Composable
private fun BindingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 渲染继承标签和默认选项使用的紧凑可点击胶囊。
 *
 * @param label 显示文本。
 * @param emphasized 是否使用选中语义色。
 * @param enabled 是否允许点击。
 * @param onClick 点击动作。
 */
@Composable
internal fun SchemaPill(
    label: String,
    emphasized: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 返回标签默认值策略的用户可理解名称。
 *
 * @return 无默认、动态或写入快照。
 */
@Composable
private fun DefaultValuePolicy.localizedDisplayName(): String = when (this) {
    DefaultValuePolicy.NONE -> stringResource(Res.string.editor_default_policy_none)
    DefaultValuePolicy.DYNAMIC -> stringResource(Res.string.editor_default_policy_dynamic)
    DefaultValuePolicy.SNAPSHOT -> stringResource(Res.string.editor_default_policy_snapshot)
}
