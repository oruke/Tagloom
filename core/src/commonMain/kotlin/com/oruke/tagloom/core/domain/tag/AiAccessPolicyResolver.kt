package com.oruke.tagloom.core.domain.tag

import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId

/**
 * 描述单篇笔记或单个笔记类型经过多父祖先链收敛后的有效 AI 访问策略与来源归因。
 */
data class ResolvedAiAccessPolicy(
    /** 最终生效的 AI 访问控制策略（`LOCKED > REFERENCE_ONLY > ALLOW`）。 */
    val effectivePolicy: AiAccessPolicy,
    /** 是否由单篇笔记显式设置覆盖值（而非从笔记类型继承）。 */
    val overriddenOnNote: Boolean,
    /** 当从类型继承时，贡献了当前最严格策略的直接或祖先笔记类型 ID 列表（按子级优先稳定排列）。 */
    val inheritedFromTagIds: List<TagId> = emptyList(),
)

/**
 * 描述修改某个笔记类型的默认 AI 访问策略时对后代类型与未覆盖笔记的影响预览。
 */
data class TagAiPolicyImpactPreview(
    /** 目标笔记类型应用候选策略后自身收敛出的有效策略。 */
    val effectiveTypePolicy: AiAccessPolicy,
    /** 包含目标类型自身在内的受控子类型闭包 ID 集合。 */
    val descendantTagIds: Set<TagId>,
    /** 关联了目标类型或其后代且未单独覆盖策略的笔记总数。 */
    val inheritingNoteCount: Int,
    /** 修改后有效策略实际发生变化的笔记篇数。 */
    val changedNoteCount: Int,
)

/**
 * 解析笔记与笔记类型的多父继承闭包最严格 [AiAccessPolicy]。
 *
 * 规则：
 * 1. 若 [Note.aiAccessPolicy] 非空，直接以单篇笔记显式设定为准（允许放宽或收紧类型默认值）；
 * 2. 若 [Note.aiAccessPolicy] 为空，沿笔记直接关联类型及完整 `parentTagIds` 多父祖先链（含隐式根 [rootTagId]）
 *    广度遍历，按 `LOCKED > REFERENCE_ONLY > ALLOW` 取最严格值；
 * 3. 即便输入标签图存在缺失父节点或异常环路，遍历过程仍保持有界抗环并对所有可达已定义标签执行最严格收敛，绝不因异常降级放行。
 *
 * @param rootTagId 工作区内置根 `Note` 类型的稳定标识。
 */
