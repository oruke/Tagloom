package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewId
import com.oruke.tagloom.core.model.ViewPresentationKinds
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_view_gallery
import tagloom.app.shared.generated.resources.database_view_kanban
import tagloom.app.shared.generated.resources.database_view_layout
import tagloom.app.shared.generated.resources.database_view_name
import tagloom.app.shared.generated.resources.database_view_table
import tagloom.app.shared.generated.resources.workspace_action_save

/**
 * 渲染 Database 视图切换条。
 *
 * 当前视图再次点击会打开视图设置，可直接切换表格、看板和画廊布局；加号只负责创建另一个独立视图。
 * 紧凑窗口增大命中区域并保留横向滚动，避免移动端隐藏任何视图。
 *
 * @param controller Database 目录控制器。
 * @param databaseId 当前 Database ID。
 * @param selectedViewId 当前 Database Block 会话中选择的 View ID。
 * @param compact 是否使用触控优先尺寸。
 * @param onSelectView 更新当前 Block 会话 View 的动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun DatabaseViewTabs(
    controller: DatabaseEditorController,
    databaseId: DatabaseId,
    selectedViewId: ViewId,
    compact: Boolean,
    onSelectView: (ViewId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ownViews = controller.viewsFor(databaseId)
    val selectedView = ownViews.firstOrNull { view -> view.id == selectedViewId } ?: return
    val scope = rememberCoroutineScope()
    var createMenuOpen by remember(databaseId) { mutableStateOf(false) }
    var settingsViewId by remember(databaseId) { mutableStateOf<ViewId?>(null) }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 40.dp else 38.dp)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ownViews.forEach { view ->
            val selected = view.id == selectedView.id
            Box {
                Column(
                    modifier = Modifier.heightIn(min = if (compact) 40.dp else 38.dp)
                        .clickable {
                            if (selected) settingsViewId = view.id else onSelectView(view.id)
                        }
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            Text(
                                datasetViewKindIcon(view.presentation.kind),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            view.name,
                            modifier = if (selected) Modifier.padding(start = 6.dp) else Modifier,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (selected) {
                            TagloomChevronDown(Modifier.size(11.dp).padding(start = 4.dp))
                        }
                    }
                    Box(
                        Modifier.heightIn(min = 2.dp).widthIn(min = 42.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
                    )
                }
                DatabaseViewSettingsMenu(
                    controller = controller,
                    view = view,
                    compact = compact,
                    expanded = settingsViewId == view.id,
                    onDismiss = { settingsViewId = null },
                )
            }
        }
        Box {
            Text(
                "+",
                modifier = Modifier.heightIn(min = if (compact) 40.dp else 34.dp)
                    .clickable { createMenuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TagloomDropdownMenu(
                expanded = createMenuOpen,
                onDismissRequest = { createMenuOpen = false },
            ) {
                datasetViewKinds(controller, databaseId).forEach { choice ->
                    TagloomMenuItem(
                        text = choice.label,
                        leadingIcon = { Text(datasetViewKindIcon(choice.kind), style = MaterialTheme.typography.bodyMedium) },
                        enabled = choice.enabled,
                        onClick = {
                            createMenuOpen = false
                            scope.launch {
                                val newId = controller.createView(
                                    databaseId = databaseId,
                                    currentViewId = selectedView.id,
                                    name = choice.label,
                                ) ?: return@launch
                                controller.updateView(newId) { current ->
                                    current.withDatasetPresentationKind(
                                        choice.kind,
                                        controller.propertyDefinitions(databaseId),
                                    )
                                }
                                onSelectView(newId)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 渲染当前视图的重命名和显示布局设置。
 *
 * @param controller Database 目录控制器。
 * @param view 当前视图。
 * @param compact 是否使用移动端底部面板。
 * @param expanded 菜单是否展开。
 * @param onDismiss 关闭菜单动作。
 */
