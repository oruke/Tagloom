package com.oruke.tagloom.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_group_empty
import tagloom.app.shared.generated.resources.database_kanban_requires_select

/**
 * 渲染按单选属性分组的有限 Kanban 投影。
 *
 * @param controller Database 控制器。
 * @param projection 当前投影。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
internal fun DatabaseKanban(
    controller: DatabaseEditorController,
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupId = projection.view.presentation.groupByPropertyId
    val property = controller.propertyDefinitions(projection.database.id).firstOrNull { item -> item.id == groupId }
    if (property == null) {
        Text(stringResource(Res.string.database_kanban_requires_select), modifier = modifier)
        return
    }
    val field = FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        (property.options.map { option -> option.id to option.label } + ("" to stringResource(Res.string.database_group_empty)))
            .forEach { (optionId, label) ->
                val rows = projection.rows.filter { row -> row.values[field].datasetContentText() == optionId }
                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$label  ${rows.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    rows.forEach { row ->
                        Surface(
                            onClick = { onOpenNote(row.noteId) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(row.title, modifier = Modifier.fillMaxWidth().padding(10.dp), maxLines = 2)
                        }
                    }
                }
            }
    }
}

/**
 * 渲染有限字段的 Gallery 卡片投影。
 *
 * @param projection 当前投影。
 * @param onOpenNote 打开原始 Note。
 * @param modifier 外部布局。
 */
@Composable
internal fun DatabaseGallery(
    projection: DatasetProjection,
    onOpenNote: (ObjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        projection.rows.forEach { row ->
            Surface(
                onClick = { onOpenNote(row.noteId) },
                modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.values.entries.take(3).forEach { (field, value) ->
                        Text(
                            "${field.propertyId?.value ?: field.systemField}: ${value.datasetContentText()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 返回投影展示使用的无 Locale JSON 文本。
 *
 * @return 空值、标量或数组的稳定展示文本。
 */
internal fun JsonElement?.datasetContentText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString(", ") { item -> item.datasetContentText() }
    else -> toString()
}
