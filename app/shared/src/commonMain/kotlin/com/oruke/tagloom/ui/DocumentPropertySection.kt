package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.domain.property.EffectiveProperty
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染描述下方的默认属性、更多属性与渐进式新增入口。
 *
 * @param state 当前编辑器状态。
 * @param document 当前笔记。
 * @param properties 已过滤内置描述和普通标签的有效属性。
 * @param onEditDefinition 请求编辑属性定义。
 * @param onEditSourceType 请求编辑属性来源类型。
 * @param onCreateOption 请求持久化自由选项。
 * @param onCreateProperty 请求持久化新属性及目标绑定。
 * @param onAttachExisting 请求把既有属性绑定到当前笔记或应用类型。
 */
@Composable
internal fun DocumentPropertySection(
    state: TagloomEditorState,
    document: EditorDocument,
    properties: List<EffectiveProperty>,
    onEditDefinition: (PropertyDefinition) -> Unit,
    onEditSourceType: (TagDefinition) -> Unit,
    onCreateOption: (PropertyId, String) -> Unit,
    onCreateProperty: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onAttachExisting: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
) {
    val primary = properties.filter { property -> property.documentPlacement() == DocumentPropertyPlacement.PRIMARY }
    val more = properties.filter { property -> property.documentPlacement() == DocumentPropertyPlacement.MORE }
    var moreExpanded by remember(document.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PropertyGrid(
            state = state,
            document = document,
            properties = primary,
            onEditDefinition = onEditDefinition,
            onEditSourceType = onEditSourceType,
            onCreateOption = onCreateOption,
        )
        if (more.isNotEmpty()) {
            Text(
                text = if (moreExpanded) {
                    stringResource(Res.string.editor_properties_hide_more)
                } else {
                    stringResource(Res.string.editor_properties_show_more, more.size)
                },
                modifier = Modifier.clickable { moreExpanded = !moreExpanded },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (moreExpanded) {
                PropertyGrid(
                    state = state,
                    document = document,
                    properties = more,
                    onEditDefinition = onEditDefinition,
                    onEditSourceType = onEditSourceType,
                    onCreateOption = onCreateOption,
                )
            }
        }
        AddPropertyPopover(
            state = state,
            document = document,
            effectivePropertyIds = properties.map(EffectiveProperty::propertyId).toSet(),
            onCreateProperty = onCreateProperty,
            onAttachExisting = onAttachExisting,
        )
    }
}

/**
 * 在宽编辑器中按两列、窄编辑器中按一列渲染属性行。
 *
 * @param state 当前编辑器状态。
 * @param document 当前笔记。
 * @param properties 当前展示组属性。
 * @param onEditDefinition 请求编辑属性定义。
 * @param onEditSourceType 请求编辑来源类型。
 * @param onCreateOption 请求持久化自由选项。
 */
@Composable
private fun PropertyGrid(
    state: TagloomEditorState,
    document: EditorDocument,
    properties: List<EffectiveProperty>,
    onEditDefinition: (PropertyDefinition) -> Unit,
    onEditSourceType: (TagDefinition) -> Unit,
    onCreateOption: (PropertyId, String) -> Unit,
) {
    if (properties.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth >= 680.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            properties.chunked(columnCount).forEach { rowProperties ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    rowProperties.forEach { property ->
                        WorkspacePropertyValueEditor(
                            state = state,
                            document = document,
                            property = property,
                            onEditDefinition = onEditDefinition,
                            onEditSourceTag = onEditSourceType,
                            onCreateOption = onCreateOption,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowProperties.size < columnCount) {
                        repeat(columnCount - rowProperties.size) { Spacer(Modifier.weight(1f).height(1.dp)) }
                    }
                }
            }
        }
    }
}
