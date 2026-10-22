package com.oruke.tagloom.editor

import com.oruke.tagloom.core.domain.property.EffectivePropertyResolver
import com.oruke.tagloom.core.domain.property.EffectivePropertyResult
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.model.ManualPropertyBinding
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.withAppliedObjectType
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas

/**
 * 构造一个尚未持久化的新标签定义。
 *
 * @param name 用户输入名称。
 * @return 带随机稳定 ID 且隐式继承根 Note 标签的定义。
 */
fun TagloomEditorState.createTagDefinitionDraft(name: String): TagDefinition = TagDefinition(
    id = TagId(createEditorId("tag")),
    name = name.trim().ifEmpty { NEW_SUPERTAG_DRAFT_NAME },
)

/** 尚未输入名称的新建表单使用合法临时 payload，确认保存时必须替换。 */
internal const val NEW_SUPERTAG_DRAFT_NAME = "New supertag"

/**
 * 保存属性选项编辑期间允许暂时为空的 UI 草稿。
 *
 * 草稿只存在于当前编辑会话；确认时必须由 [buildPropertyDefinitionDraft] 校验并转回协议模型。
 */
internal data class PropertyOptionDraft(
    /** 既有选项稳定 ID；新选项在确认前为空。 */
    val id: String?,
    /** 用户正在编辑的显示名称，可以暂时为空。 */
    val label: String,
    /** 既有语义色 token；重命名时原样保留。 */
    val colorToken: String? = null,
    /** 父项身份，草稿新项可暂时引用 draftId。 */
    val parentOptionId: String? = null,
    /** 仅在表单内用于组织新项层级的身份，确认时替换为平台生成的稳定 ID。 */
    val draftId: String? = null,
)

/**
 * 描述一次自由选项创建或复用操作在持久化前的确定结果。
 *
 * 计划本身不修改编辑器状态，平台层应先确认 [definition]，再调用
 * [applyFreePropertyOption] 写入选项值，避免 Note 引用尚未确认的选项。
 */
internal data class FreePropertyOptionPlan(
    /** 包含目标选项的完整属性定义。 */
    val definition: PropertyDefinition,
    /** 应写入 Note 值的稳定选项 ID。 */
    val optionId: String,
    /** 属性定义是否新增了选项并需要持久化。 */
    val requiresPersistence: Boolean,
)

/**
 * 把已确认选项转换为可编辑草稿，并保留稳定 ID 与语义色。
 *
 * @return 允许 UI 暂时编辑为空的选项草稿。
 */
internal fun PropertyOption.toEditorDraft(): PropertyOptionDraft = PropertyOptionDraft(id, label, colorToken, parentOptionId)

/**
 * 构造或更新一个尚未持久化的属性定义。
 *
 * 既有选项按稳定 ID 保留语义，新选项只在用户确认时分配 ID，取消对话框不会消耗协议身份。
 * 已有属性不允许改变类型，因为这会让历史值在没有迁移策略时被静默重解释。
 *
 * @param existing 已有定义；为空表示创建新定义。
 * @param name 用户输入名称。
 * @param type 属性协议类型。
 * @param optionInputMode select 类属性的固定或自由输入模式。
 * @param optionDrafts select 类属性的有序选项草稿。
 * @param hierarchical 是否启用单选层级，已有定义默认保留。
 * @return 已规范化且可交给工作区事务确认的完整定义。
 */
