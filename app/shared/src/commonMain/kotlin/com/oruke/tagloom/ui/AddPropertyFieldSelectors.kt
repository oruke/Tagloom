package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.objectTypeState
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染属性类型单选菜单。
 *
 * @param value 当前类型。
 * @param onValueChange 选择动作。
 */
@Composable
internal fun PropertyTypeSelector(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PopoverSelectValue(value.localizedDisplayName()) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            CREATE_PROPERTY_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedDisplayName()) },
                    leadingIcon = { Text(if (type == value) "✓" else "") },
                    onClick = {
                        expanded = false
                        onValueChange(type)
                    },
                )
            }
        }
    }
}

/**
 * 渲染固定选项或自由输入的单选菜单。
 *
 * @param value 当前输入模式。
 * @param onValueChange 选择动作。
 */
@Composable
internal fun OptionModeSelector(
    value: PropertyOptionInputMode,
    onValueChange: (PropertyOptionInputMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PopoverSelectValue(value.localizedDisplayName()) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PropertyOptionInputMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.localizedDisplayName()) },
                    leadingIcon = { Text(if (mode == value) "✓" else "") },
                    onClick = {
                        expanded = false
                        onValueChange(mode)
                    },
                )
            }
        }
    }
}

/**
 * 渲染属性绑定目标单选菜单。
 *
 * @param state 当前对象类型目录。
 * @param document 当前笔记。
 * @param value 当前目标。
 * @param onValueChange 选择动作。
 */
@Composable
internal fun PropertyDestinationSelector(
    state: TagloomEditorState,
    document: EditorDocument,
    value: PropertyDefinitionDestination,
    onValueChange: (PropertyDefinitionDestination) -> Unit,
) {
    val typeId = document.note.objectTypeState(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).appliedTypeId
    val typeName = typeId?.let { id -> state.tagDefinitions.firstOrNull { definition -> definition.id == id }?.name }
    val currentNoteLabel = stringResource(Res.string.editor_property_current_note)
    val typeDestinationLabel = if (typeName == null) {
        null
    } else {
        stringResource(Res.string.editor_property_destination_type, typeName)
    }
    val destinations = buildList {
        add(PropertyDefinitionDestination.CurrentNote to currentNoteLabel)
        if (typeId != null && typeDestinationLabel != null) {
            add(PropertyDefinitionDestination.Tag(typeId) to typeDestinationLabel)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val label = destinations.firstOrNull { (destination, _) -> destination == value }?.second
        ?: currentNoteLabel
    Box {
        PopoverSelectValue(label) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            destinations.forEach { (destination, destinationLabel) ->
                DropdownMenuItem(
                    text = { Text(destinationLabel) },
                    leadingIcon = { Text(if (destination == value) "✓" else "") },
                    onClick = {
                        expanded = false
                        onValueChange(destination)
                    },
                )
            }
        }
    }
}

/**
 * 渲染带下拉提示的紧凑只读选择表面。
 *
 * @param label 当前选择。
 * @param onClick 打开菜单动作。
 */
@Composable
private fun PopoverSelectValue(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TagloomChevronDown(Modifier.size(12.dp))
        }
    }
}

/** 编辑器允许从渐进式浮层创建的基础属性类型。 */
private val CREATE_PROPERTY_TYPES: List<String> = listOf(
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

/**
 * 返回选项输入模式在当前 Locale 下的展示名称。
 *
 * @return 自由输入或固定选项对应的资源文本。
 */
@Composable
private fun PropertyOptionInputMode.localizedDisplayName(): String = when (this) {
    PropertyOptionInputMode.FREE -> stringResource(Res.string.editor_property_option_free)
    PropertyOptionInputMode.FIXED -> stringResource(Res.string.editor_property_option_fixed)
}