class AiAccessPolicyResolver(
    private val rootTagId: TagId,
) {
    /**
     * 解析单篇笔记的最终有效 AI 访问策略与继承来源。
     *
     * @param note 待解析笔记载荷。
     * @param tags 当前工作区可用标签定义映射。
     * @return 包含有效策略、是否单篇覆盖及继承来源类型的解析结果。
     */
    fun resolveForNote(
        note: Note,
        tags: Map<TagId, TagDefinition>,
    ): ResolvedAiAccessPolicy {
        val explicit = note.aiAccessPolicy
        if (explicit != null) {
            return ResolvedAiAccessPolicy(
                effectivePolicy = explicit,
                overriddenOnNote = true,
                inheritedFromTagIds = emptyList(),
            )
        }
        val seedTagIds = note.directTagIds.ifEmpty { listOf(rootTagId) }
        return resolveAncestorClosure(seedTagIds, tags)
    }

    /**
     * 解析单个笔记类型叠加其多父祖先链后的有效默认 AI 访问策略。
     *
     * @param tagId 目标笔记类型 ID。
     * @param tags 当前工作区可用标签定义映射。
     * @return 该类型继承收敛后的有效策略与决定性祖先列表。
     */
    fun resolveForTag(
        tagId: TagId,
        tags: Map<TagId, TagDefinition>,
    ): ResolvedAiAccessPolicy = resolveAncestorClosure(listOf(tagId), tags)

    /**
     * 预览将指定笔记类型的默认策略调整为 [candidatePolicy] 后的继承影响面。
     *
     * @param targetTagId 待编辑的笔记类型 ID。
     * @param candidatePolicy 拟保存的新默认策略。
     * @param tags 当前工作区可用标签定义映射。
     * @param notes 当前工作区活动笔记列表。
     * @return 后代闭包规模、继承笔记数及有效策略发生变更的笔记数。
     */
    fun previewTagImpact(
        targetTagId: TagId,
        candidatePolicy: AiAccessPolicy,
        tags: Map<TagId, TagDefinition>,
        notes: Collection<Pair<ObjectId, Note>>,
    ): TagAiPolicyImpactPreview {
        val target = tags[targetTagId]
        val updatedTags = if (target != null) {
            tags + (targetTagId to target.copy(aiAccessPolicy = candidatePolicy))
        } else {
            tags
        }
        val effectiveTypePolicy = resolveForTag(targetTagId, updatedTags).effectivePolicy
        val descendants = tags.keys.filterTo(mutableSetOf()) { candidateId ->
            targetTagId in collectReachableAncestors(listOf(candidateId), updatedTags).map(TagDefinition::id)
        }
        var inheritingCount = 0
        var changedCount = 0
        notes.forEach { (_, note) ->
            if (note.aiAccessPolicy == null) {
                val reachableIds = collectReachableAncestors(
                    note.directTagIds.ifEmpty { listOf(rootTagId) },
                    updatedTags,
                ).mapTo(mutableSetOf(), TagDefinition::id)
                if (targetTagId in reachableIds) {
                    inheritingCount += 1
                    val before = resolveForNote(note, tags).effectivePolicy
                    val after = resolveForNote(note, updatedTags).effectivePolicy
                    if (before != after) {
                        changedCount += 1
                    }
                }
            }
        }
        return TagAiPolicyImpactPreview(
            effectiveTypePolicy = effectiveTypePolicy,
            descendantTagIds = descendants,
            inheritingNoteCount = inheritingCount,
            changedNoteCount = changedCount,
        )
    }

    /**
     * 在给定入口标签集合上遍历完整祖先闭包并计算最严格策略。
     *
     * @param entryTagIds 起始标签集合。
     * @param tags 工作区标签映射。
     * @return 收敛后的有效策略及贡献该策略的标签列表。
     */
    private fun resolveAncestorClosure(
        entryTagIds: Collection<TagId>,
        tags: Map<TagId, TagDefinition>,
    ): ResolvedAiAccessPolicy {
        val orderedTags = collectReachableAncestors(entryTagIds, tags)
        val strictest = AiAccessPolicy.strictestOf(orderedTags.map(TagDefinition::aiAccessPolicy))
        val contributors = orderedTags
            .filter { tag -> tag.aiAccessPolicy == strictest }
            .map(TagDefinition::id)
        return ResolvedAiAccessPolicy(
            effectivePolicy = strictest,
            overriddenOnNote = false,
            inheritedFromTagIds = contributors,
        )
    }

    /**
     * 按广度优先（子级在前、祖先在后）抗环收集全部可达已定义标签，确保隐式根 `Note` 类型始终参与收敛。
     *
     * @param entryTagIds 入口标签列表。
     * @param tags 工作区标签映射。
     * @return 去重且顺序确定性的可达标签定义列表。
     */
    private fun collectReachableAncestors(
        entryTagIds: Collection<TagId>,
        tags: Map<TagId, TagDefinition>,
    ): List<TagDefinition> {
        val queue = ArrayDeque<TagId>()
        val visited = linkedSetOf<TagId>()
        entryTagIds.sortedBy(TagId::value).forEach { id ->
            if (visited.add(id)) {
                queue.addLast(id)
            }
        }
        val resolved = mutableListOf<TagDefinition>()
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            val definition = tags[currentId] ?: continue
            resolved += definition
            val parents = when {
                definition.id == rootTagId -> emptyList()
                definition.parentTagIds.isEmpty() -> listOf(rootTagId)
                else -> definition.parentTagIds.sortedBy(TagId::value)
            }
            parents.forEach { parentId ->
                if (visited.add(parentId)) {
                    queue.addLast(parentId)
                }
            }
        }
        return resolved
    }
}
