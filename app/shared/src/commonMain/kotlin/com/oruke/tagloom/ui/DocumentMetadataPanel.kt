package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.oruke.tagloom.core.domain.property.PropertyResolutionConflict
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.bindPropertyToTag
import com.oruke.tagloom.editor.buildPropertyDefinitionDraft
import com.oruke.tagloom.editor.createTagDefinitionDraft
import com.oruke.tagloom.editor.resolveEffectiveProperties
import com.oruke.tagloom.editor.setPropertyAttached
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 编排标题下方的身份行、无标签描述和动态属性区。
 *
 * @param state 当前编辑器状态。
 * @param document 当前笔记。
 * @param onSaveTagDefinition 持久化对象类型定义并声明是否立即应用。
 * @param onSavePropertyDefinition 持久化属性定义并建立目标绑定。
 * @param onCreatePropertyOption 持久化并选择自由选项。
 */
@Composable
internal fun DocumentMetadataPanel(
    state: TagloomEditorState,
    document: EditorDocument,
    onSaveTagDefinition: (TagDefinition, Boolean) -> Unit,
    onSavePropertyDefinition: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onCreatePropertyOption: (PropertyId, String) -> Unit,
) {
    val openSupertag = LocalSupertagEditor.current
    var propertyDialog by remember(document.id) { mutableStateOf<PropertyDialogRequest?>(null) }
    val effectiveProperties = state.resolveEffectiveProperties(document.note)

    Column(Modifier.fillMaxWidth()) {
        DocumentIdentityBar(
            state = state,
            document = document,
            onCreateType = { name ->
                openSupertag(state.createTagDefinitionDraft(name), true)
            },
            onOpenType = { definition ->
                openSupertag(definition, false)
            },
            onCreateTag = onCreatePropertyOption,
        )
        Spacer(Modifier.height(8.dp))
        DocumentDescription(state = state, document = document)
        Spacer(Modifier.height(24.dp))
        if (effectiveProperties == null) {
            InlineSchemaMessage(
                stringResource(Res.string.editor_schema_inheritance_invalid),
                error = true,
            )
        } else {
            effectiveProperties.conflicts.forEach { conflict ->
                InlineSchemaMessage(conflict.displayMessage(state), error = true)
            }
            effectiveProperties.missingPropertyIds.forEach { propertyId ->
                InlineSchemaMessage(
                    stringResource(Res.string.editor_schema_missing_property, propertyId.value),
                    error = true,
                )
            }
            DocumentPropertySection(
                state = state,
                document = document,
                properties = effectiveProperties.properties.filterNot { property ->
                    property.propertyId == WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID ||
                        property.propertyId == WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID
                },
                onEditDefinition = { definition ->
                    propertyDialog = PropertyDialogRequest(definition, definition.name)
                },
                onEditSourceType = { definition ->
                    openSupertag(definition, false)
                },
                onCreateOption = onCreatePropertyOption,
                onCreateProperty = onSavePropertyDefinition,
                onAttachExisting = { definition, destination ->
                    when (destination) {
                        PropertyDefinitionDestination.CurrentNote -> state.setPropertyAttached(definition.id, attached = true)
                        is PropertyDefinitionDestination.Tag -> {
                            onSaveTagDefinition(state.bindPropertyToTag(destination.tagId, definition.id), false)
                        }
                        PropertyDefinitionDestination.Workspace -> Unit
                    }
                },
            )
        }
    }

    propertyDialog?.let { request ->
        PropertyDefinitionDialog(
            request = request,
            tagDefinitions = state.tagDefinitions.filterNot { definition ->
                definition.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
            },
            onDismiss = { propertyDialog = null },
            onConfirm = { name, type, optionInputMode, options, destination ->
                val definition = state.buildPropertyDefinitionDraft(
                    existing = request.existing,
                    name = name,
                    type = type,
                    optionInputMode = optionInputMode,
                    optionDrafts = options,
                )
                onSavePropertyDefinition(definition, destination)
                propertyDialog = null
            },
        )
    }
}

/**
 * 渲染不打断正文布局的 schema 恢复提示。
 *
 * @param message 可操作问题说明。
 * @param error 是否使用错误语义色。
 */
@Composable
private fun InlineSchemaMessage(
    message: String,
    error: Boolean,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 把属性继承冲突转换为包含来源类型名称的恢复说明。
 *
 * @param state 当前工作区 schema。
 * @return 不隐藏竞争来源的中文提示。
 */
@Composable
private fun PropertyResolutionConflict.displayMessage(state: TagloomEditorState): String {
    val propertyName = state.propertyDefinitions.firstOrNull { definition -> definition.id == propertyId }?.name
        ?: propertyId.value
    val sourceNames = sourceTagIds.map { tagId ->
        state.tagDefinitions.firstOrNull { definition -> definition.id == tagId }?.name ?: tagId.value
    }
    return stringResource(
        Res.string.editor_schema_conflict,
        propertyName,
        sourceNames.joinToString(" / "),
    )
}
