package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.*

/**
 * 创建或更新标签定义，并可在确认后立即关联当前 Note。
 *
 * @param definition 待确认完整标签定义。
 * @param attachToSelected 保存成功后是否关联当前 Note。
 */
internal suspend fun WorkspaceUiController.saveTagDefinition(
    definition: TagDefinition,
    attachToSelected: Boolean,
) = runOperation {
    val validationErrors = editorState.validateTagDefinition(definition)
    require(validationErrors.isEmpty()) { validationErrors.joinToString(separator = "；") }
    val saved = gateway.saveTagDefinition(definition).definition
    editorState.upsertTagDefinition(saved)
    if (attachToSelected) {
        editorState.setAppliedObjectType(saved.id)
    } else {
        editorState.materializeSelectedSnapshotDefaults()
    }
    statusMessage = if (attachToSelected) {
        WorkspaceUiMessage.TagCreatedAndAttached
    } else {
        WorkspaceUiMessage.TagUpdated
    }
}

/**
 * 创建或更新属性定义，并可在确认后立即加入当前 Note。
 *
 * @param definition 待确认完整属性定义。
 * @param destination 创建后绑定当前笔记、对象类型或仅进入工作区目录。
 */
internal suspend fun WorkspaceUiController.savePropertyDefinition(
    definition: PropertyDefinition,
    destination: PropertyDefinitionDestination,
) = runOperation {
    val saved = gateway.savePropertyDefinition(definition).definition
    editorState.upsertPropertyDefinition(saved)
    statusMessage = when (destination) {
        PropertyDefinitionDestination.CurrentNote -> {
            editorState.setPropertyAttached(saved.id, attached = true)
            WorkspaceUiMessage.PropertyCreatedAndAttached
        }

        PropertyDefinitionDestination.Workspace -> WorkspaceUiMessage.PropertySaved
        is PropertyDefinitionDestination.Tag -> {
            val updatedTag = editorState.bindPropertyToTag(destination.tagId, saved.id)
            val confirmedTag = gateway.saveTagDefinition(updatedTag).definition
            editorState.upsertTagDefinition(confirmedTag)
            editorState.materializeSelectedSnapshotDefaults()
            WorkspaceUiMessage.PropertyBoundToTag(confirmedTag.name)
        }
    }
}

/**
 * 为自由单选或多选属性确认新选项，并在确认后选择该选项。
 *
 * schema 先于 Note 值确认，因此进程中断最多留下未引用的可复用选项，不会让 Note
 * 指向不存在的选项；Note 值随后仍由统一自动保存链路确认。
 *
 * @param propertyId 目标属性稳定 ID。
 * @param label 用户输入的选项名称。
 */
internal suspend fun WorkspaceUiController.createFreePropertyOptionAndSelect(
    propertyId: PropertyId,
    label: String,
) = runOperation {
    val plan = editorState.planFreePropertyOption(propertyId, label)
    val confirmedDefinition = if (plan.requiresPersistence) {
        gateway.savePropertyDefinition(plan.definition).definition
    } else {
        plan.definition
    }
    editorState.applyFreePropertyOption(confirmedDefinition, plan.optionId)
    statusMessage = if (plan.requiresPersistence) {
        WorkspaceUiMessage.OptionSavedAndSelected
    } else {
        WorkspaceUiMessage.ExistingOptionSelected
    }
}
