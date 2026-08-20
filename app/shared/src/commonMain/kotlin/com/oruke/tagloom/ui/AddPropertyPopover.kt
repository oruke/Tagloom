package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.PropertyOptionDraft
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.buildPropertyDefinitionDraft
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染锚定在属性区末尾的渐进式新增属性浮层。
 *
 * @param state 当前编辑器及属性目录。
 * @param document 当前笔记。
 * @param effectivePropertyIds 已由类型或笔记绑定引入的属性 ID。
 * @param onCreateProperty 持久化新属性并绑定目标。
 * @param onAttachExisting 复用既有属性并绑定目标。
 */
@Composable
internal fun AddPropertyPopover(
    state: TagloomEditorState,
    document: EditorDocument,
    effectivePropertyIds: Set<PropertyId>,
    onCreateProperty: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onAttachExisting: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
) {
    var expanded by remember(document.id) { mutableStateOf(false) }
    var step by remember(document.id) { mutableStateOf(AddPropertyStep.SEARCH) }
    var query by remember(document.id) { mutableStateOf("") }
    var existing by remember(document.id) { mutableStateOf<PropertyDefinition?>(null) }
    var name by remember(document.id) { mutableStateOf("") }
    var type by remember(document.id) { mutableStateOf(PropertyTypes.TEXT) }
    var optionMode by remember(document.id) { mutableStateOf(PropertyOptionInputMode.FREE) }
    var optionText by remember(document.id) { mutableStateOf("") }
    var destination by remember(document.id) {
        mutableStateOf<PropertyDefinitionDestination>(PropertyDefinitionDestination.CurrentNote)
    }
    var error by remember(document.id) { mutableStateOf<String?>(null) }
    val createPropertyError = stringResource(Res.string.editor_error_create_property)

    /**
     * 清空浮层内的临时状态，使下一次打开从搜索开始。
     */
    fun reset() {
        step = AddPropertyStep.SEARCH
        query = ""
        existing = null
        name = ""
        type = PropertyTypes.TEXT
        optionMode = PropertyOptionInputMode.FREE
        optionText = ""
        destination = PropertyDefinitionDestination.CurrentNote
        error = null
    }

    /**
     * 关闭浮层并清理未确认草稿。
     */
    fun dismiss() {
        expanded = false
        reset()
    }

    Box {
        Text(
            text = stringResource(Res.string.editor_property_add),
            modifier = Modifier.clickable { expanded = true }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = ::dismiss,
        ) {
            when (step) {
                AddPropertyStep.SEARCH -> AddPropertySearchStep(
                    state = state,
                    query = query,
                    effectivePropertyIds = effectivePropertyIds,
                    onQueryChange = { value -> query = value },
                    onSelectExisting = { definition ->
                        existing = definition
                        name = definition.name
                        type = definition.type
                        optionMode = definition.optionInputMode
                        step = AddPropertyStep.CONFIGURE
                    },
                    onCreate = {
                        name = query.trim()
                        existing = null
                        step = AddPropertyStep.CONFIGURE
                    },
                )

                AddPropertyStep.CONFIGURE -> AddPropertyConfigureStep(
                    state = state,
                    document = document,
                    existing = existing,
                    name = name,
                    type = type,
                    optionMode = optionMode,
                    optionText = optionText,
                    destination = destination,
                    error = error,
                    onBack = {
                        step = AddPropertyStep.SEARCH
                        error = null
                    },
                    onNameChange = { value ->
                        name = value
                        error = null
                    },
                    onTypeChange = { value ->
                        type = value
                        optionMode = if (value.isOptionType()) PropertyOptionInputMode.FREE else PropertyOptionInputMode.FIXED
                        error = null
                    },
                    onOptionModeChange = { value ->
                        optionMode = value
                        error = null
                    },
                    onOptionTextChange = { value ->
                        optionText = value
                        error = null
                    },
                    onDestinationChange = { value -> destination = value },
                    onCancel = ::dismiss,
                    onConfirm = {
                        val failure = runCatching {
                            val selectedExisting = existing
                            if (selectedExisting != null) {
                                onAttachExisting(selectedExisting, destination)
                            } else {
                                val definition = state.buildPropertyDefinitionDraft(
                                    existing = null,
                                    name = name,
                                    type = type,
                                    optionInputMode = optionMode,
                                    optionDrafts = optionText.toOptionDrafts(),
                                )
                                onCreateProperty(definition, destination)
                            }
                        }.exceptionOrNull()
                        if (failure == null) dismiss() else error = createPropertyError
                    },
                )
            }
        }
    }
}

/**
 * 渲染新增属性第一步：搜索复用或进入新建配置。
 *
 * @param state 当前属性目录。
 * @param query 当前搜索文本。
 * @param effectivePropertyIds 当前已存在属性。
 * @param onQueryChange 搜索更新动作。
 * @param onSelectExisting 选择既有属性动作。
 * @param onCreate 使用当前名称进入新建动作。
 */
