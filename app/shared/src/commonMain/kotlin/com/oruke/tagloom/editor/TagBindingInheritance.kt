package com.oruke.tagloom.editor

import com.oruke.tagloom.core.domain.property.EffectiveProperty
import com.oruke.tagloom.core.domain.property.EffectivePropertyResolver
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolutionError
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/** 描述维护页中的一项有效属性；继承基线与本级草稿分开，展示不会制造持久化覆盖。 */
internal data class TagBindingEditorRow(
    /** 当前显示的绑定草稿；未覆盖时取自领域解析的父级绑定。 */
    val draft: TagPropertyBindingDraft,
    /** 工作区共享的属性定义，只用于显示名称、类型和选择既有选项。 */
    val definition: PropertyDefinition?,
    /** 清空本级绑定后解析得到的继承基线；本级独有属性为空。 */
    val inheritedProperty: EffectiveProperty?,
    /** 提供继承绑定的来源；发生冲突时包含所有竞争父级。 */
    val sources: List<TagDefinition>,
    /** 是否已有或正在编辑本级绑定；只有这些草稿会进入保存流程。 */
    val declaredLocally: Boolean,
) {
    /** 尚未通过本级绑定明确解决的多父冲突。 */
    val conflicted: Boolean get() = inheritedProperty?.conflicted == true && !declaredLocally
}

/** 汇总可编辑属性与继承图错误，错误发生时仍保留所有本级草稿供修复。 */
internal data class TagBindingEditorProperties(
    /** 自动展示的继承属性，以及本级新增或覆盖的属性。 */
    val rows: List<TagBindingEditorRow>,
    /** 缺失父级或继承环；不允许把解析失败伪装成空属性列表。 */
    val inheritanceErrors: List<TagResolutionError>,
) {
    /** 保存前须处理继承图错误、未解决冲突和缺失的属性定义。 */
    val canSave: Boolean get() = inheritanceErrors.isEmpty() && rows.none { it.conflicted || it.definition == null }
}

/**
 * 复用领域解析器生成维护页的继承基线，再叠加尚未确认的本级草稿。
 *
 * @param tag 当前编辑的超级标签；仅复用其稳定身份，不修改原定义。
 * @param parentTagIds 当前表单选择的父级，空列表仍由领域规则隐式继承 Note。
 * @param localDrafts 当前标签直接声明的绑定草稿，包括尚未输入完整的默认值。
 * @param tags 已确认的工作区超级标签目录。
 * @param definitions 已确认及当前表单新建的属性定义。
 * @return 继承来源、显式冲突、本级覆盖以及可修复的继承图错误。
 */
internal fun resolveTagBindingEditorProperties(
    tag: TagDefinition,
    parentTagIds: List<TagId>,
    localDrafts: List<TagPropertyBindingDraft>,
    tags: List<TagDefinition>,
    definitions: List<PropertyDefinition>,
): TagBindingEditorProperties {
    // 1. 只把正在编辑的标签设为直接入口，避免父级不可继承的属性被误算成直接绑定。
    val catalog = tags.associateBy { it.id } + (tag.id to tag.copy(
        parentTagIds = parentTagIds,
        propertyBindings = emptyList(),
    ))
    val properties = definitions.associateBy { it.id }
    val resolution = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(listOf(tag.id), catalog)
    val errors = (resolution as? TagResolution.Failure)?.errors.orEmpty()
    val inherited = if (resolution is TagResolution.Success) {
        EffectivePropertyResolver().resolve(
            note = Note(title = "", directTagIds = listOf(tag.id)),
            tagResolution = resolution,
            tags = catalog,
            propertyDefinitions = properties,
        )
    } else null

    // 2. 基线只负责展示；草稿无需提前转换为确认绑定，数字等未完成输入仍能继续编辑。
    val locals = localDrafts.associateBy { it.propertyId }
    val conflicts = inherited?.conflicts.orEmpty().associateBy { it.propertyId }
    val inheritedRows = inherited?.properties.orEmpty().map { property ->
        val sourceIds = conflicts[property.propertyId]?.sourceTagIds ?: listOfNotNull(property.sourceTagId)
        TagBindingEditorRow(
            draft = locals[property.propertyId] ?: requireNotNull(property.binding).toEditorDraft(property.definition),
            definition = property.definition,
            inheritedProperty = property,
            sources = sourceIds.map(catalog::getValue),
            declaredLocally = property.propertyId in locals,
        )
    }
    val inheritedIds = inheritedRows.map { it.draft.propertyId }.toSet()
    val localRows = localDrafts.filterNot { it.propertyId in inheritedIds }.map { draft ->
        TagBindingEditorRow(draft, properties[draft.propertyId], null, emptyList(), declaredLocally = true)
    }
    return TagBindingEditorProperties(inheritedRows + localRows, errors)
}
