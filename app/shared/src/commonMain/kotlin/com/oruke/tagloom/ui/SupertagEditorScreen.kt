package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/** 保存超级标签编辑意图，不在编辑过程中修改已确认定义。 */
internal data class SupertagEditRequest(
    /** 原定义或带稳定 ID 的新建草稿。 */
    val definition: TagDefinition,
    /** 保存成功后是否应用到发起编辑的笔记。 */
    val attachToSelected: Boolean = false,
)

/** 将笔记内维护入口路由到同一独立维护页，避免复制一套编辑与保存状态。 */
internal val LocalSupertagEditor = staticCompositionLocalOf<(TagDefinition, Boolean) -> Unit> { { _, _ -> } }

/**
 * 渲染超级标签完整维护表单，名称、图标、继承和属性都只在成功保存后生效。
 * @param state 当前笔记及 schema 目录。
 * @param request 正在编辑的定义与应用意图。
 * @param controller 维护事务状态。
 * @param onBack 取消未提交草稿并返回来源页面。
 * @param onSave 保存完整定义及本表单新建的属性。
 */
@Composable
internal fun SupertagEditorScreen(
    state: TagloomEditorState, request: SupertagEditRequest, controller: WorkspaceSupertagController,
    onBack: () -> Unit, onSave: (TagDefinition, List<PropertyDefinition>) -> Unit,
) {
    val original = request.definition
    val root = original.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    val creating = state.tagDefinitions.none { it.id == original.id }
    var name by remember(original.id) { mutableStateOf(if (creating && original.name == NEW_SUPERTAG_DRAFT_NAME) "" else original.name) }
    var icon by remember(original.id) { mutableStateOf(original.iconKey) }
    var parents by remember(original.id) { mutableStateOf(original.parentTagIds) }
    var bindings by remember(original.id) {
        mutableStateOf(
            original.propertyBindings.map { binding ->
                binding.toEditorDraft(state.propertyDefinitions.firstOrNull { it.id == binding.propertyId })
            }
        )
    }
    var newProperties by remember(original.id) { mutableStateOf(emptyList<PropertyDefinition>()) }
    var iconPicker by remember(original.id) { mutableStateOf(false) }
    var parentMenu by remember(original.id) { mutableStateOf(false) }
    var propertyMenu by remember(original.id) { mutableStateOf(false) }
    var newProperty by remember(original.id) { mutableStateOf(false) }
    var invalid by remember(original.id) { mutableStateOf(false) }
    val definitions = (state.propertyDefinitions + newProperties).distinctBy { it.id }
    val tags = state.tagDefinitions.toList()
    val propertyEditor = remember(original, parents, bindings, definitions, tags) {
        resolveTagBindingEditorProperties(original, parents, bindings, tags, definitions)
    }
    val duplicate = state.tagDefinitions.any { it.id != original.id && it.name.equals(name.trim(), true) }
    val busy = controller.busy
    val title = stringResource(
        when {
            root -> Res.string.editor_tag_view_default_type; creating -> Res.string.editor_tag_create; else -> Res.string.editor_tag_edit
        }
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack, enabled = !busy) {
                    Icon(
                        painterResource(Res.drawable.ic_arrow_back),
                        stringResource(Res.string.supertag_back)
                    )
                }
                Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
                if (!root) Button(
                    enabled = !busy && name.isNotBlank() && !duplicate && propertyEditor.canSave,
                    modifier = Modifier.testTag("supertag-save"),
                    onClick = {
                        runCatching {
                            state.buildTagDefinitionDraft(original, name, parents, bindings.map { draft ->
                                draft.toConfirmedBinding(definitions.single { it.id == draft.propertyId }, draft.order)
                            }, icon, definitions)
                        }.onSuccess {
                            onSave(
                                it,
                                newProperties.filter { property -> bindings.any { it.propertyId == property.id } })
                        }
                            .onFailure { invalid = true }
                    }) { Text(stringResource(if (busy) Res.string.supertag_saving else if (creating) Res.string.editor_tag_create_action else Res.string.workspace_action_save)) }
            }
            HorizontalDivider()
            // 保存按钮固定在顶部，失败反馈也保持可见，避免长表单滚动后看不到重试提示。
            if (invalid || controller.failed) Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().testTag("supertag-save-error")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                Text(
                    stringResource(Res.string.supertag_save_failed),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)
                    .testTag("supertag-editor-form"),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (root) Text(
                    stringResource(Res.string.editor_tag_root_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.supertag_basics), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        name,
                        { name = it; invalid = false },
                        Modifier.fillMaxWidth().testTag("supertag-name"),
                        label = { Text(stringResource(Res.string.editor_tag_name)) },
                        singleLine = true,
                        enabled = !root && !busy,
                        placeholder = { Text(stringResource(Res.string.supertag_name_placeholder)) },
                        isError = duplicate
                    )
                    if (duplicate) Text(
                        stringResource(Res.string.supertag_name_duplicate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(Res.string.supertag_icon), style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (icon == null) Text(
                            stringResource(Res.string.supertag_icon_none),
                            style = MaterialTheme.typography.bodySmall
                        )
                        else SupertagIcon(icon, Modifier.size(28.dp).testTag("supertag-icon-preview"))
                        if (!root) {
                            OutlinedButton(onClick = { iconPicker = true }, enabled = !busy) {
                                Text(stringResource(if (icon == null) Res.string.supertag_icon_choose else Res.string.supertag_icon_change))
                            }
                            if (icon != null) TextButton(onClick = { icon = null }, enabled = !busy) {
                                Text(
                                    stringResource(Res.string.supertag_icon_clear)
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(Res.string.supertag_icon_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                TagParentEditor(
                    state,
                    parents,
                    state.tagDefinitions.filterNot { it.id == original.id || it.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID },
                    parentMenu,
                    !root && !busy,
                    { parentMenu = it },
                    { id -> parents = if (id in parents) parents - id else parents + id; invalid = false })
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.editor_tag_properties), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.supertag_properties_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (root) ReadOnlyTypeProperties(bindings, definitions)
                    else {
                        SupertagInheritanceNotice(propertyEditor)
                        propertyEditor.rows.forEach { row ->
                            key(row.draft.propertyId) {
                                TagBindingDraftEditor(
                                    row = row,
                                    enabled = !busy,
                                    onChange = { updated ->
                                        // 重新选择当前值不算修改；解决冲突则必须明确创建本级覆盖。
                                        if (!busy && (row.conflicted || updated != row.draft)) {
                                            bindings = if (bindings.any { it.propertyId == updated.propertyId }) {
                                                bindings.map { if (it.propertyId == updated.propertyId) updated else it }
                                            } else bindings + updated
                                            invalid = false
                                        }
                                    },
                                    onRemove = {
                                        if (!busy) {
                                            bindings = bindings.filterNot { it.propertyId == row.draft.propertyId }
                                            invalid = false
                                        }
                                    })
                            }
                        }
                        if (propertyEditor.rows.isEmpty() && propertyEditor.inheritanceErrors.isEmpty()) Text(
                            stringResource(Res.string.supertag_properties_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box {
                            OutlinedButton(onClick = { propertyMenu = true }, enabled = !busy) {
                                Icon(
                                    painterResource(Res.drawable.ic_plus),
                                    null,
                                    Modifier.size(18.dp)
                                ); Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.supertag_add_property))
                            }
                            DropdownMenu(propertyMenu, { propertyMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.supertag_new_property)) },
                                    onClick = { propertyMenu = false; newProperty = true })
                                definitions.filterNot { property -> propertyEditor.rows.any { it.draft.propertyId == property.id } }
                                    .forEach { property ->
                                        DropdownMenuItem(text = { Text(property.name) }, onClick = {
                                            bindings += TagPropertyBinding(
                                                property.id,
                                                order = (propertyEditor.rows.maxOfOrNull { it.draft.order } ?: -1) + 1
                                            ).toEditorDraft(property)
                                            propertyMenu = false
                                        })
                                    }
                            }
                        }
                    }
                }
                if (!creating) {
                    HorizontalDivider()
                    SupertagUsage(state, original)
                }
                if (!root) TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.workspace_action_cancel))
                }
            }
        }
    }
    if (iconPicker) SupertagIconPicker(icon, { icon = it; iconPicker = false }, { iconPicker = false })
    if (newProperty) PropertyDefinitionDialog(
        request = PropertyDialogRequest(null, ""),
        tagDefinitions = emptyList(),
        fixedDestination = PropertyDefinitionDestination.Workspace,
        onDismiss = { newProperty = false },
        onConfirm = { propertyName, type, mode, options, hierarchical, _ ->
            val property = state.buildPropertyDefinitionDraft(null, propertyName, type, mode, options, hierarchical)
            newProperties += property
            bindings += TagPropertyBinding(
                property.id,
                order = (propertyEditor.rows.maxOfOrNull { it.draft.order } ?: -1) + 1,
            ).toEditorDraft(property)
            newProperty = false
        },
    )
}

/**
 * 用自然语言和可展开名称列表解释超级标签引用，引用存在时明确删除限制。
 * @param state 笔记和继承目录。
 * @param tag 被维护的超级标签。
 */
@Composable
private fun SupertagUsage(state: TagloomEditorState, tag: TagDefinition) {
    val notes = state.documents.filter { state.referencesSupertag(it.note, tag.id) }
    val children = state.tagDefinitions.filter { tag.id in it.parentTagIds }
    var expanded by remember(tag.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.supertag_references), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    Res.string.supertag_reference_summary,
                    notes.size,
                    children.size
                )
            )
        }
        if (expanded) {
            notes.forEach { Text(it.note.title, style = MaterialTheme.typography.bodySmall) }
            children.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall) }
        }
        if (notes.isNotEmpty() || children.isNotEmpty()) Text(
            stringResource(Res.string.supertag_delete_blocked),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