@Composable
private fun AddPropertySearchStep(
    state: TagloomEditorState,
    query: String,
    effectivePropertyIds: Set<PropertyId>,
    onQueryChange: (String) -> Unit,
    onSelectExisting: (PropertyDefinition) -> Unit,
    onCreate: () -> Unit,
) {
    PopoverSearchField(
        value = query,
        placeholder = stringResource(Res.string.editor_property_input_name),
        requestFocus = true,
        onValueChange = onQueryChange,
    )
    val candidates = state.propertyDefinitions
        .filterNot { definition ->
            definition.id == WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID ||
                definition.id == WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID ||
                definition.id in effectivePropertyIds
        }
        .filter { definition -> query.isBlank() || definition.name.contains(query, ignoreCase = true) }
    candidates.take(8).forEach { definition ->
        TagloomMenuItem(
            text = definition.name,
            trailingText = definition.type.localizedDisplayName(),
            leadingIcon = { Text("+", style = MaterialTheme.typography.bodyMedium) },
            onClick = { onSelectExisting(definition) },
        )
    }
    TagloomMenuDivider()
    TagloomMenuItem(
        text = if (query.isBlank()) {
            stringResource(Res.string.editor_property_new)
        } else {
            stringResource(Res.string.editor_property_create_named, query.trim())
        },
        leadingIcon = { Text("+", style = MaterialTheme.typography.bodyMedium) },
        onClick = onCreate,
    )
}

/**
 * 渲染新增属性第二步：类型、选项模式和绑定目标。
 *
 * @param state 当前类型目录。
 * @param document 当前笔记。
 * @param existing 复用的既有属性；为空表示新建。
 * @param name 属性名称。
 * @param type 属性类型。
 * @param optionMode 单选或多选输入模式。
 * @param optionText 固定选项逗号分隔草稿。
 * @param destination 绑定目标。
 * @param error 当前校验错误。
 * @param onBack 返回搜索动作。
 * @param onNameChange 名称更新动作。
 * @param onTypeChange 类型更新动作。
 * @param onOptionModeChange 选项模式更新动作。
 * @param onOptionTextChange 固定选项更新动作。
 * @param onDestinationChange 绑定目标更新动作。
 * @param onCancel 取消动作。
 * @param onConfirm 确认动作。
 */
@Composable
private fun AddPropertyConfigureStep(
    state: TagloomEditorState,
    document: EditorDocument,
    existing: PropertyDefinition?,
    name: String,
    type: String,
    optionMode: PropertyOptionInputMode,
    optionText: String,
    destination: PropertyDefinitionDestination,
    error: String?,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onOptionModeChange: (PropertyOptionInputMode) -> Unit,
    onOptionTextChange: (String) -> Unit,
    onDestinationChange: (PropertyDefinitionDestination) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.width(360.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                if (existing == null) {
                    stringResource(Res.string.editor_property_new)
                } else {
                    stringResource(Res.string.editor_property_add_existing)
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
        LabeledPopoverField(stringResource(Res.string.editor_property_name)) {
            CompactPropertyInput(
                name,
                stringResource(Res.string.editor_property_input_name),
                readOnly = existing != null,
                onValueChange = onNameChange,
            )
        }
        LabeledPopoverField(stringResource(Res.string.editor_property_type)) {
            if (existing == null) {
                PropertyTypeSelector(value = type, onValueChange = onTypeChange)
            } else {
                ReadOnlyPopoverValue(type.localizedDisplayName())
            }
        }
        if (type.isOptionType() && existing == null) {
            LabeledPopoverField(stringResource(Res.string.editor_property_option_source)) {
                OptionModeSelector(value = optionMode, onValueChange = onOptionModeChange)
            }
            if (optionMode == PropertyOptionInputMode.FIXED) {
                LabeledPopoverField(stringResource(Res.string.editor_property_option_fixed)) {
                    CompactPropertyInput(
                        optionText,
                        stringResource(Res.string.editor_property_option_examples),
                        onValueChange = onOptionTextChange,
                    )
                }
            }
        }
        LabeledPopoverField(stringResource(Res.string.editor_property_create_to)) {
            PropertyDestinationSelector(
                state = state,
                document = document,
                value = destination,
                onValueChange = onDestinationChange,
            )
        }
        Text(
            if (destination == PropertyDefinitionDestination.CurrentNote) {
                stringResource(Res.string.editor_property_current_note_hint)
            } else {
                stringResource(Res.string.editor_property_type_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.workspace_action_cancel),
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.clickable(onClick = onConfirm),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (existing == null) {
                        stringResource(Res.string.editor_property_add)
                    } else {
                        stringResource(Res.string.editor_property_add_existing)
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * 渲染带低强调字段名的浮层配置行。
 *
 * @param label 字段名。
 * @param content 字段控件。
 */
@Composable
private fun LabeledPopoverField(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/**
 * 渲染浮层内紧凑的有边界文本输入。
 *
 * @param value 当前值。
 * @param placeholder 空值提示。
 * @param readOnly 是否只读。
 * @param onValueChange 更新动作。
 */
@Composable
private fun CompactPropertyInput(
    value: String,
    placeholder: String,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * 渲染不可编辑的属性配置值。
 *
 * @param value 展示文本。
 */
@Composable
private fun ReadOnlyPopoverValue(value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(value, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 判断属性类型是否依赖选项目录。
 *
 * @return 单选或多选时为 true。
 */
private fun String.isOptionType(): Boolean = this == PropertyTypes.SELECT || this == PropertyTypes.MULTI_SELECT

/**
 * 把逗号、中文逗号或换行分隔文本转换为待确认选项草稿。
 *
 * @return 去除空值且保留输入顺序的选项草稿。
 */
private fun String.toOptionDrafts(): List<PropertyOptionDraft> = split(',', '，', '\n')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { label -> PropertyOptionDraft(id = null, label = label) }

/**
 * 区分新增属性浮层的搜索和配置阶段。
 */
private enum class AddPropertyStep {
    /** 搜索已有属性或输入新名称。 */
    SEARCH,

    /** 配置类型、选项与绑定目标。 */
    CONFIGURE,
}
