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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/**
 * 保存对象类型对话框的定义草稿和确认后的笔记关联意图。
 */
internal data class TagDefinitionDialogRequest(
    /** 正在创建、查看或编辑的完整对象类型定义。 */
    val definition: TagDefinition,
    /** 创建成功后是否立即关联当前笔记。 */
    val attachToSelected: Boolean,
)

/**
 * 创建或编辑对象类型名称、多父继承和直接属性 schema，并只读展示内置 Note 根类型。
 *
 * @param state 当前解锁工作区的 schema 状态。
 * @param request 待编辑标签及保存意图。
 * @param onDismiss 放弃未确认草稿。
 * @param onConfirm 提交已经过继承图和属性默认值校验的标签定义。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagDefinitionDialog(
    state: TagloomEditorState,
    request: TagDefinitionDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: (TagDefinition) -> Unit,
) {
    val original = request.definition
    val readOnlyRoot = original.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    var name by remember(original.id) { mutableStateOf(original.name) }
    var parentTagIds by remember(original.id) { mutableStateOf(original.parentTagIds) }
    var bindingDrafts by remember(original.id, state.propertyDefinitions.size) {
        mutableStateOf(createBindingDrafts(original.propertyBindings, state.propertyDefinitions))
    }
    var parentMenuOpen by remember(original.id) { mutableStateOf(false) }
    var propertyMenuOpen by remember(original.id) { mutableStateOf(false) }
    var errorMessage by remember(original.id) { mutableStateOf<String?>(null) }
    val creating = state.tagDefinitions.none { definition -> definition.id == original.id }
    val parentCandidates = state.tagDefinitions.filterNot { definition ->
        definition.id == original.id || definition.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    }
    val boundPropertyIds = bindingDrafts.map { draft -> draft.propertyId }.toSet()
    val availableProperties = state.propertyDefinitions.filterNot { definition -> definition.id in boundPropertyIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    readOnlyRoot -> "查看默认类型"
                    creating -> "新建对象类型"
                    else -> "编辑对象类型"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value ->
                        name = value
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("类型名称") },
                    singleLine = true,
                    readOnly = readOnlyRoot,
                    enabled = !readOnlyRoot,
                )
                if (readOnlyRoot) {
                    Text(
                        "Note 是所有对象的只读根类型，不能重命名、删除或解除继承。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TagParentEditor(
                    state = state,
                    parentTagIds = parentTagIds,
                    candidates = parentCandidates,
                    menuOpen = parentMenuOpen,
                    editable = !readOnlyRoot,
                    onMenuOpenChange = { open -> parentMenuOpen = open },
                    onToggle = { tagId ->
                        parentTagIds = if (tagId in parentTagIds) parentTagIds - tagId else parentTagIds + tagId
                        errorMessage = null
                    },
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("类型属性", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (readOnlyRoot) {
                            "所有 Note 都具备以下基础属性；这里只能查看。"
                        } else {
                            "使用这个类型的 Note 会继承以下属性；子类型可继续重载。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (readOnlyRoot) {
                        ReadOnlyTypeProperties(bindingDrafts, state.propertyDefinitions)
                    } else {
                        bindingDrafts.forEachIndexed { index, draft ->
                            val definition = state.propertyDefinitions.firstOrNull { property -> property.id == draft.propertyId }
                            TagBindingDraftEditor(
                                draft = draft,
                                definition = definition,
                                onChange = { updated ->
                                    bindingDrafts = bindingDrafts.mapIndexed { currentIndex, current ->
                                        if (currentIndex == index) updated else current
                                    }
                                    errorMessage = null
                                },
                                onRemove = {
                                    bindingDrafts = bindingDrafts.filterIndexed { currentIndex, _ -> currentIndex != index }
                                    errorMessage = null
                                },
                            )
                        }
                        Box {
                            OutlinedButton(
                                onClick = { propertyMenuOpen = true },
                                enabled = availableProperties.isNotEmpty(),
                            ) {
                                Text("+ 添加工作区属性")
                            }
                            DropdownMenu(
                                expanded = propertyMenuOpen,
                                onDismissRequest = { propertyMenuOpen = false },
                            ) {
                                availableProperties.forEach { definition ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(definition.name)
                                                Text(
                                                    definition.type.displayName(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            bindingDrafts += TagPropertyBinding(
                                                propertyId = definition.id,
                                                order = bindingDrafts.size,
                                            ).toEditorDraft(definition)
                                            propertyMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                errorMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (readOnlyRoot) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                Button(
                    onClick = {
                        runCatching {
                            val bindings = bindingDrafts.mapIndexed { index, draft ->
                                val definition = requireNotNull(
                                    state.propertyDefinitions.firstOrNull { property -> property.id == draft.propertyId },
                                ) { "类型绑定的属性定义已不存在" }
                                draft.toConfirmedBinding(definition, index)
                            }
                            state.buildTagDefinitionDraft(original, name, parentTagIds, bindings)
                        }.onSuccess(onConfirm).onFailure { failure ->
                            errorMessage = failure.message ?: "对象类型无法保存"
                        }
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(if (creating) "创建类型" else "保存")
                }
            }
        },
        dismissButton = {
            if (!readOnlyRoot) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 渲染对象类型的多父继承选择器。
 *
 * @param state 当前 schema 状态，用于把 ID 映射为名称。
 * @param parentTagIds 当前显式父标签。
 * @param candidates 可选择父标签。
 * @param menuOpen 菜单是否展开。
 * @param editable 是否允许修改父类型。
 * @param onMenuOpenChange 切换菜单展开状态。
 * @param onToggle 增删一个父标签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagParentEditor(
    state: TagloomEditorState,
    parentTagIds: List<TagId>,
    candidates: List<TagDefinition>,
    menuOpen: Boolean,
    editable: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onToggle: (TagId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("继承自", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (parentTagIds.isEmpty()) {
                SchemaPill(if (editable) "Note（默认父类型）" else "根类型", emphasized = false, onClick = {})
            } else {
                parentTagIds.forEach { parentId ->
                    val name = state.tagDefinitions.firstOrNull { definition -> definition.id == parentId }?.name
                        ?: parentId.value
                    SchemaPill("$name  ×", emphasized = true) { onToggle(parentId) }
                }
            }
            if (editable) Box {
                TextButton(onClick = { onMenuOpenChange(true) }) { Text("+ 父类型") }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    candidates.forEach { definition ->
                        val selected = definition.id in parentTagIds
                        DropdownMenuItem(
                            text = { Text(definition.name) },
                            leadingIcon = { Text(if (selected) "✓" else "") },
                            onClick = { onToggle(definition.id) },
                        )
                    }
                }
            }
        }
        Text(
            if (editable) "支持多父继承；同层属性约束不一致时会在 Note 中显示冲突。" else "所有自定义对象类型最终继承此根类型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 以不可交互列表展示 Note 根类型绑定的基础属性。
 *
 * @param bindings 根类型当前属性绑定。
 * @param definitions 工作区属性定义目录。
 */
@Composable
private fun ReadOnlyTypeProperties(
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
                    definition?.type?.displayName() ?: "缺失定义",
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
private fun createBindingDrafts(
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
