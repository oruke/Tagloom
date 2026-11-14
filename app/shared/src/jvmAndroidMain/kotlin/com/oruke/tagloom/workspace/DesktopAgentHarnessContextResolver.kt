package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.AgentNotePolicyGate
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentContextRefKind
import com.oruke.tagloom.core.ai.AgentRawSourceCandidate
import com.oruke.tagloom.core.ai.AgentResolvedContextPayload
import com.oruke.tagloom.core.ai.AiContextBudget
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.external.ExternalToolArguments
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonObject

/**
 * 保存单步内置只读工具执行时的显式挂载单篇集合与受控发现白名单。
 */
internal data class ScopedReadContext(
    /** 用户通过 `@` 单篇笔记或段落显式挂载的笔记 ID 集合（允许直读 `REFERENCE_ONLY`）。 */
    val explicitlyCitedNoteIds: Set<ObjectId>,
    /** `CITED_ONLY` 模式下允许发现的可读笔记 ID 集合；`ALL_NOTES` 模式下为空表示全工作区 `ALLOW` 笔记。 */
    val discoverableAllowList: Set<ObjectId>?,
)

/**
 * 负责在已解锁会话互斥锁内解析内置 Agent 的 `@` 显式引用上下文与检索范围门禁。
 *
 * @param session 当前已解锁的本地工作区会话。
 */
