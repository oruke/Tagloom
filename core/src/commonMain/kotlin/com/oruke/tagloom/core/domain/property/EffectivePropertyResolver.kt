package com.oruke.tagloom.core.domain.property

import com.oruke.tagloom.core.domain.tag.ResolvedTag
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.ManualPropertyBinding
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.model.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 描述有效属性值当前来自哪个优先级层级。
 */
@Serializable
enum class EffectivePropertyValueSource {
    /** 笔记快照中实际保存的值。 */
    NOTE_VALUE,

    /** 标签提供且实时跟随的动态默认值。 */
    DYNAMIC_DEFAULT,

    /** 需要写入笔记快照后才成为实际值的默认值。 */
    SNAPSHOT_PENDING,

    /** 当前没有任何实际值或默认值。 */
    NONE,
}

/**
 * 描述同一继承深度的多个标签为相同属性提供了不一致绑定。
 */
@Serializable
data class PropertyResolutionConflict(
    /** 发生冲突的属性。 */
    val propertyId: PropertyId,
    /** 提供竞争绑定的标签，按 ID 稳定排序。 */
    val sourceTagIds: List<TagId>,
    /** 稳定冲突错误码。 */
    val code: String,
    /** 面向用户和开发者的中文说明。 */
    val message: String,
)

/**
 * 表示渲染、校验和查询可以共同使用的最终有效属性。
 */
@Serializable
data class EffectiveProperty(
    /** 属性稳定业务标识。 */
    val propertyId: PropertyId,
    /** 工作区属性定义；定义缺失时为空并进入 missingPropertyIds。 */
    val definition: PropertyDefinition?,
    /** 最终生效的标签或笔记手动绑定。 */
    val binding: TagPropertyBinding?,
    /** 提供最终标签绑定的标签；笔记手动绑定时为空。 */
    val sourceTagId: TagId?,
    /** 当前用于渲染的值。 */
    val value: JsonElement?,
    /** 当前值的来源层级。 */
    val valueSource: EffectivePropertyValueSource,
    /** 是否需要把 snapshot 默认值写入下一次笔记快照。 */
    val requiresSnapshot: Boolean,
    /** 合并属性定义、标签和笔记覆盖后的最终样式。 */
    val style: PropertyStyle,
    /** 当前属性是否仍存在需要用户处理的同级标签冲突。 */
    val conflicted: Boolean,
)

/**
 * 汇总有效属性、显式冲突和缺失 schema。
 */
@Serializable
data class EffectivePropertyResult(
    /** 按最终排序值和属性 ID 稳定排列的有效属性。 */
    val properties: List<EffectiveProperty>,
    /** 不允许静默覆盖的同级标签冲突。 */
    val conflicts: List<PropertyResolutionConflict>,
    /** 被绑定或赋值但不存在定义的属性。 */
    val missingPropertyIds: List<PropertyId>,
)

/**
 * 按父标签、子标签、同级冲突和笔记覆盖规则计算最终有效属性。
 */
