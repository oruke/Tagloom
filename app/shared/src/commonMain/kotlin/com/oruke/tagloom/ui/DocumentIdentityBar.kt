package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.objectTypeState
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.resolveEffectiveTags
import com.oruke.tagloom.editor.setAppliedObjectType
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染标题正下方的对象身份、层级路径与普通标签。
 *
 * @param state 当前编辑器及工作区 schema。
 * @param document 当前笔记。
 * @param onCreateType 请求创建并应用一个对象类型。
 * @param onOpenType 请求查看或编辑对象类型定义。
 * @param onCreateTag 请求持久化并选择一个自由普通标签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentIdentityBar(
    state: TagloomEditorState,
    document: EditorDocument,
    onCreateType: (String) -> Unit,
    onOpenType: (TagDefinition) -> Unit,
    onCreateTag: (PropertyId, String) -> Unit,
) {
    var showHierarchy by remember(document.id) { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HierarchyToggle(
            expanded = showHierarchy,
            onClick = { showHierarchy = !showHierarchy },
        )
        ObjectTypeSelector(
            state = state,
            document = document,
            showHierarchy = showHierarchy,
            onCreate = onCreateType,
            onOpenDefinition = onOpenType,
        )
        OrdinaryTagSelector(
            state = state,
            document = document,
            onCreateTag = onCreateTag,
        )
    }
}

/**
 * 渲染对象类型父级路径的开关图标。
 *
 * @param expanded 当前是否展示父级路径。
 * @param onClick 切换动作。
 */
@Composable
private fun HierarchyToggle(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val iconColor = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val hierarchyDescription = if (expanded) {
        stringResource(Res.string.editor_identity_collapse_hierarchy)
    } else {
        stringResource(Res.string.editor_identity_expand_hierarchy)
    }
    Surface(
        modifier = Modifier
            .size(28.dp)
            .semantics {
                contentDescription = hierarchyDescription
                role = Role.Button
            }
            .clickable(onClick = onClick),
        color = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Canvas(Modifier.padding(7.dp)) {
            val stroke = 1.35.dp.toPx()
            drawLine(iconColor, start = center.copy(x = size.width * 0.28f), end = center.copy(x = size.width * 0.72f), strokeWidth = stroke)
            drawLine(
                iconColor,
                start = center.copy(x = size.width * 0.28f),
                end = center.copy(x = size.width * 0.72f, y = size.height * 0.24f),
                strokeWidth = stroke,
            )
            drawLine(
                iconColor,
                start = center.copy(x = size.width * 0.28f),
                end = center.copy(x = size.width * 0.72f, y = size.height * 0.76f),
                strokeWidth = stroke,
            )
            drawCircle(iconColor, radius = 1.8.dp.toPx(), center = center.copy(x = size.width * 0.22f))
            drawCircle(iconColor, radius = 1.8.dp.toPx(), center = center.copy(x = size.width * 0.78f, y = size.height * 0.22f))
            drawCircle(iconColor, radius = 1.8.dp.toPx(), center = center.copy(x = size.width * 0.78f, y = size.height * 0.78f))
        }
    }
}

/**
 * 渲染唯一应用类型及其单选搜索菜单。
 *
 * @param state 当前编辑器及类型目录。
 * @param document 当前笔记。
 * @param showHierarchy 是否显示完整有效父级路径。
 * @param onCreate 请求创建类型。
 * @param onOpenDefinition 请求查看或编辑类型定义。
 */
