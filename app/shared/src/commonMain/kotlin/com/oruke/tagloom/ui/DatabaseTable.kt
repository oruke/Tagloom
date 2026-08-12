package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.DatasetProjectionColumn
import com.oruke.tagloom.core.model.DatasetProjectionRow
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.SystemFieldNames
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_system_title
import kotlin.math.roundToInt

/**
 * 渲染行等于 Note、列等于字段的可写 Table 投影。
 *
 * 序号、列头和数据单元格共享同一网格。列头只保留列宽拖动与字段菜单；字段顺序统一在字段选择面板中
 * 使用上移、下移调整，避免横向滚动与列拖动手势竞争。
 *
 * @param controller Database 控制器。
 * @param projection 当前投影。
 * @param compact 是否使用触控优先尺寸。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
internal fun DatabaseTable(
    controller: DatabaseEditorController,
    projection: DatasetProjection,
    compact: Boolean,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val configuredWidths = remember(projection.view.id, projection.view.presentation.fields) {
        mutableStateMapOf<FieldRef, Int>().apply {
            projection.columns.forEach { column -> this[column.field] = projection.columnWidthDp(column) }
        }
    }
    val rowHeight = DATABASE_CELL_HEIGHT
    Column(modifier.horizontalScroll(rememberScrollState())) {
        Row {
            DatabaseIndexCell(label = "#", height = rowHeight)
            projection.columns.forEach { column ->
                val width = configuredWidths[column.field] ?: projection.columnWidthDp(column)
                DatabaseHeaderCell(
                    column = column,
                    widthDp = width,
                    height = rowHeight,
                    compact = compact,
                    canHide = column.field.systemField != SystemFieldNames.TITLE,
                    onWidthChange = { updated -> configuredWidths[column.field] = updated },
                    onWidthCommit = { updated ->
                        scope.launch {
                            controller.updateView(projection.view.id) { current ->
                                current.withDatasetFieldWidth(column.field, updated)
                            }
                        }
                    },
                    onHide = {
                        scope.launch {
                            controller.updateView(projection.view.id) { current -> current.toggledDatasetField(column.field) }
                        }
                    },
                )
            }
        }
        projection.rows.forEachIndexed { rowIndex, row ->
            Row {
                DatabaseIndexCell(
                    label = (rowIndex + 1).toString(),
                    height = rowHeight,
                    onClick = { onOpenNote(row.noteId) },
                )
                projection.columns.forEach { column ->
                    DatabaseCell(
                        controller = controller,
                        databaseId = projection.database.id,
                        row = row,
                        column = column,
                        width = (configuredWidths[column.field] ?: projection.columnWidthDp(column)).dp,
                        height = rowHeight,
                        onCommit = { value -> scope.launch { controller.updateCell(row.noteId, column.field, value) } },
                    )
                }
            }
        }
    }
}

/**
 * 渲染表头或数据行使用的标准序号列单元格。
 *
 * @param label 井号或从一开始的行号。
 * @param height 当前桌面或触控行高。
 * @param onClick 可选打开原始 Note 动作。
 */