internal fun TagloomEditorState.buildPropertyDefinitionDraft(
    existing: PropertyDefinition?,
    name: String,
    type: String,
    optionInputMode: PropertyOptionInputMode = existing?.optionInputMode ?: PropertyOptionInputMode.FIXED,
    optionDrafts: List<PropertyOptionDraft>,
    hierarchical: Boolean = existing?.hierarchical ?: false,
): PropertyDefinition {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "属性名称不能为空" }
    require(existing == null || existing.type == type) { "已有属性类型不能直接修改" }

    val normalizedMode = if (type.requiresPropertyOptions()) optionInputMode else PropertyOptionInputMode.FIXED
    val normalizedOptions = if (type.requiresPropertyOptions()) {
        normalizePropertyOptionDrafts(optionDrafts, normalizedMode, hierarchical)
    } else {
        emptyList()
    }
    return existing?.copy(
        name = normalizedName,
        options = normalizedOptions,
        optionInputMode = normalizedMode,
        hierarchical = hierarchical,
    )
        ?: PropertyDefinition(
            id = PropertyId(createEditorId("property")),
            name = normalizedName,
            type = type,
            options = normalizedOptions,
            optionInputMode = normalizedMode,
            hierarchical = hierarchical,
        )
}

/**
 * 判断属性类型是否必须携带稳定选项 schema。
 *
 * @return select 或 multi-select 时为 true。
 */
internal fun String.requiresPropertyOptions(): Boolean =
    this == PropertyTypes.SELECT || this == PropertyTypes.MULTI_SELECT

/**
 * 校验属性选项名称并把 UI 草稿转换为协议模型。
 *
 * 名称按不区分大小写去重，避免菜单中出现肉眼无法区分但写入不同 ID 的重复值。
 *
 * @param drafts 当前有序草稿。
 * @param inputMode 固定模式要求至少一个选项，自由模式允许从空目录开始。
 * @param hierarchical 是否校验同级唯一和父子目录。
 * @return 保留既有 ID、为新增项分配稳定 ID 的协议选项。
 */
private fun TagloomEditorState.normalizePropertyOptionDrafts(
    drafts: List<PropertyOptionDraft>,
    inputMode: PropertyOptionInputMode,
    hierarchical: Boolean,
): List<PropertyOption> {
    require(inputMode == PropertyOptionInputMode.FREE || drafts.isNotEmpty()) {
        "固定单选和多选属性至少需要一个选项"
    }
    val labels = drafts.map { draft -> draft.label.trim() }
    require(labels.all(String::isNotEmpty)) { "属性选项名称不能为空" }
    val names = drafts.mapIndexed { index, draft -> (if (hierarchical) draft.parentOptionId else null) to labels[index].lowercase() }
    require(names.distinct().size == names.size) { "属性选项名称不能重复" }
    val ids = drafts.map { it.id ?: createEditorId("option") }
    val draftIds = drafts.mapIndexedNotNull { index, draft -> (draft.id ?: draft.draftId)?.let { it to ids[index] } }.toMap()
    return drafts.mapIndexed { index, draft ->
        PropertyOption(
            id = ids[index],
            label = labels[index],
            colorToken = draft.colorToken,
            parentOptionId = if (hierarchical) draft.parentOptionId?.let { draftIds[it] ?: it } else null,
        )
    }
}

/**
 * 规划一次自由选项的创建或大小写不敏感复用。
 *
 * @param propertyId 目标单选或多选属性。
 * @param label 用户输入的选项名称。
 * @return 不修改状态的完整属性定义与待选择 ID。
 */
internal fun TagloomEditorState.planFreePropertyOption(
    propertyId: PropertyId,
    label: String,
): FreePropertyOptionPlan {
    val definition = propertyDefinitions.firstOrNull { current -> current.id == propertyId }
        ?: WorkspaceDefaultSchemas.ordinaryTagsPropertyDefinition().takeIf { fallback -> fallback.id == propertyId }
        ?: error("属性定义不存在")
    require(definition.type.requiresPropertyOptions()) { "只有单选和多选属性可以创建选项" }
    require(definition.optionInputMode == PropertyOptionInputMode.FREE) { "该属性只允许使用固定选项" }
    val normalized = label.trim()
    require(normalized.isNotEmpty()) { "选项名称不能为空" }
    val existing = definition.options.firstOrNull { option -> option.label.equals(normalized, ignoreCase = true) }
    if (existing != null) {
        return FreePropertyOptionPlan(definition, existing.id, requiresPersistence = false)
    }
    val option = PropertyOption(
        id = createEditorId("option"),
        label = normalized,
    )
    return FreePropertyOptionPlan(
        definition = definition.copy(options = definition.options + option),
        optionId = option.id,
        requiresPersistence = true,
    )
}