@Composable
private fun ObjectTypeSelector(
    state: TagloomEditorState,
    document: EditorDocument,
    showHierarchy: Boolean,
    onCreate: (String) -> Unit,
    onOpenDefinition: (TagDefinition) -> Unit,
) {
    val rootType = state.tagDefinitions.firstOrNull { definition ->
        definition.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    } ?: WorkspaceDefaultSchemas.noteTagDefinition()
    val typeState = document.note.objectTypeState(rootType.id)
    val appliedType = typeState.appliedTypeId?.let { appliedId ->
        state.tagDefinitions.firstOrNull { definition -> definition.id == appliedId }
    }
    val label = when {
        typeState.requiresResolution -> stringResource(
            Res.string.editor_identity_resolve_type,
            typeState.candidateTypeIds.size,
        )
        showHierarchy && appliedType != null -> state.objectTypePath(appliedType.id)
        else -> appliedType?.name ?: rootType.name
    }
    var expanded by remember(document.id) { mutableStateOf(false) }
    var query by remember(document.id) { mutableStateOf("") }

    Box {
        Surface(
            modifier = Modifier.clickable { expanded = true },
            color = if (typeState.requiresResolution) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (typeState.requiresResolution) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("◇", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelMedium)
                TagloomChevronDown(Modifier.size(10.dp))
            }
        }
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
        ) {
            PopoverSearchField(
                value = query,
                placeholder = stringResource(Res.string.editor_identity_search_object_types),
                requestFocus = expanded,
                onValueChange = { value -> query = value },
            )
            val candidates = (listOf(rootType) + state.tagDefinitions.filterNot { definition -> definition.id == rootType.id })
                .distinctBy(TagDefinition::id)
                .filter { definition -> query.isBlank() || definition.name.contains(query, ignoreCase = true) }
            candidates.forEach { definition ->
                val selected = if (definition.id == rootType.id) {
                    typeState.appliedTypeId == null && !typeState.requiresResolution
                } else {
                    definition.id == typeState.appliedTypeId
                }
                TagloomMenuItem(
                    text = definition.name,
                    leadingIcon = if (selected) {
                        { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    } else {
                        { Text("◇", style = MaterialTheme.typography.labelMedium) }
                    },
                    trailingText = when {
                        definition.id == rootType.id -> {
                            stringResource(Res.string.editor_identity_root_type_readonly)
                        }
                        selected -> stringResource(Res.string.editor_identity_applied)
                        else -> null
                    },
                    trailingIcon = {
                        Text(
                            if (definition.id == rootType.id) {
                                stringResource(Res.string.editor_action_view)
                            } else {
                                stringResource(Res.string.editor_action_edit)
                            },
                            modifier = Modifier.clickable {
                                expanded = false
                                query = ""
                                onOpenDefinition(definition)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    selected = selected,
                    onClick = {
                        state.setAppliedObjectType(definition.id.takeUnless { id -> id == rootType.id })
                        expanded = false
                        query = ""
                    },
                )
            }
            TagloomMenuDivider()
            val normalized = query.trim()
            TagloomMenuItem(
                text = if (normalized.isEmpty()) {
                    stringResource(Res.string.editor_identity_create_object_type)
                } else {
                    stringResource(Res.string.editor_identity_create_named_object_type, normalized)
                },
                leadingIcon = { Text("+", style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    expanded = false
                    query = ""
                    onCreate(normalized)
                },
            )
        }
    }
}

/**
 * 按稳定继承顺序生成应用类型的父级展示路径。
 *
 * @param appliedTypeId 唯一应用类型 ID。
 * @return 可在身份 chip 中直接显示的路径；解析失败时回退到类型名称。
 */
private fun TagloomEditorState.objectTypePath(appliedTypeId: TagId): String {
    val resolution = resolveEffectiveTags(listOf(appliedTypeId))
    if (resolution !is TagResolution.Success) {
        return tagDefinitions.firstOrNull { definition -> definition.id == appliedTypeId }?.name ?: appliedTypeId.value
    }
    val namesById = tagDefinitions.associate { definition -> definition.id to definition.name }
    return resolution.tags.joinToString(" › ") { resolved -> namesById[resolved.tagId] ?: resolved.tagId.value }
}

/**
 * 渲染普通标签 chip 与自由多选菜单。
 *
 * @param state 当前编辑器及普通标签目录。
 * @param document 当前笔记。
 * @param onCreateTag 请求先持久化再选择新标签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrdinaryTagSelector(
    state: TagloomEditorState,
    document: EditorDocument,
    onCreateTag: (PropertyId, String) -> Unit,
) {
    val definition = state.propertyDefinitions.firstOrNull { property ->
        property.id == WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID
    } ?: WorkspaceDefaultSchemas.ordinaryTagsPropertyDefinition()
    val selectedIds = state.propertyEditor.stringListValue(document.note, definition.id)
    var expanded by remember(document.id) { mutableStateOf(false) }
    var query by remember(document.id) { mutableStateOf("") }
    Box {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            selectedIds.forEach { optionId ->
                val label = definition.options.firstOrNull { option -> option.id == optionId }?.label
                    ?: optionId.take(12)
                OrdinaryTagChip(label = label, onClick = { expanded = true })
            }
            IdentityAddButton(
                compact = selectedIds.isNotEmpty(),
                onClick = { expanded = true },
            )
        }
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
        ) {
            PopoverSearchField(
                value = query,
                placeholder = stringResource(Res.string.editor_identity_search_or_enter_tag),
                requestFocus = expanded,
                onValueChange = { value -> query = value },
            )
            val selectedSet = selectedIds.toSet()
            val candidates = definition.options.filter { option ->
                query.isBlank() || option.label.contains(query, ignoreCase = true)
            }
            candidates.forEach { option ->
                val selected = option.id in selectedSet
                TagloomMenuItem(
                    text = "#${option.label}",
                    leadingIcon = if (selected) {
                        { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    } else {
                        { Text("#", style = MaterialTheme.typography.labelMedium) }
                    },
                    selected = selected,
                    onClick = {
                        val next = if (selected) selectedSet - option.id else selectedSet + option.id
                        state.propertyEditor.updateMultiSelect(definition.id, next.sorted())
                    },
                )
            }
            val normalized = query.trim().removePrefix("#").trim()
            val canCreate = definition.optionInputMode == PropertyOptionInputMode.FREE &&
                normalized.isNotEmpty() && definition.options.none { option ->
                    option.label.equals(normalized, ignoreCase = true)
                }
            if (canCreate) {
                TagloomMenuDivider()
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_identity_create_tag, normalized),
                    leadingIcon = { Text("+", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        query = ""
                        onCreateTag(definition.id, normalized)
                    },
                )
            }
            if (candidates.isEmpty() && !canCreate && query.isNotBlank()) {
                TagloomMenuItem(
                    text = stringResource(Res.string.editor_identity_no_matching_tags),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

/**
 * 渲染不带删除叉号的中性普通标签 chip，移除动作统一在多选菜单中完成。
 *
 * @param label 标签显示名。
 * @param onClick 打开标签选择菜单。
 */
@Composable
private fun OrdinaryTagChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = "#$label",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 渲染标签组末尾的添加入口。
 *
 * @param compact 已有标签时只显示加号。
 * @param onClick 打开多选菜单。
 */
@Composable
private fun IdentityAddButton(
    compact: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (compact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (compact) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
        border = if (compact) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = if (compact) "+" else stringResource(Res.string.editor_identity_add_tag),
            modifier = Modifier.padding(horizontal = if (compact) 9.dp else 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 渲染浮层顶部可持续输入并自动聚焦的搜索框。
 *
 * @param value 当前查询。
 * @param placeholder 空查询提示。
 * @param requestFocus 浮层打开后是否请求输入焦点。
 * @param onValueChange 查询更新动作。
 */
@Composable
internal fun PopoverSearchField(
    value: String,
    placeholder: String,
    requestFocus: Boolean,
    onValueChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }
    Surface(
        modifier = Modifier.width(320.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).padding(horizontal = 10.dp, vertical = 8.dp),
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
