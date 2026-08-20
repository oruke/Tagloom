package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewPresentationKinds
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_fields
import tagloom.app.shared.generated.resources.database_new_row
import tagloom.app.shared.generated.resources.database_operation_failed
import tagloom.app.shared.generated.resources.database_unknown_view
import tagloom.app.shared.generated.resources.editor_block_action_move_down
import tagloom.app.shared.generated.resources.editor_block_action_move_up

/**
 * 渲染响应式 Dataset Block。
 *
 * 数据集名称与来源属于身份区；过滤、字段和新建属于动作区。宽屏时共处一行，紧凑窗口中动作区换到
 * 可横向滚动的触控工具条，避免压缩标题或隐藏核心能力。
 *
 * @param controller Database 目录和写入控制器。
 * @param databaseId 当前 Database Block 引用的稳定 ID。
 * @param onOpenNote 从投影行打开原始 Note。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun DatabaseViewPane(
    controller: DatabaseEditorController,
    databaseId: DatabaseId,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val database = controller.database(databaseId) ?: return
    val ownViews = controller.viewsFor(databaseId)
    var selectedViewId by remember(database.id) { mutableStateOf(database.defaultViewId) }
    val view = ownViews.firstOrNull { item -> item.id == selectedViewId } ?: ownViews.firstOrNull() ?: return
    val projection = controller.projection(databaseId, view.id) ?: return
    val scope = rememberCoroutineScope()
    var nameDraft by remember(database.id, database.name) { mutableStateOf(database.name) }
    val sourceName = controller.tagDefinitions().firstOrNull { tag -> tag.id == database.sourceTypeId }?.name
        ?: database.sourceTypeId.value

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < DATASET_COMPACT_BREAKPOINT
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(6.dp),
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 46.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▦", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { value -> nameDraft = value },
                    modifier = Modifier.weight(1f).onFocusChanged { focus ->
                        if (!focus.isFocused && nameDraft.isNotBlank() && nameDraft != database.name) {
                            scope.launch {
                                controller.updateDatabase(database.id) { current -> current.copy(name = nameDraft.trim()) }
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                DatabaseSourceSelector(controller, database, sourceName, enabled = !controller.busy)
                if (!compact) {
                    DatasetToolbar(controller = controller, view = view, compact = false)
                }
            }
            if (compact) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DatasetToolbar(controller = controller, view = view, compact = true)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DatabaseViewTabs(
                controller = controller,
                databaseId = database.id,
                selectedViewId = view.id,
                compact = compact,
                onSelectView = { viewId -> selectedViewId = viewId },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (controller.operationFailed) {
                Text(
                    stringResource(Res.string.database_operation_failed),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (view.presentation.kind) {
                ViewPresentationKinds.TABLE -> DatabaseTable(
                    controller = controller,
                    projection = projection,
                    compact = compact,
                    onOpenNote = onOpenNote,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewPresentationKinds.KANBAN -> DatabaseKanban(
                    controller,
                    projection,
                    onOpenNote,
                    Modifier.fillMaxWidth().padding(12.dp),
                )
                ViewPresentationKinds.GALLERY -> DatabaseGallery(
                    projection,
                    onOpenNote,
                    Modifier.fillMaxWidth().padding(12.dp),
                )
                else -> Text(stringResource(Res.string.database_unknown_view), modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/**
 * 渲染过滤、字段显隐和新建对象组成的数据集动作区。
 *
 * @param controller Database 目录和写入控制器。
 * @param view 当前视图定义。
 * @param compact 是否使用移动端触控尺寸。
 */
