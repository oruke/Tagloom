package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 桌面锚定、窄窗口底部展示的共享层级单选；名称选择与展开分别响应。
 * @param state 当前目录和笔记草稿；数据集只编辑值时为空，不展示目录维护入口。
 * @param definition 具有层级能力的单选定义。
 * @param selectedId 当前选中身份，空表示无值。
 * @param onSelect 选择本级或清空。
 * @param onDismiss 关闭选择器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HierarchicalOptionPicker(
    state: TagloomEditorState?,
    definition: PropertyDefinition,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    var manage by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    if ((manage || create) && state != null) {
        PropertyOptionManagementDialog(
            state, definition, startCreating = create,
            onCreated = { id -> onSelect(id) }, onDismiss = { manage = false; create = false; onDismiss() })
    } else if (windowWidth < 720.dp) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            HierarchicalPickerContent(
                definition,
                selectedId,
                onSelect,
                onDismiss,
                { create = true },
                { manage = true },
                Modifier.fillMaxWidth(),
                state != null
            )
        }
    } else {
        TagloomDropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.width(344.dp)) {
            HierarchicalPickerContent(
                definition,
                selectedId,
                onSelect,
                onDismiss,
                { create = true },
                { manage = true },
                Modifier.fillMaxWidth(),
                state != null
            )
        }
    }
}

/**
 * 渲染共享目录、路径搜索和键盘游标，打开时展开当前值祖先并定位选中项。
 * @param definition 完整目录定义。
 * @param selectedId 当前实际值。
 * @param onSelect 确认选择或无值。
 * @param onDismiss 取消选择。
 * @param onCreate 打开新建分组。
 * @param onManage 打开完整目录维护。
 * @param modifier 宿主宽度。
 * @param maintenance 宿主是否提供真实目录维护能力。
 */
@Composable
private fun HierarchicalPickerContent(
    definition: PropertyDefinition,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier,
    maintenance: Boolean
) {
    var query by remember { mutableStateOf("") }
    var expanded by remember(definition.id) {
        mutableStateOf(
            PropertyOptionTree.path(definition.options, selectedId).dropLast(1).map { it.id }.toSet()
        )
    }
    var focused by remember { mutableStateOf(selectedId) }
    val rows = definition.options.filter { option ->
        if (query.isNotBlank()) PropertyOptionTree.label(definition.options, option.id).contains(query, true)
        else PropertyOptionTree.path(definition.options, option.id).dropLast(1).all { it.id in expanded }
    }.sortedBy { option ->
        PropertyOptionTree.path(definition.options, option.id)
            .joinToString("\u0000") { node -> definition.options.indexOf(node).toString().padStart(6, '0') }
    }
    val searchFocus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    // 游标与已保存值独立；每次键盘移动或搜索后，先等新行挂载，再确保游标处于可见范围。
    LaunchedEffect(focused, query, rows.map { it.id }) {
        if (focused == null || rows.any { it.id == focused }) {
            withFrameNanos { }
            bring.bringIntoView()
        }
    }
    val searchLabel = stringResource(Res.string.option_search_path)
    Column(modifier.testTag("hierarchy-picker").padding(8.dp).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ids = listOf<String?>(null) + rows.map { it.id }
        val index = ids.indexOf(focused).coerceAtLeast(0)
        when (event.key) {
            Key.Escape -> {
                onDismiss(); true
            }

            Key.DirectionDown -> {
                focused = ids[(index + 1).coerceAtMost(ids.lastIndex)]; true
            }

            Key.DirectionUp -> {
                focused = ids[(index - 1).coerceAtLeast(0)]; true
            }

            Key.DirectionRight -> {
                focused?.let { expanded = expanded + it }; true
            }

            Key.DirectionLeft -> {
                focused?.let { id ->
                    if (id in expanded) expanded = expanded - id else focused =
                        definition.options.firstOrNull { it.id == id }?.parentOptionId
                }; true
            }

            Key.Enter -> {
                onSelect(focused); true
            }

            else -> false
        }
    }) {
        Text(
            stringResource(Res.string.option_choose),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(6.dp)
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painterResource(Res.drawable.ic_search), null, Modifier.size(18.dp))
                BasicTextField(
                    query,
                    { query = it; focused = null },
                    Modifier.weight(1f).focusRequester(searchFocus).testTag("hierarchy-search")
                        .semantics { contentDescription = searchLabel },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text(
                                searchLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ); inner()
                        }
                    })
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(scroll).padding(vertical = 6.dp)) {
            HierarchyChoiceRow(
                stringResource(Res.string.link_ungrouped),
                selectedId == null,
                focused == null,
                0,
                (if (focused == null) Modifier.bringIntoViewRequester(bring) else Modifier)
                    .testTag("hierarchy-choice-none"),
                { onSelect(null) })
            rows.forEach { option ->
                val path = PropertyOptionTree.path(definition.options, option.id)
                val label = if (query.isBlank()) option.label else path.joinToString(" / ") { it.label }
                val focusedModifier = if (option.id == focused) Modifier.bringIntoViewRequester(bring) else Modifier
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HierarchyChoiceRow(
                        label, option.id == selectedId, option.id == focused, if (query.isBlank()) path.size - 1 else 0,
                        focusedModifier.weight(1f).testTag("hierarchy-choice-${option.id}"), { onSelect(option.id) })
                    if (query.isBlank() && definition.options.any { it.parentOptionId == option.id }) {
                        IconButton(onClick = {
                            expanded = if (option.id in expanded) expanded - option.id else expanded + option.id
                        }, Modifier.size(36.dp).testTag("hierarchy-expand-${option.id}")) {
                            TagloomChevronDown(Modifier.size(14.dp).rotate(if (option.id in expanded) 0f else -90f))
                        }
                    } else Spacer(Modifier.width(36.dp))
                }
            }
            if (rows.isEmpty()) Text(
                stringResource(Res.string.option_no_matches),
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (maintenance) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (maintenance) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCreate) { Text(stringResource(Res.string.option_new)) }
            TextButton(onClick = onManage) { Text(stringResource(Res.string.option_manage)) }
        }
    }
}

/**
 * 一项可选择的目录名称；展开按钮由父布局独立承载。
 * @param label 当前展示名称或搜索路径。
 * @param selected 是否为实际值。
 * @param focused 是否为键盘游标。
 * @param depth 展示缩进层级。
 * @param modifier 布局、定位和测试语义。
 * @param onClick 选择本级。
 */
@Composable
private fun HierarchyChoiceRow(
    label: String,
    selected: Boolean,
    focused: Boolean,
    depth: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val description = stringResource(Res.string.option_select, label)
    Row(
        modifier.fillMaxWidth().background(
            if (selected) MaterialTheme.colorScheme.primaryContainer else if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
            MaterialTheme.shapes.small
        )
            .clickable(onClick = onClick).semantics { this.selected = selected; contentDescription = description }
            .padding(start = (8 + depth.coerceAtMost(8) * 18).dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected, onClick = null, Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