/**
 * 接受已确认的自由选项目录，并把目标选项写入当前 Note。
 *
 * 单选替换旧值，多选保留旧值并追加；调用方必须确保新增选项的属性定义已经确认，
 * 这样崩溃最多留下未引用选项，不会让 Note 引用不存在的 schema。
 *
 * @param confirmedDefinition 已确认且包含目标选项的属性定义。
 * @param optionId 待选择稳定选项 ID。
 */
internal fun TagloomEditorState.applyFreePropertyOption(
    confirmedDefinition: PropertyDefinition,
    optionId: String,
) {
    require(confirmedDefinition.options.any { option -> option.id == optionId }) { "自由选项尚未进入属性定义" }
    upsertPropertyDefinition(confirmedDefinition)
    when (confirmedDefinition.type) {
        PropertyTypes.SELECT -> propertyEditor.updateSelect(confirmedDefinition.id, optionId)
        PropertyTypes.MULTI_SELECT -> {
            val currentIds = selectedDocument?.let { document ->
                propertyEditor.stringListValue(document.note, confirmedDefinition.id)
            }.orEmpty()
            propertyEditor.updateMultiSelect(confirmedDefinition.id, currentIds + optionId)
        }
        else -> error("只有单选和多选属性可以应用自由选项")
    }
}

/**
 * 用新确认标签定义替换内存目录中的同 ID 版本。
 *
 * @param definition 已由 manifest 确认的标签定义。
 */
fun TagloomEditorState.upsertTagDefinition(definition: TagDefinition) {
    val index = tagDefinitions.indexOfFirst { current -> current.id == definition.id }
    if (index >= 0) tagDefinitions[index] = definition else tagDefinitions += definition
    tagDefinitions.sortWith(compareBy(TagDefinition::name, { current -> current.id.value }))
}

/**
 * 用新确认属性定义替换内存目录中的同 ID 版本。
 *
 * @param definition 已由 manifest 确认的属性定义。
 */
fun TagloomEditorState.upsertPropertyDefinition(definition: PropertyDefinition) {
    val index = propertyDefinitions.indexOfFirst { current -> current.id == definition.id }
    if (index >= 0) propertyDefinitions[index] = definition else propertyDefinitions += definition
    propertyDefinitions.sortWith(compareBy(PropertyDefinition::name, { current -> current.id.value }))
}

/**
 * 为当前 Note 显式选择唯一应用对象类型。
 *
 * 选择新类型会替换其他非根直接类型，但保留它们曾写入的属性值；旧多类型快照只有经过此显式动作才会规范化。
 *
 * @param tagId 目标对象类型 ID；为空表示恢复为只应用内置 Note 根类型。
 */
fun TagloomEditorState.setAppliedObjectType(tagId: TagId?) {
    val rootTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    require(tagId == null || tagId != rootTypeId) { "根 Note 类型不需要重复设为应用类型" }
    require(tagId == null || tagDefinitions.any { definition -> definition.id == tagId }) { "对象类型定义不存在" }
    updateSelectedNote { note ->
        val updated = note.withAppliedObjectType(rootTypeId, tagId)
        if (tagId == null) updated else materializePendingSnapshotDefaults(updated)
    }
}

/**
 * 兼容旧调用方的直接类型关联入口。
 *
 * 新增非根类型时采用单一应用类型语义；移除操作只删除指定关联，以便旧多类型快照仍可逐项恢复处理。
 *
 * @param tagId 目标对象类型 ID。
 * @param attached 是否应关联该类型。
 */