class EffectivePropertyResolver {
    /**
     * 计算一篇笔记的最终有效属性集合。
     *
     * @param note 待计算属性的笔记 payload。
     * @param tagResolution 已成功解析且父级在前的有效标签。
     * @param tags 工作区标签定义。
     * @param propertyDefinitions 工作区属性定义。
     * @return 有效属性、冲突和缺失属性定义。
     */
    fun resolve(
        note: Note,
        tagResolution: TagResolution.Success,
        tags: Map<TagId, TagDefinition>,
        propertyDefinitions: Map<PropertyId, PropertyDefinition>,
    ): EffectivePropertyResult {
        val candidatesByProperty = collectTagCandidates(tagResolution.tags, tags)
        val manualBindings = note.manualPropertyBindings.associateBy(ManualPropertyBinding::propertyId)
        val allPropertyIds = buildSet {
            addAll(candidatesByProperty.keys)
            addAll(manualBindings.keys)
            addAll(note.propertyValues.keys)
            addAll(note.propertyStyleOverrides.keys)
        }
        val conflicts = mutableListOf<PropertyResolutionConflict>()
        val missingPropertyIds = mutableListOf<PropertyId>()

        val properties = allPropertyIds.map { propertyId ->
            val definition = propertyDefinitions[propertyId]
            if (definition == null) {
                missingPropertyIds += propertyId
            }

            val tagCandidates = candidatesByProperty[propertyId].orEmpty()
            val manualBinding = manualBindings[propertyId]
            val selection = selectBinding(propertyId, tagCandidates, manualBinding)
            selection.conflict?.let(conflicts::add)

            val noteValue = note.propertyValues[propertyId]
            val valueResolution = resolveValue(noteValue?.value, selection.binding)
            val style = (definition?.defaultStyle ?: PropertyStyle())
                .merge(selection.binding?.styleOverride ?: PropertyStyle())
                .merge(note.propertyStyleOverrides[propertyId] ?: PropertyStyle())

            EffectiveProperty(
                propertyId = propertyId,
                definition = definition,
                binding = selection.binding,
                sourceTagId = selection.sourceTagId,
                value = valueResolution.value,
                valueSource = if (noteValue != null) {
                    EffectivePropertyValueSource.NOTE_VALUE
                } else {
                    valueResolution.source
                },
                requiresSnapshot = noteValue == null && valueResolution.requiresSnapshot,
                style = style,
                conflicted = selection.conflict != null,
            )
        }.sortedWith(
            compareBy<EffectiveProperty> { property -> property.binding?.order ?: Int.MAX_VALUE }
                .thenBy { property -> property.propertyId.value },
        )

        return EffectivePropertyResult(
            properties = properties,
            conflicts = conflicts.sortedBy { conflict -> conflict.propertyId.value },
            missingPropertyIds = missingPropertyIds.distinct().sortedBy(PropertyId::value),
        )
    }

    /**
     * 收集每个属性在最大继承深度上的标签绑定候选。
     *
     * `inheritable=false` 的绑定只在笔记直接关联对应标签时生效，避免从祖先泄漏到子标签。
     *
     * @param resolvedTags 父级在前的有效标签。
     * @param tags 工作区标签定义。
     * @return 按属性分组的最大深度候选。
     */
    private fun collectTagCandidates(
        resolvedTags: List<ResolvedTag>,
        tags: Map<TagId, TagDefinition>,
    ): Map<PropertyId, List<BindingCandidate>> {
        val candidates = mutableMapOf<PropertyId, MutableList<BindingCandidate>>()

        resolvedTags.forEach { resolvedTag ->
            val tag = tags[resolvedTag.tagId] ?: return@forEach
            tag.propertyBindings.forEach { binding ->
                if (!binding.inheritable && !resolvedTag.direct) {
                    return@forEach
                }

                val propertyCandidates = candidates.getOrPut(binding.propertyId) { mutableListOf() }
                val currentDepth = propertyCandidates.firstOrNull()?.depth
                when {
                    currentDepth == null || resolvedTag.depth > currentDepth -> {
                        propertyCandidates.clear()
                        propertyCandidates += BindingCandidate(
                            tagId = resolvedTag.tagId,
                            depth = resolvedTag.depth,
                            binding = binding,
                        )
                    }

                    resolvedTag.depth == currentDepth -> propertyCandidates += BindingCandidate(
                        tagId = resolvedTag.tagId,
                        depth = resolvedTag.depth,
                        binding = binding,
                    )
                }
            }
        }

        return candidates.mapValues { (_, value) -> value.sortedBy { candidate -> candidate.tagId.value } }
    }

