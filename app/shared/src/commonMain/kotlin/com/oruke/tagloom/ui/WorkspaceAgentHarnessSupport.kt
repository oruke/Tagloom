package com.oruke.tagloom.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentContextRefKind
import com.oruke.tagloom.core.ai.AgentSourceEntry
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.appendAssistantMarkdownToSelectedNote
import com.oruke.tagloom.editor.createNoteFromAssistantMarkdown
import com.oruke.tagloom.editor.resolveNoteAiAccessPolicy
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import com.oruke.tagloom.platform.WorkspaceAgentCredentialStatus

/**
 * 在解锁工作区树内共享唯一的双形态 Agent 控制器（预览环境为空时安全降级）。
 */
internal val LocalWorkspaceAgentHarness = staticCompositionLocalOf<WorkspaceAgentHarnessController?> { null }

/**
 * 描述当前作曲框所选模型与连接凭据的就绪状态，用于温和内联横幅引导而非阻断进入页面。
 */
internal enum class AgentReadinessStatus {
    /** 已具备启用模型与就绪凭据，可直接发起流式对话。 */
    READY,

    /** 工作区尚未配置任何启用的模型。 */
    NO_ENABLED_MODEL,

    /** 所选模型对应的 API 连接在当前设备上缺少 API Key。 */
    MISSING_LOCAL_API_KEY,
}

/**
 * 描述点击回答正文中的 `[1]` 来源角标后触发的原位段落滚动与高亮指令。
 */
internal data class AgentCitationHighlight(
    /** 目标笔记稳定 ID。 */
    val noteId: ObjectId,
    /** 可选目标段落稳定 ID；为空时仅打开笔记顶部。 */
    val blockId: BlockId?,
    /** 每次点击递增的请求版本号，确保重复点击同一角标也能重新滚动聚焦。 */
    val version: Int,
)

/**
 * 计算作曲框当前选中的有效模型配置；当会话未指定或原模型已停用时自动回退至默认或首个启用模型。
 *
 * @param snapshot 当前脱敏配置快照。
 * @param preferredModelId 会话或作曲框当前记录的模型复合 ID。
 * @return 匹配到的启用模型配置；无启用模型时为空。
 */
internal fun resolveEffectiveAgentModel(
    snapshot: WorkspaceAgentConfigSnapshot?,
    preferredModelId: String?,
): AiModelConfig? {
    val enabled = snapshot?.config?.models?.filter { it.enabled }.orEmpty()
    if (enabled.isEmpty()) return null
    val defaultId = snapshot?.config?.preferences?.defaultModelId
    return enabled.firstOrNull { it.id == preferredModelId }
        ?: enabled.firstOrNull { it.id == defaultId }
        ?: enabled.first()
}

/**
 * 判定当前模型与设备凭据组合的就绪状态。
 *
 * @param snapshot 当前脱敏配置与凭据状态快照。
 * @param model 当前解析出的有效模型。
 * @return [AgentReadinessStatus] 枚举。
 */
internal fun evaluateAgentReadiness(
    snapshot: WorkspaceAgentConfigSnapshot?,
    model: AiModelConfig?,
): AgentReadinessStatus {
    if (model == null) return AgentReadinessStatus.NO_ENABLED_MODEL
    val credStatus = snapshot?.credentialStatuses?.get(model.connectionId)
    val missingCred = credStatus == WorkspaceAgentCredentialStatus.MISSING_LOCAL_KEY ||
        credStatus == WorkspaceAgentCredentialStatus.MISSING_ENV_VAR ||
        credStatus == WorkspaceAgentCredentialStatus.ENV_VAR_UNSUPPORTED
    if (missingCred) {
        return AgentReadinessStatus.MISSING_LOCAL_API_KEY
    }
    return AgentReadinessStatus.READY
}

/**
 * 计算模型切换后仍然合法的思考强度档位。
 *
 * @param current 当前思考档位。
 * @param model 目标模型配置。
 * @return 兼容的 [AiThinkingEffort]。
 */
internal fun coerceThinkingEffortForModel(
    current: AiThinkingEffort,
    model: AiModelConfig?,
): AiThinkingEffort {
    val supported = model?.supportedThinkingEfforts ?: setOf(AiThinkingEffort.OFF)
    return if (current in supported) current else AiThinkingEffort.OFF
}

/**
 * 计算当前模型允许的实际检索范围（`CHAT_ONLY` 模型强制回退为 `CITED_ONLY`）。
 *
 * @param requested 用户选中的检索范围。
 * @param model 当前有效模型。
 * @return 实际生效的 [AiRetrievalScope]。
 */