@Composable
private fun DatabaseIndexCell(
    label: String,
    height: Dp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.width(DATABASE_INDEX_COLUMN_WIDTH).height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (label == "#") 0.42f else 0f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 渲染支持调宽和字段菜单的 Table 列头。
 *
 * @param column 当前投影列。
 * @param widthDp 视图配置的列宽。
 * @param height 列头高度。
 * @param compact 是否使用触控优先尺寸。
 * @param canHide 是否允许隐藏。
 * @param onWidthChange 拖动中的宽度预览动作。
 * @param onWidthCommit 拖动结束后的宽度写入动作。
 * @param onHide 隐藏字段动作。
 */
@Composable
private fun DatabaseHeaderCell(
    column: DatasetProjectionColumn,
    widthDp: Int,
    height: Dp,
    compact: Boolean,
    canHide: Boolean,
    onWidthChange: (Int) -> Unit,
    onWidthCommit: (Int) -> Unit,
    onHide: () -> Unit,
) {
    val density = LocalDensity.current
    var menuOpen by remember(column.field) { mutableStateOf(false) }
    var resizeDistance by remember(column.field) { mutableFloatStateOf(0f) }
    var resizeStartWidth by remember(column.field) { mutableIntStateOf(widthDp) }
    var resizing by remember(column.field) { mutableStateOf(false) }
    val latestWidth by rememberUpdatedState(widthDp)
    val label = if (column.field.kind == FieldRefKinds.SYSTEM && column.field.systemField == SystemFieldNames.TITLE) {
        stringResource(Res.string.database_system_title)
    } else {
        column.label
    }
    val typeIcon = datasetColumnIcon(column)
    Box(
        Modifier.width(widthDp.dp).height(height)
            .zIndex(if (resizing) 1f else 0f)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = if (compact) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (typeIcon.isNotEmpty()) {
                Text(
                    typeIcon,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DatabaseColumnMenu(
                expanded = menuOpen,
                compact = compact,
                height = height,
                canHide = canHide,
                onOpen = { menuOpen = true },
                onDismiss = { menuOpen = false },
                onSetWidth = { targetWidth ->
                    onWidthChange(targetWidth)
                    onWidthCommit(targetWidth)
                },
                onHide = onHide,
            )
        }
        Box(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(if (compact) 16.dp else 10.dp)
                .pointerInput(column.field) {
                    detectDragGestures(
                        onDragStart = {
                            resizing = true
                            resizeDistance = 0f
                            resizeStartWidth = latestWidth
                        },
                        onDragEnd = {
                            resizing = false
                            onWidthCommit(latestWidth)
                        },
                        onDragCancel = {
                            resizing = false
                            onWidthChange(resizeStartWidth)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            resizeDistance += amount.x
                            val deltaDp = with(density) { resizeDistance.toDp().value.roundToInt() }
                            onWidthChange((resizeStartWidth + deltaDp).coerceIn(64, 720))
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.width(if (resizing) 2.dp else 1.dp).fillMaxHeight()
                    .background(if (resizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/**
 * 渲染列头的宽度预设和隐藏菜单。
 *
 * @param expanded 菜单是否展开。
 * @param compact 是否使用触控宽度。
 * @param height 列头高度。
 * @param canHide 是否允许隐藏。
 * @param onOpen 打开菜单动作。
 * @param onDismiss 关闭菜单动作。
 * @param onSetWidth 选择宽度预设动作。
 * @param onHide 隐藏字段动作。
 */
@Composable
private fun DatabaseColumnMenu(
    expanded: Boolean,
    compact: Boolean,
    height: Dp,
    canHide: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSetWidth: (Int) -> Unit,
    onHide: () -> Unit,
) {
    Box {
        Text(
            "⋯",
            modifier = Modifier.height(height).width(if (compact) 32.dp else 18.dp).clickable(onClick = onOpen),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            listOf(96 to "窄列", 180 to "标准列", 300 to "宽列").forEach { (targetWidth, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSetWidth(targetWidth)
                    onDismiss()
                })
            }
            DropdownMenuItem(text = { Text("隐藏字段") }, enabled = canHide, onClick = {
                onHide()
                onDismiss()
            })
        }
    }
}

/**
 * 渲染一个可写或只读 Database 单元格。
 *
 * @param controller Database 控制器。
 * @param databaseId 当前投影所属 Database ID。
 * @param row 当前 Note 行。
 * @param column 当前字段列。
 * @param width 视图配置的列宽。
 * @param height 单元格高度。
 * @param onCommit 写回 Note 的值。
 */
@Composable
private fun DatabaseCell(
    controller: DatabaseEditorController,
    databaseId: DatabaseId,
    row: DatasetProjectionRow,
    column: DatasetProjectionColumn,
    width: Dp,
    height: Dp,
    onCommit: (JsonElement?) -> Unit,
) {
    val property = column.field.propertyId?.let { id ->
        controller.propertyDefinitions(databaseId).firstOrNull { item -> item.id == id }
    }
    val value = row.values[column.field]
    Box(
        Modifier.width(width).height(height)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            !column.writable || property?.type in setOf(PropertyTypes.FORMULA, PropertyTypes.RELATION, PropertyTypes.FILE) ->
                Text(value.datasetContentText(), style = MaterialTheme.typography.bodySmall)
            property?.type == PropertyTypes.BOOLEAN -> Checkbox(
                checked = (value as? JsonPrimitive)?.booleanOrNull ?: false,
                onCheckedChange = { checked -> onCommit(JsonPrimitive(checked)) },
            )
            property?.type == PropertyTypes.SELECT -> DatabaseSelectCell(property, value, onCommit)
            property?.type == PropertyTypes.MULTI_SELECT -> DatabaseMultiSelectCell(property, value, onCommit)
            else -> DatabaseTextCell(value.datasetContentText(), property?.type, onCommit)
        }
    }
}

/**
 * 返回列头显示的紧凑字段类型图标。
 *
 * @param column 当前投影列。
 * @return 标题字段使用 Aa，属性字段不占用额外列头宽度。
 */
private fun datasetColumnIcon(column: DatasetProjectionColumn): String =
    if (column.field.kind == FieldRefKinds.SYSTEM && column.field.systemField == SystemFieldNames.TITLE) "Aa" else ""

/**
 * 读取当前 View 中一列的用户宽度并限制在协议允许范围。
 *
 * @param column 当前投影列。
 * @return Compose 使用的列宽数值；缺失时使用紧凑默认值。
 */
private fun DatasetProjection.columnWidthDp(column: DatasetProjectionColumn): Int =
    view.presentation.fields.firstOrNull { field -> field.field == column.field }
        ?.width
        ?.coerceIn(64, 720)
        ?: DATABASE_COLUMN_WIDTH

/** Table 默认列宽。 */
private const val DATABASE_COLUMN_WIDTH = 190

/** 桌面 Table 单元格高度。 */
private val DATABASE_CELL_HEIGHT = 40.dp

/** Table 序号列宽度。 */
private val DATABASE_INDEX_COLUMN_WIDTH = 44.dp