internal class DesktopAgentHarnessContextResolver(
    private val session: DesktopWorkspaceSession,
) {
    private val tagResolver = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)

    /**
     * 在持有会话锁的前提下解析 `@` 引用，拦截 `LOCKED` 笔记并按单轮预算组装上下文块与来源候选。
     *
     * @param refs 作曲框挂载的引用列表。
     * @param budget 单轮上下文预算。
     * @return 结构化显式上下文载荷。
     */
    suspend fun resolveLocked(
        refs: List<AgentContextRef>,
        budget: AiContextBudget,
    ): AgentResolvedContextPayload {
        if (refs.isEmpty()) {
            return AgentResolvedContextPayload("", emptyList(), emptySet(), false)
        }
        val notesById = session.loadActiveNotes()
            .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }
            .associateBy { it.id }
        val policyResolver = session.loadAiAccessPolicyResolver()
        val savedQueriesById = session.savedQueryLoader.load(session.unlocked).associateBy { it.header.objectId }

        val blockedRefIds = linkedSetOf<String>()
        val sources = mutableListOf<AgentRawSourceCandidate>()
        val sb = StringBuilder()
        var usedNotes = 0
        var usedBlocks = 0
        var truncated = false

        for (ref in refs) {
            when (ref.kind) {
                AgentContextRefKind.NOTE, AgentContextRefKind.BLOCKS -> {
                    val noteId = ref.noteId ?: continue
                    val stored = notesById[noteId] ?: continue
                    if (policyResolver.resolveEffectiveNotePolicy(stored.snapshot.payload) == AiAccessPolicy.LOCKED) {
                        blockedRefIds += ref.id
                        continue
                    }
                    if (usedNotes >= budget.maxNotes || sb.length >= budget.maxCharacters) {
                        truncated = true
                        continue
                    }
                    val note = stored.snapshot.payload
                    val revision = stored.snapshot.header.revision.externalText()
                    val selectedBlocks = selectReferencedBlocks(note.blocks, ref)
                    usedNotes += 1
                    sb.appendLine("### 引用笔记：${note.title} (noteId=${noteId.value}, revision=$revision)")
                    if (selectedBlocks.isEmpty()) {
                        sources += AgentRawSourceCandidate(noteId, note.title, null, revision, note.title)
                    } else {
                        for (block in selectedBlocks) {
                            if (usedBlocks >= budget.maxBlocks || sb.length >= budget.maxCharacters) {
                                truncated = true
                                break
                            }
                            val text = block.extractPlainText().orEmpty().trim()
                            if (text.isEmpty()) continue
                            val remaining = (budget.maxCharacters - sb.length).coerceAtLeast(0)
                            if (remaining == 0) {
                                truncated = true
                                break
                            }
                            val clipped = if (text.length > remaining) {
                                truncated = true
                                text.take(remaining)
                            } else {
                                text
                            }
                            usedBlocks += 1
                            sb.appendLine("- [blockId=${block.id.value}] $clipped")
                            sources += AgentRawSourceCandidate(noteId, note.title, block.id, revision, clipped)
                        }
                    }
                }
                AgentContextRefKind.TAG_SCOPE -> {
                    val tagId = ref.tagId ?: continue
                    val tagName = policyResolver.tags[tagId]?.name ?: ref.label
                    sb.appendLine("### 已挂载笔记类型检索范围：$tagName (tagId=${tagId.value}，含子类型闭包，仅限可读笔记)")
                }
                AgentContextRefKind.SAVED_QUERY_SCOPE -> {
                    val queryId = ref.queryId ?: continue
                    val queryName = savedQueriesById[queryId]?.payload?.name ?: ref.label
                    sb.appendLine("### 已挂载保存查询检索范围：$queryName (queryId=${queryId.value}，仅限可读笔记)")
                }
            }
        }
        return AgentResolvedContextPayload(sb.toString().trim(), sources, blockedRefIds, truncated)
    }

    /**
     * 根据当前激活的检索范围与 `@` 引用集合构建受控发现白名单。
     */
    suspend fun buildScopedReadContextLocked(
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
        activeNotes: List<DesktopStoredNote>,
        policyResolver: BoundAiAccessPolicyResolver,
    ): ScopedReadContext {
        val explicitlyCited = activeRefs.mapNotNull { ref ->
            when (ref.kind) {
                AgentContextRefKind.NOTE, AgentContextRefKind.BLOCKS -> ref.noteId
                else -> null
            }
        }.toSet()
        if (retrievalScope == AiRetrievalScope.ALL_NOTES) {
            return ScopedReadContext(explicitlyCitedNoteIds = explicitlyCited, discoverableAllowList = null)
        }
        val scopedIds = linkedSetOf<ObjectId>()
        explicitlyCited.forEach { noteId ->
            val stored = activeNotes.firstOrNull { it.id == noteId } ?: return@forEach
            if (AgentNotePolicyGate.canDiscover(policyResolver.resolveEffectiveNotePolicy(stored.snapshot.payload))) {
                scopedIds += noteId
            }
        }
        val scopedTagIds = activeRefs.mapNotNull { ref ->
            ref.tagId.takeIf { ref.kind == AgentContextRefKind.TAG_SCOPE }
        }.toSet()
        if (scopedTagIds.isNotEmpty()) {
            activeNotes.forEach { stored ->
                val resolved = tagResolver.resolve(stored.snapshot.payload.directTagIds, policyResolver.tags)
                val matchesTag = (resolved as? TagResolution.Success)?.tags?.any { it.tagId in scopedTagIds } == true ||
                    stored.snapshot.payload.directTagIds.any { it in scopedTagIds }
                if (matchesTag && AgentNotePolicyGate.canDiscover(policyResolver.resolveEffectiveNotePolicy(stored.snapshot.payload))) {
                    scopedIds += stored.id
                }
            }
        }
        val scopedQueryIds = activeRefs.mapNotNull { ref ->
            ref.queryId.takeIf { ref.kind == AgentContextRefKind.SAVED_QUERY_SCOPE }
        }
        if (scopedQueryIds.isNotEmpty()) {
            val savedQueries = session.savedQueryLoader.load(session.unlocked).associateBy { it.header.objectId }
            for (queryId in scopedQueryIds) {
                val saved = savedQueries[queryId]?.payload ?: continue
                val hits = session.projection.index.search(
                    SearchIndexQuery(
                        text = saved.text,
                        tagIds = saved.tagIds.toSet(),
                        propertyFilters = saved.propertyFilters.map { PropertyIndexFilter(it.propertyId, it.canonicalValue) },
                        limit = 500,
                        allowedAiPolicies = setOf(AiAccessPolicy.ALLOW),
                    ),
                )
                hits.forEach { hit ->
                    if (!SystemDatabaseDefinitions.isProtectedSystemPage(hit.noteId)) {
                        scopedIds += hit.noteId
                    }
                }
            }
        }
        return ScopedReadContext(explicitlyCitedNoteIds = explicitlyCited, discoverableAllowList = scopedIds)
    }

    /**
     * 校验按 `noteId` 直接读取的内置门禁（覆盖 `LOCKED` 禁读、`REFERENCE_ONLY` 仅显式挂载可读与检索范围校验）。
     */
    fun resolveAuthorizedDirectNote(
        args: JsonObject,
        notesById: Map<ObjectId, DesktopStoredNote>,
        policyResolver: BoundAiAccessPolicyResolver,
        retrievalScope: AiRetrievalScope,
        scopeCtx: ScopedReadContext,
    ): DesktopStoredNote {
        val noteId = ObjectId(ExternalToolArguments.string(args, "noteId", true)!!)
        if (SystemDatabaseDefinitions.isProtectedSystemPage(noteId)) {
            throw ExternalCallException("target_out_of_scope")
        }
        val stored = notesById[noteId] ?: throw ExternalCallException("not_found")
        val policy = policyResolver.resolveEffectiveNotePolicy(stored.snapshot.payload)
        val denial = AgentNotePolicyGate.checkInternalRead(
            effectivePolicy = policy,
            retrievalScope = retrievalScope,
            explicitlyCited = noteId in scopeCtx.explicitlyCitedNoteIds,
            inCitedScopeFilter = scopeCtx.discoverableAllowList?.contains(noteId) == true,
        )
        if (denial != null) {
            throw ExternalCallException(denial.name.lowercase())
        }
        return stored
    }

    /** 按引用规则过滤未删除活动段落。 */
    private fun selectReferencedBlocks(blocks: List<Block>, ref: AgentContextRef): List<Block> {
        val includeSet = ref.blockIds.toSet()
        val excludeSet = ref.excludedBlockIds.toSet()
        return blocks.filter { block ->
            !block.deleted &&
                (includeSet.isEmpty() || block.id in includeSet) &&
                block.id !in excludeSet
        }
    }
}