    /**
     * 应用笔记手动绑定和同级标签冲突规则，选择确定性临时结果。
     *
     * @param propertyId 当前属性。
     * @param candidates 最大继承深度的标签候选。
     * @param manualBinding 笔记手动绑定；存在时优先级最高。
     * @return 最终绑定选择及可选冲突。
     */
    private fun selectBinding(
        propertyId: PropertyId,
        candidates: List<BindingCandidate>,
        manualBinding: ManualPropertyBinding?,
    ): BindingSelection {
        if (manualBinding != null) {
            return BindingSelection(
                binding = manualBinding.toTagBinding(),
                sourceTagId = null,
                conflict = null,
            )
        }

        val selected = candidates.firstOrNull()
        val conflict = if (candidates.map(BindingCandidate::binding).distinct().size > 1) {
            PropertyResolutionConflict(
                propertyId = propertyId,
                sourceTagIds = candidates.map(BindingCandidate::tagId),
                code = "same_depth_property_binding_conflict",
                message = "多个同级标签为属性 ${propertyId.value} 提供了不一致绑定",
            )
        } else {
            null
        }

        return BindingSelection(
            binding = selected?.binding,
            sourceTagId = selected?.tagId,
            conflict = conflict,
        )
    }

    /**
     * 根据标签默认策略计算未覆盖属性的当前值。
     *
     * @param noteValue 笔记已保存值；调用方只在值为空时采用默认来源。
     * @param binding 最终有效绑定。
     * @return 当前值、来源和是否需要写入 snapshot。
     */
    private fun resolveValue(
        noteValue: JsonElement?,
        binding: TagPropertyBinding?,
    ): ValueResolution {
        if (noteValue != null) {
            return ValueResolution(
                value = noteValue,
                source = EffectivePropertyValueSource.NOTE_VALUE,
                requiresSnapshot = false,
            )
        }

        return when (binding?.defaultPolicy) {
            DefaultValuePolicy.DYNAMIC -> ValueResolution(
                value = binding.defaultValue,
                source = EffectivePropertyValueSource.DYNAMIC_DEFAULT,
                requiresSnapshot = false,
            )

            DefaultValuePolicy.SNAPSHOT -> ValueResolution(
                value = binding.defaultValue,
                source = EffectivePropertyValueSource.SNAPSHOT_PENDING,
                requiresSnapshot = binding.defaultValue != null,
            )

            DefaultValuePolicy.NONE,
            null,
            -> ValueResolution(
                value = null,
                source = EffectivePropertyValueSource.NONE,
                requiresSnapshot = false,
            )
        }
    }

    /**
     * 将笔记手动绑定提升为统一解析流程使用的绑定结构。
     *
     * @return 不产生标签默认值的最高优先级绑定。
     */
    private fun ManualPropertyBinding.toTagBinding(): TagPropertyBinding = TagPropertyBinding(
        propertyId = propertyId,
        required = required,
        defaultPolicy = DefaultValuePolicy.NONE,
        readonly = readonly,
        inheritable = false,
        styleOverride = styleOverride,
    )

    /**
     * 描述一个标签提供的属性绑定候选。
     */
    private data class BindingCandidate(
        /** 候选标签。 */
        val tagId: TagId,
        /** 候选标签继承深度。 */
        val depth: Int,
        /** 候选属性绑定。 */
        val binding: TagPropertyBinding,
    )

    /**
     * 描述绑定选择及其显式冲突。
     */
    private data class BindingSelection(
        /** 最终或确定性临时绑定。 */
        val binding: TagPropertyBinding?,
        /** 提供标签绑定的标签。 */
        val sourceTagId: TagId?,
        /** 未解决的同级标签冲突。 */
        val conflict: PropertyResolutionConflict?,
    )

    /**
     * 描述有效属性值的中间解析结果。
     */
    private data class ValueResolution(
        /** 当前值。 */
        val value: JsonElement?,
        /** 默认值来源。 */
        val source: EffectivePropertyValueSource,
        /** 是否需要写入笔记快照。 */
        val requiresSnapshot: Boolean,
    )
}