internal fun coerceRetrievalScopeForModel(
    requested: AiRetrievalScope,
    model: AiModelConfig?,
): AiRetrievalScope = if (model?.toolsState == AiModelToolsState.CHAT_ONLY) {
    AiRetrievalScope.CITED_ONLY
} else {
    requested
}

/**
 * 检查作曲框已挂载的引用在当前编辑器最新策略下是否已被收紧为 `LOCKED` 禁读。
 *
 * @param ref 待检查的上下文引用。
 * @param editorState 当前编辑器文档与类型状态。
 * @param explicitlyBlockedIds 后端或当轮刚返回的拦截 ID 集合。
 * @return 处于禁读拦截态时返回 `true`。
 */
internal fun isContextRefLockedInEditor(
    ref: AgentContextRef,
    editorState: TagloomEditorState,
    explicitlyBlockedIds: Set<String>,
): Boolean {
    if (ref.id in explicitlyBlockedIds) return true
    if (ref.kind != AgentContextRefKind.NOTE && ref.kind != AgentContextRefKind.BLOCKS) return false
    val doc = editorState.documents.firstOrNull { it.id == ref.noteId } ?: return false
    return editorState.resolveNoteAiAccessPolicy(doc.note).effectivePolicy == AiAccessPolicy.LOCKED
}

/**
 * 在不打乱既有访问顺序的前提下，将工作区文档集合同步进「最近笔记」有序 ID 列表。
 *
 * @param existingOrder 当前维护的最近笔记 ID 序列。
 * @param documents 当前工作区全部文档。
 * @return 剔除已删除文档并在末尾补齐新文档后的有序 ID 列表。
 */
internal fun syncStableRecentNoteOrder(
    existingOrder: List<ObjectId>,
    documents: List<EditorDocument>,
): List<ObjectId> {
    val byId = documents.associateBy { it.id }
    val kept = existingOrder.filter { it in byId }
    val keptSet = kept.toSet()
    val additions = documents
        .filter { it.id !in keptSet }
        .sortedWith(compareByDescending<EditorDocument> { it.updatedAt }.thenBy { it.id.value })
        .map { it.id }
    return if (existingOrder.isEmpty()) additions else kept + additions
}

/**
 * 构造单篇笔记或指定段落子集的 `@` 上下文引用对象。
 *
 * @param noteId 目标笔记稳定 ID。
 * @param title 笔记展示标题。
 * @param blockIds 可选限定的稳定段落 ID 列表。
 * @return 结构化 [AgentContextRef]。
 */
internal fun buildNoteContextRef(
    noteId: ObjectId,
    title: String,
    blockIds: List<BlockId> = emptyList(),
): AgentContextRef {
    val cleanTitle = title.ifBlank { "未命名笔记" }
    return if (blockIds.isEmpty()) {
        AgentContextRef(id = "note:${noteId.value}", kind = AgentContextRefKind.NOTE, noteId = noteId, label = cleanTitle)
    } else {
        val cleanBlocks = blockIds.distinct()
        val digestHex = cleanBlocks.joinToString("|") { it.value }.hashCode().toUInt().toString(16)
        AgentContextRef(
            id = "blocks:${noteId.value}:${cleanBlocks.size}:$digestHex",
            kind = AgentContextRefKind.BLOCKS,
            noteId = noteId,
            blockIds = cleanBlocks,
            label = "$cleanTitle · ${cleanBlocks.size} 段",
        )
    }
}

/**
 * 将新挂载的单篇笔记或段落引用合并进现有活跃引用列表：同篇笔记多次挂载精确段落自动去重合并为 `N 段` 单枚胶囊。
 *
 * @param existing 当前作曲框活跃 `@` 引用列表。
 * @param noteId 目标笔记稳定 ID。
 * @param title 笔记标题。
 * @param blockIds 待挂载的稳定段落列表；为空表示整篇笔记挂载。
 * @return 合并更新后的活跃引用列表。
 */
internal fun mergeNoteOrBlocksContextRef(
    existing: List<AgentContextRef>,
    noteId: ObjectId,
    title: String,
    blockIds: List<BlockId>,
): List<AgentContextRef> {
    if (blockIds.isEmpty()) {
        val wholeRef = buildNoteContextRef(noteId, title, emptyList())
        val withoutBlocks = existing.filterNot { it.kind == AgentContextRefKind.BLOCKS && it.noteId == noteId }
        return if (withoutBlocks.any { it.id == wholeRef.id }) {
            withoutBlocks.map { if (it.id == wholeRef.id) wholeRef else it }
        } else {
            withoutBlocks + wholeRef
        }
    }
    val priorBlocksIdx = existing.indexOfFirst { it.kind == AgentContextRefKind.BLOCKS && it.noteId == noteId }
    return if (priorBlocksIdx >= 0) {
        val mergedIds = (existing[priorBlocksIdx].blockIds + blockIds).distinct()
        val mergedRef = buildNoteContextRef(noteId, title, mergedIds)
        existing.mapIndexed { idx, item -> if (idx == priorBlocksIdx) mergedRef else item }
    } else {
        val nextRef = buildNoteContextRef(noteId, title, blockIds)
        if (existing.any { it.id == nextRef.id }) existing else existing + nextRef
    }
}