@Composable
private fun DatasetToolbar(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    compact: Boolean,
) {
    val scope = rememberCoroutineScope()
    var fieldMenuOpen by remember(view.id) { mutableStateOf(false) }
    val onToggleField: (FieldRef) -> Unit = { field ->
        scope.launch { controller.updateView(view.id) { current -> current.toggledDatasetField(field) } }
    }
    val onMoveField: (FieldRef, Int) -> Unit = { field, offset ->
        scope.launch {
            controller.updateView(view.id) { current -> current.movedDatasetVisiblePropertyField(field, offset) }
        }
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DatasetFilterButton(controller = controller, view = view, compact = compact)
        Box {
            DatasetToolbarAction(
                label = "▥  ${stringResource(Res.string.database_fields)}",
                compact = compact,
                onClick = { fieldMenuOpen = true },
            )
            TagloomDropdownMenu(
                expanded = fieldMenuOpen && !compact,
                onDismissRequest = { fieldMenuOpen = false },
            ) {
                DatasetFieldMenuContent(controller, view, onToggleField, onMoveField)
            }
        }
        Box(
            modifier = Modifier.heightIn(min = if (compact) 40.dp else 34.dp)
                .clickable(enabled = !controller.busy) {
                    scope.launch { controller.createRow(view.databaseId) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.heightIn(min = if (compact) 32.dp else 30.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+ ${stringResource(Res.string.database_new_row)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
    if (compact && fieldMenuOpen) {
        Dialog(onDismissRequest = { fieldMenuOpen = false }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    DatasetFieldMenuContent(controller, view, onToggleField, onMoveField)
                }
            }
        }
    }
}

/**
 * 渲染桌面弹层与移动端底部面板共用的字段选择和排序面板。
 *
 * @param controller Database 目录控制器。
 * @param view 当前视图定义。
 * @param onToggleField 切换字段可见性动作。
 * @param onMoveField 按可见属性顺序上移或下移字段的动作。
 */
@Composable
private fun DatasetFieldMenuContent(
    controller: DatabaseEditorController,
    view: ViewDefinition,
    onToggleField: (FieldRef) -> Unit,
    onMoveField: (FieldRef, Int) -> Unit,
) {
    val propertyFields = controller.propertyDefinitions(view.databaseId).map { property ->
        FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id) to property.name
    }
    val labels = propertyFields.toMap()
    val visibleFields = view.presentation.fields.mapNotNull { configured ->
        configured.field.takeIf { field -> configured.visible && field in labels }
    }
    val orderedFields = visibleFields + propertyFields
        .map { propertyField -> propertyField.first }
        .filterNot(visibleFields::contains)
    Column(Modifier.widthIn(min = 280.dp).padding(vertical = 4.dp)) {
        TagloomMenuSectionHeader(title = stringResource(Res.string.database_fields))
        TagloomMenuDivider()
        orderedFields.forEach { field ->
            val visibleIndex = visibleFields.indexOf(field)
            DatasetFieldMenuRow(
                label = labels.getValue(field),
                visible = visibleIndex >= 0,
                canMoveUp = visibleIndex > 0,
                canMoveDown = visibleIndex >= 0 && visibleIndex < visibleFields.lastIndex,
                onToggle = { onToggleField(field) },
                onMoveUp = { onMoveField(field, -1) },
                onMoveDown = { onMoveField(field, 1) },
            )
        }
    }
}

/**
 * 渲染一个可选择并可按按钮排序的属性字段行。
 *
 * @param label 属性字段名称。
 * @param visible 字段当前是否显示。
 * @param canMoveUp 当前字段是否允许上移。
 * @param canMoveDown 当前字段是否允许下移。
 * @param onToggle 切换字段显示状态的动作。
 * @param onMoveUp 上移字段动作。
 * @param onMoveDown 下移字段动作。
 */
@Composable
private fun DatasetFieldMenuRow(
    label: String,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    TagloomMenuItem(
        onClick = onToggle,
        selected = visible,
        leadingIcon = {
            Text(
                if (visible) "✓" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingIcon = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DatasetFieldMoveAction(
                    label = stringResource(Res.string.editor_block_action_move_up),
                    enabled = visible && canMoveUp,
                    onClick = onMoveUp,
                )
                DatasetFieldMoveAction(
                    label = stringResource(Res.string.editor_block_action_move_down),
                    enabled = visible && canMoveDown,
                    onClick = onMoveDown,
                )
            }
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 渲染字段面板内的单个排序动作。
 *
 * @param label 本地化动作名称。
 * @param enabled 是否允许执行动作。
 * @param onClick 执行排序的动作。
 */
@Composable
private fun DatasetFieldMoveAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.heightIn(min = 28.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

/** 数据集切换为触控优先布局的宽度阈值。 */
private val DATASET_COMPACT_BREAKPOINT = 680.dp
