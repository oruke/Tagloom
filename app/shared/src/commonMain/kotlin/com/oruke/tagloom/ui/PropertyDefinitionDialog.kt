package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.PropertyOptionDraft
import com.oruke.tagloom.editor.requiresPropertyOptions
import com.oruke.tagloom.editor.toEditorDraft
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 保存属性定义对话框的编辑目标与新建初始名称。
 */
internal data class PropertyDialogRequest(
    /** 既有定义；为空表示新建。 */
    val existing: PropertyDefinition?,
    /** 打开对话框时预填名称。 */
    val initialName: String,
)

/**
 * 渲染属性名称、类型和逐项稳定选项编辑对话框。
 *
 * 选项以独立草稿保留既有 ID，因此新增、重命名或移除一个选项不会改变其他选项的身份。
 *
 * @param request 既有定义或新建初始名称。
 * @param tagDefinitions 可接收新属性 schema 的对象类型。
 * @param onDismiss 取消编辑。
 * @param onConfirm 提交名称、类型、选项输入模式、有序选项草稿和创建目标。
 */
@Composable
internal fun PropertyDefinitionDialog(
    request: PropertyDialogRequest,
    tagDefinitions: List<TagDefinition>,
    onDismiss: () -> Unit,
    onConfirm: (
        String,
        String,
        PropertyOptionInputMode,
        List<PropertyOptionDraft>,
        PropertyDefinitionDestination,
    ) -> Unit,
) {
    var name by remember(request) { mutableStateOf(request.initialName) }
    var type by remember(request) { mutableStateOf(request.existing?.type ?: PropertyTypes.TEXT) }
    var optionDrafts by remember(request) {
        mutableStateOf(request.existing?.options?.map { option -> option.toEditorDraft() }.orEmpty())
    }
    var optionInputMode by remember(request) {
        mutableStateOf(request.existing?.optionInputMode ?: PropertyOptionInputMode.FIXED)
    }
    var typeMenuOpen by remember(request) { mutableStateOf(false) }
    var creationScope by remember(request) { mutableStateOf(PropertyCreationScope.CURRENT_NOTE) }
    var selectedTagId by remember(request, tagDefinitions) { mutableStateOf(tagDefinitions.firstOrNull()?.id) }
    val normalizedLabels = optionDrafts.map { option -> option.label.trim().lowercase() }
    val hasBlankOption = normalizedLabels.any(String::isEmpty)
    val hasDuplicateOption = normalizedLabels.distinct().size != normalizedLabels.size
    val optionsValid = !type.requiresPropertyOptions() || (
        (optionInputMode == PropertyOptionInputMode.FREE || optionDrafts.isNotEmpty()) &&
            !hasBlankOption && !hasDuplicateOption
        )
    val nextOptionLabel = nextOptionLabel(optionDrafts)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (request.existing == null) {
                    stringResource(Res.string.editor_property_new)
                } else {
                    stringResource(Res.string.editor_property_edit)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> name = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.editor_property_name)) },
                    singleLine = true,
                )
                PropertyTypePicker(
                    type = type,
                    expanded = typeMenuOpen,
                    editable = request.existing == null,
                    onExpandedChange = { expanded -> typeMenuOpen = expanded },
                    onTypeSelected = { selected -> type = selected },
                )
                if (request.existing != null) {
                    Text(
                        stringResource(Res.string.editor_property_existing_type_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (type.requiresPropertyOptions()) {
                    PropertyOptionInputModePicker(
                        mode = optionInputMode,
                        onModeChange = { selected -> optionInputMode = selected },
                    )
                    PropertyOptionDraftList(
                        options = optionDrafts,
                        inputMode = optionInputMode,
                        hasBlankOption = hasBlankOption,
                        hasDuplicateOption = hasDuplicateOption,
                        onLabelChange = { index, label ->
                            optionDrafts = optionDrafts.mapIndexed { currentIndex, option ->
                                if (currentIndex == index) option.copy(label = label) else option
                            }
                        },
                        onRemove = { index ->
                            optionDrafts = optionDrafts.filterIndexed { currentIndex, _ -> currentIndex != index }
                        },
                        onAdd = {
                            optionDrafts += PropertyOptionDraft(
                                id = null,
                                label = nextOptionLabel,
                            )
                        },
                    )
                }
                if (request.existing == null) {
                    PropertyDestinationPicker(
                        scope = creationScope,
                        tagDefinitions = tagDefinitions,
                        selectedTagId = selectedTagId,
                        onScopeChange = { scope -> creationScope = scope },
                        onTagChange = { tagId -> selectedTagId = tagId },
                    )
                }
            }
        },
        confirmButton = {
            val destination = when {
                request.existing != null -> PropertyDefinitionDestination.Workspace
                creationScope == PropertyCreationScope.CURRENT_NOTE -> PropertyDefinitionDestination.CurrentNote
                creationScope == PropertyCreationScope.WORKSPACE -> PropertyDefinitionDestination.Workspace
                else -> selectedTagId?.let(PropertyDefinitionDestination::Tag)
            }
            Button(
                onClick = {
                    onConfirm(name, type, optionInputMode, optionDrafts, requireNotNull(destination))
                },
                enabled = name.isNotBlank() && optionsValid && destination != null,
            ) {
                Text(destination.confirmLabel(request.existing != null, tagDefinitions))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.workspace_action_cancel))
            }
        },
    )
}