/**
 * 构造笔记类型闭包范围引用（严格不带 `#` 前缀）。
 *
 * @param tagId 目标笔记类型 ID。
 * @param tagName 类型展示名称。
 * @return 结构化 [AgentContextRef]。
 */
internal fun buildTagScopeContextRef(tagId: TagId, tagName: String): AgentContextRef = AgentContextRef(
    id = "tag:${tagId.value}",
    kind = AgentContextRefKind.TAG_SCOPE,
    tagId = tagId,
    label = tagName,
)

/**
 * 构造已保存查询范围引用。
 *
 * @param queryId 目标保存查询 ID。
 * @param queryName 查询展示名称。
 * @return 结构化 [AgentContextRef]。
 */
internal fun buildSavedQueryContextRef(queryId: ObjectId, queryName: String): AgentContextRef = AgentContextRef(
    id = "query:${queryId.value}",
    kind = AgentContextRefKind.SAVED_QUERY_SCOPE,
    queryId = queryId,
    label = queryName,
)

/** 重命名会话（保持 `lastMessageAt` 不变，绝不跳序）。 */
internal suspend fun WorkspaceAgentHarnessController.renameThread(threadId: ObjectId, newTitle: String) {
    val gateway = workspace.gateway.agentHarness ?: return
    val trimmed = newTitle.trim()
    if (trimmed.isEmpty()) return
    replaceThreadInPlace(gateway.updateThreadState(threadId = threadId, title = trimmed))
}

/** 归档或恢复会话（保持 `lastMessageAt` 不变）。 */
internal suspend fun WorkspaceAgentHarnessController.setThreadArchived(threadId: ObjectId, archived: Boolean) {
    val gateway = workspace.gateway.agentHarness ?: return
    replaceThreadInPlace(gateway.updateThreadState(threadId = threadId, archived = archived))
}

/** 删除持久化或无痕会话。 */
internal suspend fun WorkspaceAgentHarnessController.deleteThread(threadId: ObjectId) {
    val gateway = workspace.gateway.agentHarness ?: return
    gateway.deleteThread(threadId)
    threads = threads.filterNot { it.id == threadId }
    if (selectedThreadId == threadId) {
        val fallback = threads.firstOrNull { !it.thread.archived }
        selectedThreadId = fallback?.id
        fallback?.let(::applySelectedThreadToComposer)
    }
}

/** 点击来源角标跳转至目标笔记与精确段落，并在窄屏提供返回对话胶囊。 */
internal fun WorkspaceAgentHarnessController.jumpToCitation(entry: AgentSourceEntry, isCompactViewport: Boolean) {
    if (workspace.editorState.documents.none { it.id == entry.noteId }) return
    allNotesPageActive = false
    if (fullScreenAgentActive) {
        fullScreenAgentActive = false
        companionVisible = !isCompactViewport
    }
    if (isCompactViewport) {
        companionVisible = false
        showReturnToAgentPill = true
    }
    workspace.editorState.selectDocument(entry.noteId)
    promoteRecentNote(entry.noteId)
    highlightCounter += 1
    highlightedCitation = AgentCitationHighlight(entry.noteId, entry.blockId, highlightCounter)
}

/** 将助手回答追加到当前活动笔记末尾并触发保存。 */
internal fun WorkspaceAgentHarnessController.appendMessageToCurrentNote(markdown: String, onSave: () -> Unit): Boolean {
    val ok = workspace.editorState.appendAssistantMarkdownToSelectedNote(markdown)
    if (ok) {
        workspace.editorState.selectedDocumentId?.let(::promoteRecentNote)
        onSave()
    }
    return ok
}

/** 将助手回答存为一篇全新笔记并打开展示。 */
internal fun WorkspaceAgentHarnessController.saveMessageAsNewNote(
    markdown: String,
    suggestedTitle: String,
    onSave: () -> Unit,
): ObjectId {
    val newId = workspace.editorState.createNoteFromAssistantMarkdown(suggestedTitle, markdown)
    promoteRecentNote(newId)
    allNotesPageActive = false
    if (fullScreenAgentActive) {
        fullScreenAgentActive = false
        companionVisible = true
    }
    onSave()
    return newId
}