@Composable
private fun DatabaseViewSettingsMenu(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    compact: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(view.id, view.name) { mutableStateOf(view.name) }
    val content: @Composable () -> Unit = {
        DatabaseViewSettingsContent(
            controller = controller,
            view = view,
            draft = draft,
            onDraftChange = { value -> draft = value },
            onSaveName = {
                scope.launch {
                    controller.updateView(view.id) { current -> current.copy(name = draft.ifBlank { current.name }) }
                }
                onDismiss()
            },
            onSelectKind = { kind ->
                scope.launch {
                    controller.updateView(view.id) { current ->
                        current.withDatasetPresentationKind(
                            kind,
                            controller.propertyDefinitions(view.databaseId),
                        )
                    }
                }
                onDismiss()
            },
        )
    }
    if (compact && expanded) {
        Dialog(onDismissRequest = onDismiss) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    content()
                }
            }
        }
    } else {
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(min = 260.dp),
        ) {
            content()
        }
    }
}

/**
 * 渲染桌面弹层与移动端底部面板共用的视图设置内容。
 *
 * @param controller Database 目录控制器。
 * @param view 当前视图。
 * @param draft 当前名称草稿。
 * @param onDraftChange 名称变化动作。
 * @param onSaveName 保存名称动作。
 * @param onSelectKind 选择显示布局动作。
 */
@Composable
private fun DatabaseViewSettingsContent(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onSelectKind: (String) -> Unit,
) {
    Column(Modifier.widthIn(min = 260.dp).padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.database_view_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        MaterialTheme.shapes.small,
                    ).padding(horizontal = 10.dp, vertical = 10.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                Text(
                    stringResource(Res.string.workspace_action_save),
                    modifier = Modifier.clickable(onClick = onSaveName).padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TagloomMenuDivider()
        TagloomMenuSectionHeader(title = stringResource(Res.string.database_view_layout))
        datasetViewKinds(controller, view.databaseId).forEach { choice ->
            val selected = view.presentation.kind == choice.kind
            TagloomMenuItem(
                text = choice.label,
                leadingIcon = { Text(datasetViewKindIcon(choice.kind), style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = if (selected) {
                    { Text("✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                } else null,
                selected = selected,
                enabled = choice.enabled,
                onClick = { onSelectKind(choice.kind) },
            )
        }
    }
}

/** 描述视图布局菜单中的一个选择。 */
private data class DatasetViewKindChoice(
    /** 稳定展示类型。 */
    val kind: String,
    /** 用户可见名称。 */
    val label: String,
    /** 当前数据结构是否允许选择。 */
    val enabled: Boolean,
)

/**
 * 返回当前数据集支持的展示布局。
 *
 * @param controller Database 目录控制器。
 * @param databaseId 当前 Database ID。
 * @return 表格、看板和画廊选择；缺少单选属性时禁用看板。
 */
@Composable
private fun datasetViewKinds(
    controller: DatabaseEditorController,
    databaseId: DatabaseId,
): List<DatasetViewKindChoice> = listOf(
    DatasetViewKindChoice(ViewPresentationKinds.TABLE, stringResource(Res.string.database_view_table), true),
    DatasetViewKindChoice(
        ViewPresentationKinds.KANBAN,
        stringResource(Res.string.database_view_kanban),
        controller.propertyDefinitions(databaseId).any { property -> property.type == PropertyTypes.SELECT },
    ),
    DatasetViewKindChoice(ViewPresentationKinds.GALLERY, stringResource(Res.string.database_view_gallery), true),
)

/**
 * 返回视图展示类型对应的紧凑图标。
 *
 * @param kind 稳定展示类型。
 * @return 表格、看板或画廊图标文本。
 */
private fun datasetViewKindIcon(kind: String): String = when (kind) {
    ViewPresentationKinds.KANBAN -> "▥"
    ViewPresentationKinds.GALLERY -> "▦"
    else -> "▤"
}