/**
 * 渲染 select 类属性的固定选项与自由输入策略。
 *
 * @param mode 当前选项输入模式。
 * @param onModeChange 切换输入模式。
 */
@Composable
private fun PropertyOptionInputModePicker(
    mode: PropertyOptionInputMode,
    onModeChange: (PropertyOptionInputMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.editor_property_option_source), style = MaterialTheme.typography.titleSmall)
        OptionInputModeChoice(
            label = stringResource(Res.string.editor_property_option_fixed),
            description = stringResource(Res.string.editor_property_option_fixed_description),
            selected = mode == PropertyOptionInputMode.FIXED,
        ) { onModeChange(PropertyOptionInputMode.FIXED) }
        OptionInputModeChoice(
            label = stringResource(Res.string.editor_property_option_free),
            description = stringResource(Res.string.editor_property_option_free_description),
            selected = mode == PropertyOptionInputMode.FREE,
        ) { onModeChange(PropertyOptionInputMode.FREE) }
    }
}

/**
 * 渲染一个选项输入策略及其影响说明。
 *
 * @param label 策略名称。
 * @param description 策略效果。
 * @param selected 当前是否选中。
 * @param onClick 选择动作。
 */
@Composable
private fun OptionInputModeChoice(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染新属性的三种产品级落点：当前笔记、对象类型或工作区目录。
 *
 * @param scope 当前落点类别。
 * @param tagDefinitions 可选择标签。
 * @param selectedTagId 当前标签目标。
 * @param onScopeChange 切换落点类别。
 * @param onTagChange 选择标签目标。
 */
@Composable
private fun PropertyDestinationPicker(
    scope: PropertyCreationScope,
    tagDefinitions: List<TagDefinition>,
    selectedTagId: com.oruke.tagloom.core.model.TagId?,
    onScopeChange: (PropertyCreationScope) -> Unit,
    onTagChange: (com.oruke.tagloom.core.model.TagId) -> Unit,
) {
    var tagMenuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.editor_property_create_to), style = MaterialTheme.typography.titleSmall)
        DestinationChoice(
            label = stringResource(Res.string.editor_property_current_note),
            description = stringResource(Res.string.editor_property_current_note_description),
            selected = scope == PropertyCreationScope.CURRENT_NOTE,
            enabled = true,
        ) { onScopeChange(PropertyCreationScope.CURRENT_NOTE) }
        DestinationChoice(
            label = stringResource(Res.string.editor_property_object_type),
            description = stringResource(Res.string.editor_property_object_type_description),
            selected = scope == PropertyCreationScope.TAG,
            enabled = tagDefinitions.isNotEmpty(),
        ) { onScopeChange(PropertyCreationScope.TAG) }
        if (scope == PropertyCreationScope.TAG && tagDefinitions.isNotEmpty()) {
            Box {
                val selectedName = tagDefinitions.firstOrNull { definition -> definition.id == selectedTagId }?.name
                OutlinedButton(onClick = { tagMenuOpen = true }) {
                    Text(selectedName ?: stringResource(Res.string.editor_property_choose_type))
                }
                TagloomDropdownMenu(expanded = tagMenuOpen, onDismissRequest = { tagMenuOpen = false }) {
                    tagDefinitions.forEach { definition ->
                        val selected = definition.id == selectedTagId
                        TagloomMenuItem(
                            text = definition.name,
                            leadingIcon = if (selected) {
                                { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            selected = selected,
                            onClick = {
                                onTagChange(definition.id)
                                tagMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        DestinationChoice(
            label = stringResource(Res.string.editor_property_workspace_only),
            description = stringResource(Res.string.editor_property_workspace_only_description),
            selected = scope == PropertyCreationScope.WORKSPACE,
            enabled = true,
        ) { onScopeChange(PropertyCreationScope.WORKSPACE) }
    }
}

/**
 * 渲染一个属性创建落点及其影响说明。
 *
 * @param label 落点名称。
 * @param description 落点影响。
 * @param selected 是否已选择。
 * @param enabled 是否可用。
 * @param onClick 选择动作。
 */
@Composable
private fun DestinationChoice(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 返回属性对话框确认按钮应展示的明确结果。
 *
 * @param editing 是否编辑既有定义。
 * @param tags 当前标签目录。
 * @return 面向用户的保存动作文本。
 */
@Composable
private fun PropertyDefinitionDestination?.confirmLabel(
    editing: Boolean,
    tags: List<TagDefinition>,
): String = when {
    editing -> stringResource(Res.string.workspace_action_save)
    this == PropertyDefinitionDestination.CurrentNote -> {
        stringResource(Res.string.editor_property_confirm_current_note)
    }
    this == PropertyDefinitionDestination.Workspace -> {
        stringResource(Res.string.editor_property_confirm_workspace)
    }
    this is PropertyDefinitionDestination.Tag -> {
        val name = tags.firstOrNull { definition -> definition.id == tagId }?.name
            ?: stringResource(Res.string.editor_property_object_type)
        stringResource(Res.string.editor_property_confirm_bind_type, name)
    }
    else -> stringResource(Res.string.editor_property_confirm_choose_destination)
}

/** 区分新属性对话框中的三种创建层级。 */
private enum class PropertyCreationScope {
    /** 只绑定当前笔记。 */
    CURRENT_NOTE,
    /** 绑定一个对象类型。 */
    TAG,
    /** 仅进入工作区属性目录。 */
    WORKSPACE,
}

/**
 * 渲染可切换的属性类型按钮；既有定义只显示当前类型。
 *
 * @param type 当前类型。
 * @param expanded 类型菜单是否展开。
 * @param editable 是否允许改变类型。
 * @param onExpandedChange 请求切换菜单状态。
 * @param onTypeSelected 选择一个新类型。
 */
@Composable
private fun PropertyTypePicker(
    type: String,
    expanded: Boolean,
    editable: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTypeSelected: (String) -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = editable,
        ) {
            Text(stringResource(Res.string.editor_property_type_prefix, type.localizedDisplayName()))
        }
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            EDITABLE_PROPERTY_TYPES.forEach { candidate ->
                val selected = candidate == type
                TagloomMenuItem(
                    text = candidate.localizedDisplayName(),
                    leadingIcon = if (selected) {
                        { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    selected = selected,
                    onClick = {
                        onTypeSelected(candidate)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

/**
 * 渲染 select 类属性的有序选项草稿列表与校验反馈。
 *
 * @param options 当前有序选项草稿。
 * @param inputMode 固定模式要求至少一个选项，自由模式允许从空目录开始。
 * @param hasBlankOption 是否存在空名称。
 * @param hasDuplicateOption 是否存在不区分大小写的重复名称。
 * @param onLabelChange 更新一个选项名称。
 * @param onRemove 移除一个选项。
 * @param onAdd 追加新选项。
 */
@Composable
private fun PropertyOptionDraftList(
    options: List<PropertyOptionDraft>,
    inputMode: PropertyOptionInputMode,
    hasBlankOption: Boolean,
    hasDuplicateOption: Boolean,
    onLabelChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.editor_property_options), style = MaterialTheme.typography.titleSmall)
        options.forEachIndexed { index, option ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = option.label,
                    onValueChange = { label -> onLabelChange(index, label) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(Res.string.editor_property_option_name)) },
                    singleLine = true,
                )
                TextButton(onClick = { onRemove(index) }) {
                    Text(stringResource(Res.string.editor_action_remove))
                }
            }
        }
        OutlinedButton(onClick = onAdd) { Text(stringResource(Res.string.editor_property_add_option)) }
        when {
            options.isEmpty() && inputMode == PropertyOptionInputMode.FIXED -> {
                PropertyOptionError(stringResource(Res.string.editor_property_option_required))
            }
            hasBlankOption -> PropertyOptionError(stringResource(Res.string.editor_property_option_name_required))
            hasDuplicateOption -> PropertyOptionError(stringResource(Res.string.editor_property_option_name_duplicate))
        }
    }
}

/**
 * 渲染不会占据输入控件位置的选项校验提示。
 *
 * @param message 面向用户的校验原因。
 */
@Composable
private fun PropertyOptionError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 返回不与现有选项重名的本地化默认名称。
 *
 * @param options 当前选项草稿。
 * @return 当前 Locale 下最小可用的“选项 N”名称。
 */
@Composable
private fun nextOptionLabel(options: List<PropertyOptionDraft>): String {
    val existing = options.map { option -> option.label.trim() }.toSet()
    var number = 1
    var candidate = stringResource(Res.string.editor_property_option_default_name, number)
    while (candidate in existing) {
        number += 1
        candidate = stringResource(Res.string.editor_property_option_default_name, number)
    }
    return candidate
}

/** 编辑器当前提供完整值输入的属性类型。 */
private val EDITABLE_PROPERTY_TYPES: List<String> = listOf(
    PropertyTypes.TEXT,
    PropertyTypes.NUMBER,
    PropertyTypes.BOOLEAN,
    PropertyTypes.DATE,
    PropertyTypes.DATETIME,
    PropertyTypes.SELECT,
    PropertyTypes.MULTI_SELECT,
    PropertyTypes.URL,
    PropertyTypes.EMAIL,
)
