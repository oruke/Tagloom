package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.editor.TagPropertyBindingDraft
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染一个标签直接属性绑定及其约束和默认策略。
 *
 * @param draft 当前可编辑绑定草稿。
 * @param definition 属性定义；缺失时只允许移除损坏引用。
 * @param onChange 更新绑定草稿。
 * @param onRemove 从标签 schema 移除绑定。
 */
@Composable
internal fun TagBindingDraftEditor(
    draft: TagPropertyBindingDraft,
    definition: PropertyDefinition?,
    onChange: (TagPropertyBindingDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
                TextButton(onClick = onRemove) { Text(stringResource(Res.string.editor_action_remove)) }
            }
            if (definition != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BindingToggle(stringResource(Res.string.editor_tag_required), draft.required) { checked ->
                        onChange(draft.copy(required = checked))
                    }
                    BindingToggle(stringResource(Res.string.editor_tag_readonly), draft.readonly) { checked ->
                        onChange(draft.copy(readonly = checked))
                    }
                    BindingToggle(stringResource(Res.string.editor_tag_child_inherit), draft.inheritable) { checked ->
                        onChange(draft.copy(inheritable = checked))
                    }
                }
                DefaultPolicyEditor(draft, definition, onChange)
            }
        }
    }
}

/**
 * 渲染标签属性的默认策略与按 schema 约束的默认值输入。
 *
 * @param draft 当前绑定草稿。
 * @param definition 属性定义。
 * @param onChange 更新绑定草稿。
 */
@Composable
private fun DefaultPolicyEditor(
    draft: TagPropertyBindingDraft,
    definition: PropertyDefinition,
    onChange: (TagPropertyBindingDraft) -> Unit,
) {
    var policyMenuOpen by remember(draft.propertyId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box {
            OutlinedButton(onClick = { policyMenuOpen = true }) {
                Text(
                    stringResource(
                        Res.string.editor_tag_default_value_prefix,
                        draft.defaultPolicy.localizedDisplayName(),
                    ),
                )
            }
            DropdownMenu(expanded = policyMenuOpen, onDismissRequest = { policyMenuOpen = false }) {
                DefaultValuePolicy.entries.forEach { policy ->
                    DropdownMenuItem(
                        text = { Text(policy.localizedDisplayName()) },
                        leadingIcon = { Text(if (policy == draft.defaultPolicy) "✓" else "") },
                        onClick = {
                            onChange(draft.copy(defaultPolicy = policy))
                            policyMenuOpen = false
                        },
                    )
                }
            }
        }
        if (draft.defaultPolicy != DefaultValuePolicy.NONE) {
            DefaultValueInput(draft, definition, onChange)
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
 * @param onChange 更新默认值草稿。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultValueInput(
    draft: TagPropertyBindingDraft,
    definition: PropertyDefinition,
    onChange: (TagPropertyBindingDraft) -> Unit,
) {
    when (definition.type) {
        PropertyTypes.SELECT -> {
            var menuOpen by remember(draft.propertyId) { mutableStateOf(false) }
            val selected = definition.options.firstOrNull { option -> option.id == draft.defaultValueText }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(selected?.label ?: stringResource(Res.string.editor_tag_select_default_option))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    definition.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = { Text(if (option.id == draft.defaultValueText) "✓" else "") },
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
                    SchemaPill(option.label, emphasized = selected) {
                        val next = if (selected) selectedIds - option.id else selectedIds + option.id
                        onChange(draft.copy(defaultValueText = next.sorted().joinToString(",")))
                    }
                }
            }
        }

        PropertyTypes.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SchemaPill(stringResource(Res.string.editor_boolean_true), emphasized = draft.defaultValueText == "true") {
                onChange(draft.copy(defaultValueText = "true"))
            }
            SchemaPill(stringResource(Res.string.editor_boolean_false), emphasized = draft.defaultValueText == "false") {
                onChange(draft.copy(defaultValueText = "false"))
            }
        }

        else -> OutlinedTextField(
            value = draft.defaultValueText,
            onValueChange = { value -> onChange(draft.copy(defaultValueText = value)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.editor_tag_default_value)) },
            placeholder = {
                Text(
                    if (definition.type == PropertyTypes.DATE) {
                        stringResource(Res.string.editor_property_date_placeholder)
                    } else {
                        stringResource(Res.string.editor_tag_input_default_value)
                    },
                )
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
 * @param onCheckedChange 更新状态。
 */
@Composable
private fun BindingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 渲染继承标签和默认选项使用的紧凑可点击胶囊。
 *
 * @param label 显示文本。
 * @param emphasized 是否使用选中语义色。
 * @param onClick 点击动作。
 */
@Composable
internal fun SchemaPill(
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
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
