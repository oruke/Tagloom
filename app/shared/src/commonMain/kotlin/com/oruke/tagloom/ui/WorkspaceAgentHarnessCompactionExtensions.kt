package com.oruke.tagloom.ui

import com.oruke.tagloom.core.ai.AgentContextMeterMetrics
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentContextRefKind
import com.oruke.tagloom.core.ai.AgentTokenMetricsCalculator
import com.oruke.tagloom.core.ai.AgentTokenUsageMetrics
import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiTokenUsage
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.editor.resolveNoteAiAccessPolicy
import kotlinx.coroutines.CancellationException

/**
 * 读取当前工作区生效的 Agent 自动压缩与上下文预算运行偏好。
 */
internal val WorkspaceAgentHarnessController.currentPreferences: AiAgentPreferences
    get() = workspace.agentSettings.snapshot?.config?.preferences ?: AiAgentPreferences()

/**
 * 计算当前选中会话的累计计费 Token 账单视图（`StatsPills` 口径，上下文压缩绝不清零）。
 */
internal val WorkspaceAgentHarnessController.currentUsageMetrics: AgentTokenUsageMetrics
    get() {
        val raw = selectedThreadEntry?.thread?.cumulativeUsage ?: AiTokenUsage()
        return AgentTokenMetricsCalculator.computeUsageMetrics(raw)
    }

/**
 * 计算当前选中会话叠加作曲框输入草稿与新增 `@` 引用后的实时三色分段上下文窗口水位（`ContextMeter` 口径）。
 */