fun TagloomEditorState.setTagAttached(
    tagId: TagId,
    attached: Boolean,
) {
    require(tagDefinitions.any { definition -> definition.id == tagId }) { "对象类型定义不存在" }
    val rootTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    if (tagId == rootTypeId) {
        if (attached) {
            updateSelectedNote { note ->
                if (rootTypeId in note.directTagIds) note else note.copy(directTagIds = listOf(rootTypeId) + note.directTagIds)
            }
        }
        return
    }
    if (attached) {
        setAppliedObjectType(tagId)
    } else {
        updateSelectedNote { note -> note.copy(directTagIds = note.directTagIds.filterNot { current -> current == tagId }) }
    }
}

/**
 * 把新标签引入的 snapshot 默认值写入笔记业务快照。
 *
 * 只有解析无冲突、存在来源标签且当前笔记没有显式值时才落盘；这样可以保留默认值的来源，
 * 同时避免同级冲突在用户尚未选择前被静默固化。
 *
 * @param note 已完成直接标签更新的笔记。
 * @return 包含待确认快照默认值的笔记。
 */
private fun TagloomEditorState.materializePendingSnapshotDefaults(note: Note): Note {
    val effective = resolveEffectiveProperties(note) ?: return note
    val pendingValues = effective.properties
        .filter { property ->
            property.requiresSnapshot &&
                !property.conflicted &&
                property.value != null &&
                property.sourceTagId != null &&
                property.propertyId !in note.propertyValues
        }
        .associate { property ->
            property.propertyId to NotePropertyValue(
                value = requireNotNull(property.value),
                source = PropertyValueSource.TAG_SNAPSHOT,
                sourceTagId = requireNotNull(property.sourceTagId),
                overridden = false,
                updatedAt = currentEditorTimestamp(),
            )
        }
    return if (pendingValues.isEmpty()) note else note.copy(propertyValues = note.propertyValues + pendingValues)
}

/**
 * 为当前笔记补齐因标签 schema 新增而尚未确认的 snapshot 默认值。
 *
 * 该动作只更新内存 Note 并标记为待保存，随后仍由统一自动保存链路写入加密对象快照。
 */
fun TagloomEditorState.materializeSelectedSnapshotDefaults() {
    updateSelectedNote { note -> materializePendingSnapshotDefaults(note) }
}

/**
 * 手动加入或移除当前 Note 的一个可复用属性。
 *
 * @param propertyId 目标属性定义 ID。
 * @param attached 是否应建立笔记级绑定。
 */
fun TagloomEditorState.setPropertyAttached(
    propertyId: PropertyId,
    attached: Boolean,
) {
    require(propertyDefinitions.any { definition -> definition.id == propertyId }) { "属性定义不存在" }
    updateSelectedNote { note ->
        if (attached) {
            if (note.manualPropertyBindings.any { binding -> binding.propertyId == propertyId }) {
                note
            } else {
                note.copy(manualPropertyBindings = note.manualPropertyBindings + ManualPropertyBinding(propertyId))
            }
        } else {
            note.copy(
                manualPropertyBindings = note.manualPropertyBindings.filterNot { binding -> binding.propertyId == propertyId },
                propertyValues = note.propertyValues - propertyId,
                propertyStyleOverrides = note.propertyStyleOverrides - propertyId,
            )
        }
    }
}

/**
 * 按标签继承、手动绑定和 Note 值计算当前有效属性。
 *
 * @param note 待解析 Note。
 * @return 成功解析的有效属性；标签图损坏时为空。
 */
fun TagloomEditorState.resolveEffectiveProperties(note: Note): EffectivePropertyResult? {
    val tags = tagDefinitions.associateBy(TagDefinition::id)
    val resolution = resolveEffectiveTags(note.directTagIds)
    if (resolution !is TagResolution.Success) return null
    return EffectivePropertyResolver().resolve(
        note = note,
        tagResolution = resolution,
        tags = tags,
        propertyDefinitions = propertyDefinitions.associateBy(PropertyDefinition::id),
    )
}
