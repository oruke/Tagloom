package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.domain.property.EffectiveProperty
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.setPropertyAttached
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 在标题下方元数据区渲染一项紧凑属性值。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param property 已完成标签继承、手动绑定和默认值解析的有效属性。
 * @param onEditDefinition 请求编辑工作区级 schema。
 * @param onEditSourceTag 请求编辑提供当前约束的对象类型。
 * @param onCreateOption 请求持久化并选择自由输入选项。
 * @param modifier 外部属性网格布局修饰符。
 */
@Composable
internal fun WorkspacePropertyValueEditor(
    state: TagloomEditorState,
    document: EditorDocument,
    property: EffectiveProperty,
    onEditDefinition: (PropertyDefinition) -> Unit,
    onEditSourceTag: (TagDefinition) -> Unit,
    onCreateOption: (PropertyId, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val definition = requireNotNull(property.definition)
    var menuOpen by remember(document.id, definition.id) { mutableStateOf(false) }
    val hoverSource = remember(document.id, definition.id) { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val manuallyAttached = document.note.manualPropertyBindings.any { binding -> binding.propertyId == definition.id }
    val required = property.binding?.required == true
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 32.dp).hoverable(hoverSource),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (required) "${definition.name} *" else definition.name,
            modifier = Modifier.width(88.dp).clickable { menuOpen = true },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) {
            PropertyValueControl(state, document, property, onCreateOption)
        }
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            if (hovered || menuOpen) {
                Text(
                    "⋯",
                    modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("编辑属性定义") },
                    onClick = {
                        menuOpen = false
                        onEditDefinition(definition)
                    },
                )
                property.sourceTagId?.let { sourceTagId ->
                    state.tagDefinitions.firstOrNull { tag -> tag.id == sourceTagId }?.let { sourceTag ->
                        DropdownMenuItem(
                            text = { Text("编辑来源类型 ${sourceTag.name}") },
                            onClick = {
                                menuOpen = false
                                onEditSourceTag(sourceTag)
                            },
                        )
                    }
                }
                if (manuallyAttached) {
                    DropdownMenuItem(
                        text = { Text("从当前笔记移除") },
                        onClick = {
                            menuOpen = false
                            state.setPropertyAttached(definition.id, attached = false)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 按属性类型选择对应的紧凑值控件。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param property 完整有效属性。
 * @param onCreateOption 请求持久化并选择自由输入选项。
 */
@Composable
private fun PropertyValueControl(
    state: TagloomEditorState,
    document: EditorDocument,
    property: EffectiveProperty,
    onCreateOption: (PropertyId, String) -> Unit,
) {
    val definition = requireNotNull(property.definition)
    val editable = property.binding?.readonly != true
    when (definition.type) {
        PropertyTypes.TEXT,
        PropertyTypes.URL,
        PropertyTypes.EMAIL,
        -> MetadataTextProperty(state, document, definition, property.value, editable)

        PropertyTypes.NUMBER -> MetadataValidatedProperty(
            state, document, definition, property.value, editable, ValueValidationMode.NUMBER,
        )
        PropertyTypes.DATE -> MetadataValidatedProperty(
            state, document, definition, property.value, editable, ValueValidationMode.DATE,
        )
        PropertyTypes.DATETIME -> MetadataValidatedProperty(
            state, document, definition, property.value, editable, ValueValidationMode.DATETIME,
        )
        PropertyTypes.BOOLEAN -> MetadataBooleanProperty(state, document, definition, property.value, editable)
        PropertyTypes.SELECT -> MetadataSelectProperty(
            state, document, definition, property.value, editable, multiple = false, onCreateOption,
        )
        PropertyTypes.MULTI_SELECT -> MetadataSelectProperty(
            state, document, definition, property.value, editable, multiple = true, onCreateOption,
        )
        else -> Column {
            Text(
                property.value?.toString()?.take(120).orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${definition.type.displayName()} · 只读保留",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染文本、URL 或邮箱属性的无边框输入。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param definition 属性定义。
 * @param effectiveValue 解析标签默认值后的当前展示值。
 * @param editable 当前标签约束是否允许编辑。
 */
@Composable
private fun MetadataTextProperty(
    state: TagloomEditorState,
    document: EditorDocument,
    definition: PropertyDefinition,
    effectiveValue: JsonElement?,
    editable: Boolean,
) {
    val value = effectiveValue.stringContent()
    MetadataTextField(
        value = value,
        placeholder = if (editable) "—" else "只读 · —",
        onValueChange = { next -> state.propertyEditor.updateText(definition.id, next) },
        readOnly = !editable,
    )
}

/**
 * 渲染需要确认校验的数字、日期或日期时间属性。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param definition 属性定义。
 * @param effectiveValue 解析后的当前展示值。
 * @param editable 当前标签约束是否允许编辑。
 * @param mode 值校验与提交策略。
 */
@Composable
private fun MetadataValidatedProperty(
    state: TagloomEditorState,
    document: EditorDocument,
    definition: PropertyDefinition,
    effectiveValue: JsonElement?,
    editable: Boolean,
    mode: ValueValidationMode,
) {
    val displayed = effectiveValue.stringContent()
    var draft by remember(document.id, definition.id, displayed) { mutableStateOf(displayed) }
    var error by remember(document.id, definition.id) { mutableStateOf<String?>(null) }

    /**
     * 校验并提交当前草稿，失败时保留用户输入。
     */
    fun submitDraft() {
        runCatching {
            when (mode) {
                ValueValidationMode.NUMBER -> state.propertyEditor.updateNumber(definition.id, draft)
                ValueValidationMode.DATE -> state.propertyEditor.updateDate(definition.id, draft)
                ValueValidationMode.DATETIME -> state.propertyEditor.updateText(definition.id, draft)
            }
        }.onSuccess {
            error = null
        }.onFailure { failure ->
            error = failure.message
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetadataTextField(
                value = draft,
                placeholder = mode.placeholder,
                onValueChange = { next ->
                    draft = next
                    error = null
                },
                readOnly = !editable,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (editable && event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        submitDraft()
                        true
                    } else {
                        false
                    }
                },
            )
            if (editable && draft.trim() != displayed) {
                MetadataInlineAction(label = "应用", enabled = true, onClick = ::submitDraft)
            }
        }
        error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 安全读取属性 JSON 值的标量文本。
 *
 * @return 字符串、数字或布尔值文本；类型不匹配时为空。
 */
private fun JsonElement?.stringContent(): String = runCatching {
    this?.jsonPrimitive?.contentOrNull.orEmpty()
}.getOrDefault("")

/**
 * 渲染布尔属性的紧凑复选框。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param definition 属性定义。
 * @param effectiveValue 解析后的当前展示值。
 * @param editable 当前标签约束是否允许编辑。
 */
@Composable
private fun MetadataBooleanProperty(
    state: TagloomEditorState,
    document: EditorDocument,
    definition: PropertyDefinition,
    effectiveValue: JsonElement?,
    editable: Boolean,
) {
    val value = effectiveValue?.jsonPrimitive?.booleanOrNull
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = value ?: false,
            onCheckedChange = { checked -> state.propertyEditor.updateBoolean(definition.id, checked) },
            enabled = editable,
        )
        Text(
            if (value == true) "是" else "否",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (value != null && editable) {
            MetadataInlineAction("清除", enabled = true) { state.propertyEditor.updateBoolean(definition.id, null) }
        }
    }
}

/**
 * 渲染元数据值使用的紧凑输入表面。
 *
 * @param value 当前文本。
 * @param placeholder 空值提示。
 * @param onValueChange 文本更新动作。
 * @param readOnly 是否禁止创建笔记覆盖值。
 * @param modifier 外部布局修饰符。
 */
@Composable
private fun MetadataTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 渲染属性值旁的低强调文字动作。
 *
 * @param label 动作文案。
 * @param enabled 是否响应点击。
 * @param onClick 点击动作。
 */
@Composable
internal fun MetadataInlineAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        label,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
    )
}

/**
 * 区分紧凑文本控件的提交校验策略。
 *
 * @property placeholder 对应格式提示。
 */
private enum class ValueValidationMode(val placeholder: String) {
    /** 有限数值。 */
    NUMBER("输入数字"),

    /** ISO 本地日期。 */
    DATE("YYYY-MM-DD"),

    /** ISO-8601 日期时间。 */
    DATETIME("ISO-8601"),
}