internal val WorkspaceAgentHarnessController.currentContextMeterMetrics: AgentContextMeterMetrics
    get() {
        val thread = selectedThreadEntry?.thread
        val model = effectiveModel
        val prefs = currentPreferences
        val validRefs = activeContextRefs.filterNot { ref ->
            isContextRefLockedInEditor(ref, workspace.editorState, blockedRefIds)
        }
        val hasPersistedBaseline = thread != null &&
            (thread.messages.isNotEmpty() || thread.latestContextBreakdown.usedTokens > 0)
        val persistedRefIds = if (hasPersistedBaseline) {
            thread.activeContextRefs.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        val newlyAddedRefs = validRefs.filter { it.id !in persistedRefIds }
        val refPreviewText = estimateMountedRefsPreviewText(newlyAddedRefs)
        return AgentTokenMetricsCalculator.computeContextMeterMetrics(
            thread = thread,
            model = model,
            preferences = prefs,
            retrievalScope = coerceRetrievalScopeForModel(retrievalScope, model),
            composerDraft = promptDraft,
            activeContextRefs = validRefs,
            resolvedRefText = refPreviewText,
        )
    }

/**
 * 在单篇笔记编辑页首次开启新伴随会话时，若当前笔记非受保护系统页且有效策略非 `LOCKED`，默认生成单篇候选 `@` 引用。
 *
 * @return 可移除的初始候选引用列表。
 */
internal fun WorkspaceAgentHarnessController.defaultCompanionNoteRefs(): List<AgentContextRef> {
    if (allNotesPageActive) return emptyList()
    val doc = workspace.editorState.selectedDocument ?: return emptyList()
    if (SystemDatabaseDefinitions.isProtectedSystemPage(doc.id)) return emptyList()
    val effectivePolicy = workspace.editorState.resolveNoteAiAccessPolicy(doc.note).effectivePolicy
    if (effectivePolicy == AiAccessPolicy.LOCKED) return emptyList()
    return listOf(buildNoteContextRef(doc.id, doc.note.title, emptyList()))
}

/**
 * 从编辑器正文选区浮动栏（`SelectionFormattingToolbar`）或块手柄菜单（`EditorBlockHandle`）一键将稳定 `BlockId` 挂载至右侧伴随栏并聚焦输入框。
 *
 * 严格遵循 `AiAccessPolicy` 门禁：当当前笔记有效策略为 `LOCKED` 时拒绝挂载并弹出提示。
 *
 * @param noteId 当前编辑笔记稳定 ID。
 * @param blockIds 选中的一个或多个稳定段落 ID。
 * @param lockedMessage 禁读策略拦截时的本地化提示文本。
 * @param attachedMessage 成功挂载后的本地化提示文本。
 * @return 成功挂载时返回 `true`；被 `LOCKED` 门禁拦截或目标不存在时返回 `false`。
 */
internal fun WorkspaceAgentHarnessController.attachBlocksFromEditorQuickAction(
    noteId: ObjectId,
    blockIds: List<BlockId>,
    lockedMessage: String,
    attachedMessage: String,
): Boolean {
    val cleanIds = blockIds.distinct()
    if (cleanIds.isEmpty()) return false
    val doc = workspace.editorState.documents.firstOrNull { it.id == noteId } ?: return false
    val policy = workspace.editorState.resolveNoteAiAccessPolicy(doc.note).effectivePolicy
    if (policy == AiAccessPolicy.LOCKED) {
        editorToastMessage = lockedMessage
        return false
    }
    attachNoteRef(doc.id, doc.note.title, cleanIds)
    fullScreenAgentActive = false
    allNotesPageActive = false
    companionVisible = true
    mediumSidebarExpandedOverride = false
    showReturnToAgentPill = false
    composerFocusVersion += 1
    editorToastMessage = attachedMessage
    return true
}

/**
 * 手动对当前选中会话执行两阶段无损上下文压缩（不修改 `lastMessageAt`，保留近期对话、活跃 `@` 引用与未决提案）。
 *
 * @return 成功生成新压缩节点时返回 `true`。
 */
internal suspend fun WorkspaceAgentHarnessController.compactSelectedThreadManually(): Boolean {
    if (compactionBusy || runningThreadId != null) return false
    val entry = selectedThreadEntry ?: return false
    if (entry.thread.messages.isEmpty()) return false
    val gateway = workspace.gateway.agentHarness ?: return false
    val marker = workspace.workspaceSessionMarker
    compactionBusy = true
    return try {
        val updated = gateway.compactThread(
            threadId = entry.id,
            activeContextRefs = activeContextRefs,
        )
        if (workspace.workspaceSessionMarker === marker) {
            replaceThreadInPlace(updated)
            applySelectedThreadToComposer(updated)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    } finally {
        if (workspace.workspaceSessionMarker === marker) {
            compactionBusy = false
        }
    }
}

/**
 * 在纯前端内存中提取作曲框已挂载 `@` 引用对应的近似正文文本，用于实时更新 `ContextMeter` 预估水位。
 */
private fun WorkspaceAgentHarnessController.estimateMountedRefsPreviewText(
    refs: List<AgentContextRef>,
): String {
    if (refs.isEmpty()) return ""
    val docsById = workspace.editorState.documents.associateBy { it.id }
    return buildString {
        refs.forEach { ref ->
            when (ref.kind) {
                AgentContextRefKind.NOTE -> {
                    val doc = ref.noteId?.let(docsById::get)
                    if (doc != null) {
                        appendLine(doc.note.title)
                        doc.note.blocks
                            .filterNot { it.deleted || it.id in ref.excludedBlockIds }
                            .forEach { blk -> blk.extractPlainText()?.let(::appendLine) }
                    } else {
                        appendLine(ref.label)
                    }
                }
                AgentContextRefKind.BLOCKS -> {
                    val doc = ref.noteId?.let(docsById::get)
                    if (doc != null) {
                        val wanted = ref.blockIds.toSet()
                        appendLine(doc.note.title)
                        doc.note.blocks
                            .filter { !it.deleted && it.id in wanted }
                            .forEach { blk -> blk.extractPlainText()?.let(::appendLine) }
                    } else {
                        appendLine(ref.label)
                    }
                }
                AgentContextRefKind.TAG_SCOPE,
                AgentContextRefKind.SAVED_QUERY_SCOPE,
                -> appendLine(ref.label)
            }
        }
    }
}
