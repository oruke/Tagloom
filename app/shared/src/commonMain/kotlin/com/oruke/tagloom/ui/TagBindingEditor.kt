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
                    Text(definition?.name ?: "缺失属性 ${draft.propertyId.value}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        definition?.type?.displayName() ?: "schema 已损坏",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (definition == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRemove) { Text("移除") }
            }
            if (definition != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BindingToggle("必填", draft.required) { checked -> onChange(draft.copy(required = checked)) }
                    BindingToggle("只读", draft.readonly) { checked -> onChange(draft.copy(readonly = checked)) }
                    BindingToggle("子标签继承", draft.inheritable) { checked -> onChange(draft.copy(inheritable = checked)) }
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
                Text("默认值：${draft.defaultPolicy.displayName()}")
            }
            DropdownMenu(expanded = policyMenuOpen, onDismissRequest = { policyMenuOpen = false }) {
                DefaultValuePolicy.entries.forEach { policy ->
                    DropdownMenuItem(
                        text = { Text(policy.displayName()) },
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
                    "动态：未覆盖的笔记会实时跟随标签默认值。"
                } else {
                    "快照：首次关联标签时把值和来源写入笔记，之后不随标签变化。"
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
                OutlinedButton(onClick = { menuOpen = true }) { Text(selected?.label ?: "选择默认选项") }
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
            SchemaPill("是", emphasized = draft.defaultValueText == "true") {
                onChange(draft.copy(defaultValueText = "true"))
            }
            SchemaPill("否", emphasized = draft.defaultValueText == "false") {
                onChange(draft.copy(defaultValueText = "false"))
            }
        }

        else -> OutlinedTextField(
            value = draft.defaultValueText,
            onValueChange = { value -> onChange(draft.copy(defaultValueText = value)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("默认值") },
            placeholder = { Text(if (definition.type == PropertyTypes.DATE) "YYYY-MM-DD" else "输入默认值") },
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
private fun DefaultValuePolicy.displayName(): String = when (this) {
    DefaultValuePolicy.NONE -> "无默认值"
    DefaultValuePolicy.DYNAMIC -> "动态跟随"
    DefaultValuePolicy.SNAPSHOT -> "写入快照"
}
