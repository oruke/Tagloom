package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.domain.tag.AiAccessPolicyResolver
import com.oruke.tagloom.core.domain.tag.ResolvedAiAccessPolicy
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 绑定了当前工作区活动标签目录的 AI 访问策略解析器包装，便于会话内多处直接解析单篇笔记有效策略。
 *
 * @param tags 当前工作区活动 `TagDefinition` 映射。
 * @param resolver 多父祖先链最严格策略解析器。
 */
internal class BoundAiAccessPolicyResolver(
    val tags: Map<TagId, TagDefinition>,
    private val resolver: AiAccessPolicyResolver = AiAccessPolicyResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
) {
    /**
     * 解析给定笔记的最终有效 AI 访问控制策略（`LOCKED > REFERENCE_ONLY > ALLOW`）。
     *
     * @param note 待解析笔记载荷。
     * @return 收敛后的有效 [AiAccessPolicy]。
     */
    fun resolveEffectiveNotePolicy(note: Note): AiAccessPolicy =
        resolver.resolveForNote(note, tags).effectivePolicy

    /**
     * 解析给定笔记的有效策略及继承来源归因。
     *
     * @param note 待解析笔记载荷。
     * @return 完整的 [ResolvedAiAccessPolicy]。
     */
    fun resolveNote(note: Note): ResolvedAiAccessPolicy =
        resolver.resolveForNote(note, tags)
}

/**
 * 从当前已认证工作区 schema 目录构造多父祖先链最严格收敛的 [BoundAiAccessPolicyResolver]。
 *
 * 调用方须处于已解锁会话内（建议在 `sessionMutex` 保护下调用）。
 *
 * @return 基于当前活动 `TagDefinition` 目录初始化的策略解析器。
 */
internal suspend fun DesktopWorkspaceSession.loadAiAccessPolicyResolver(): BoundAiAccessPolicyResolver {
    val tags = schemaStore.load(unlocked).tags.map { it.payload }.associateBy { it.id }
    return BoundAiAccessPolicyResolver(tags)
}
