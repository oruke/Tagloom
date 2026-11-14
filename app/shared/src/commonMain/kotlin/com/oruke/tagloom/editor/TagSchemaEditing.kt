package com.oruke.tagloom.editor

import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 构造可提交的超级标签定义，并校验父标签图与属性 schema 引用。
 *
 * @param existing 正在创建或编辑的标签定义。
 * @param name 用户输入名称。
 * @param parentTagIds 显式父标签；空列表表示隐式继承根 Note。
 * @param propertyBindings 当前标签直接声明的属性绑定。
 * @param iconKey 显式图标目录键；为空表示不使用图标。
 * @param availableProperties 已确认属性与当前表单的新属性草稿。
 * @return 已规范化且不会制造继承环的标签定义。
 */
internal fun TagloomEditorState.buildTagDefinitionDraft(
    existing: TagDefinition,
    name: String,
    parentTagIds: List<TagId>,
    propertyBindings: List<TagPropertyBinding>,
    iconKey: String? = existing.iconKey,
    availableProperties: List<com.oruke.tagloom.core.model.PropertyDefinition> = propertyDefinitions,
    aiAccessPolicy: com.oruke.tagloom.core.model.AiAccessPolicy = existing.aiAccessPolicy,
): TagDefinition {
    val normalizedName = name.trim()
    val normalizedParents = parentTagIds.distinct()
    require(normalizedName.isNotEmpty()) { "标签名称不能为空" }
    require(existing.id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) { "根 Note 标签不能通过普通编辑器修改" }
    require(existing.id !in normalizedParents) { "标签不能继承自己" }
    require(normalizedParents.all { parentId -> tagDefinitions.any { definition -> definition.id == parentId } }) {
        "父标签不存在"
    }
    val knownPropertyIds = availableProperties.map { definition -> definition.id }.toSet()
    require(propertyBindings.all { binding -> binding.propertyId in knownPropertyIds }) { "标签绑定了不存在的属性" }

    val definition = existing.copy(
        name = normalizedName,
        parentTagIds = normalizedParents,
        propertyBindings = propertyBindings,
        iconKey = iconKey,
        aiAccessPolicy = aiAccessPolicy,
    )
    val errors = validateTagDefinition(definition)
    require(errors.isEmpty()) { errors.joinToString(separator = "；") }
    return definition
}

/**
 * 校验一个待保存标签替换目录中同 ID 定义后是否仍可解析。
 *
 * @param definition 待验证标签定义。
 * @return 缺失父级或循环继承的中文原因；空列表表示可保存。
 */
fun TagloomEditorState.validateTagDefinition(definition: TagDefinition): List<String> {
    val tags = tagDefinitions.associateBy(TagDefinition::id).toMutableMap()
    tags[definition.id] = definition
    return when (
        val resolution = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(
            directTagIds = listOf(definition.id),
            tags = tags,
        )
    ) {
        is TagResolution.Success -> emptyList()
        is TagResolution.Failure -> resolution.errors.map { error -> error.message }
    }
}

/**
 * 解析一篇笔记的全部直接与继承标签。
 *
 * @param noteDirectTagIds 笔记直接关联标签。
 * @return 父级在前的稳定标签闭包或显式错误。
 */
fun TagloomEditorState.resolveEffectiveTags(noteDirectTagIds: Collection<TagId>): TagResolution =
    TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(
        directTagIds = noteDirectTagIds,
        tags = tagDefinitions.associateBy(TagDefinition::id),
    )

/**
 * 把一个工作区属性绑定到指定超级标签，并保留其他标签 schema。
 *
 * 已存在绑定时保持原约束不变；新绑定追加到当前顺序末尾且默认不产生值。
 *
 * @param tagId 目标标签。
 * @param propertyId 待绑定属性。
 * @return 更新后的完整标签定义。
 */
fun TagloomEditorState.bindPropertyToTag(
    tagId: TagId,
    propertyId: PropertyId,
): TagDefinition {
    require(propertyDefinitions.any { definition -> definition.id == propertyId }) { "属性定义不存在" }
    val tag = requireNotNull(tagDefinitions.firstOrNull { definition -> definition.id == tagId }) { "标签定义不存在" }
    if (tag.propertyBindings.any { binding -> binding.propertyId == propertyId }) return tag
    return tag.copy(
        propertyBindings = tag.propertyBindings + TagPropertyBinding(
            propertyId = propertyId,
            order = tag.propertyBindings.size,
        ),
    )
}
