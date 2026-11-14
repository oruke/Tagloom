package com.oruke.tagloom.ui

import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItem
import com.oruke.tagloom.core.ai.AgentProposalKind
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.editor.EditorDocument
import kotlinx.coroutines.CancellationException

/**
 * 描述待审阅修改提案与当前编辑器内目标笔记快照之间的乐观锁或未保存草稿冲突类型。
 */
internal enum class AgentProposalConflictKind {
    /** 基准一致（或属于新建笔记草稿提案），可直接原子提交所选变更。 */
    NONE,

    /** 目标笔记已确认的权威 `revision` 晚于提案冻结时的 `expectedRevision`。 */
    STALE_REVISION,

    /** 目标笔记在当前编辑器内存中存在尚未落盘的修改，禁止直接覆盖手写草稿。 */
    UNSAVED_EDITOR_DIRTY,

    /** 目标笔记在当前工作区中不存在或已被移除。 */
    TARGET_MISSING,
}

/**
 * 描述修改提案冲突检测结果，供提案卡片与原位对照审阅横幅统一渲染。
 */
internal data class AgentProposalConflictState(
    /** 冲突分类。 */
    val kind: AgentProposalConflictKind,
    /** 当前目标笔记在编辑器中的最新权威修订号文本（若存在）。 */
    val currentRevision: String? = null,
) {
    /** 当前是否处于阻止直接落盘覆盖的冲突拦截态。 */
    val hasConflict: Boolean
        get() = kind != AgentProposalConflictKind.NONE
}

/** 当前会话内正在处于双方位共享 Diff 对照审阅态的待确认提案；无活动审阅或已结案时为空。 */
internal val WorkspaceAgentHarnessController.activeReviewProposal: AgentMutationProposal?
    get() {
        val targetId = activeReviewProposalId ?: return null
        return selectedThreadEntry?.thread?.proposals?.firstOrNull {
            it.id == targetId && it.status == AgentProposalStatus.PENDING
        }
    }

/**
 * 判断笔记编辑页左侧正文区当前是否应渲染指定文档的原位共享 Diff 审阅画布。
 *
 * @param documentId 当前编辑器选中文档 ID。
 * @return 若处于非全屏伴随态且存在匹配该文档（或新建笔记提案）的活动审阅提案则返回该提案，否则为空。
 */
internal fun WorkspaceAgentHarnessController.activeReviewProposalForEditor(
    documentId: ObjectId,
): AgentMutationProposal? {
    if (fullScreenAgentActive || allNotesPageActive) return null
    val proposal = activeReviewProposal ?: return null
    return when (proposal.kind) {
        AgentProposalKind.PATCH_NOTE -> proposal.takeIf { it.noteId == documentId }
        AgentProposalKind.CREATE_NOTE -> proposal
    }
}

/**
 * 查询某条助手消息关联的结构化修改提案列表（兼顾流式进行中预演态与已完成会话快照）。
 *
 * @param messageId 助手回复消息稳定 ID。
 * @param isStreaming 当前卡片是否处于流式生成态。
 * @return 按生成顺序排列的提案列表。
 */
internal fun WorkspaceAgentHarnessController.proposalsForMessage(
    messageId: String,
    isStreaming: Boolean,
): List<AgentMutationProposal> {
    if (isStreaming) {
        return liveProgress?.proposals?.filter { it.messageId == messageId }.orEmpty()
    }
    return selectedThreadEntry?.thread?.proposals?.filter { it.messageId == messageId }.orEmpty()
}

/**
 * 将长 UUID 形式的修订号（如 `0e847b9b-ff13-476f-b4af-6e1f26de7acf:3`）压缩为适合徽标展示的紧凑前缀（`0e847b9b:3`），
 * 不影响底层精确修订号比对。
 *
 * @param revision 原始修订号字符串。
 * @return 紧凑可读的展示修订号。
 */
internal fun formatShortRevision(revision: String?): String {
    if (revision.isNullOrBlank()) return "?"
    val colonIdx = revision.lastIndexOf(':')
    if (colonIdx <= 12) return revision
    return "${revision.substring(0, 8)}${revision.substring(colonIdx)}"
}

/**
 * 实时对比提案冻结的 `expectedRevision` 与编辑器内目标笔记的修订号及未保存脏状态。
 *
 * @param proposal 待评估的修改提案。
 * @return 精确冲突评估结果。
 */
internal fun WorkspaceAgentHarnessController.evaluateProposalConflict(
    proposal: AgentMutationProposal,
): AgentProposalConflictState {
    if (proposal.status != AgentProposalStatus.PENDING || proposal.kind == AgentProposalKind.CREATE_NOTE) {
        return AgentProposalConflictState(AgentProposalConflictKind.NONE)
    }
    val doc = workspace.editorState.documents.firstOrNull { it.id == proposal.noteId }
        ?: return AgentProposalConflictState(AgentProposalConflictKind.TARGET_MISSING)
    val currentRev = doc.revision?.let { "${it.actorId.value}:${it.sequence}" }
    if (doc.dirty) {
        return AgentProposalConflictState(AgentProposalConflictKind.UNSAVED_EDITOR_DIRTY, currentRev)
    }
    if (currentRev != proposal.expectedRevision) {
        return AgentProposalConflictState(AgentProposalConflictKind.STALE_REVISION, currentRev)
    }
    return AgentProposalConflictState(AgentProposalConflictKind.NONE, currentRev)
}

/**
 * 打开指定待确认提案的双方位共享 Diff 审阅视图：
 * - 全屏 Agent 工作台下在右侧滑出对照面板；
 * - 笔记编辑页伴随模式下自动切换左侧主舞台至目标笔记并原位展开 Diff。
 *
 * @param proposalId 目标提案稳定 ID。
 */
internal fun WorkspaceAgentHarnessController.openProposalDiff(proposalId: String) {
    val proposal = selectedThreadEntry?.thread?.proposals?.firstOrNull {
        it.id == proposalId && it.status == AgentProposalStatus.PENDING
    } ?: return
    proposalNoticeCode = null
    activeReviewProposalId = proposal.id
    if (!fullScreenAgentActive) {
        allNotesPageActive = false
        companionVisible = true
        showReturnToAgentPill = false
        if (proposal.kind == AgentProposalKind.PATCH_NOTE &&
            workspace.editorState.documents.any { it.id == proposal.noteId }
        ) {
            workspace.editorState.selectDocument(proposal.noteId)
        }
    }
}

/** 退出当前共享 Diff 审阅视图（保留所有逐项勾选状态以便稍后继续审阅）。 */
internal fun WorkspaceAgentHarnessController.closeProposalDiff() {
    activeReviewProposalId = null
    proposalNoticeCode = null
}

/**
 * 执行全屏工作台右侧 Diff 面板的 `[↗ 在笔记页打开]` 无损穿透：
 * 切至笔记编辑页 + 右侧伴随栏形态，自动选中目标笔记并在左侧原位展示同一 Diff，100% 保留逐项勾选状态。
 *
 * @param proposalId 正在审阅的提案 ID。
 */
internal fun WorkspaceAgentHarnessController.openDiffInNotePage(proposalId: String) {
    val proposal = selectedThreadEntry?.thread?.proposals?.firstOrNull {
        it.id == proposalId && it.status == AgentProposalStatus.PENDING
    } ?: return
    proposalNoticeCode = null
    activeReviewProposalId = proposal.id
    fullScreenAgentActive = false
    allNotesPageActive = false
    companionVisible = true
    mediumSidebarExpandedOverride = false
    showReturnToAgentPill = false
    if (proposal.kind == AgentProposalKind.PATCH_NOTE &&
        workspace.editorState.documents.any { it.id == proposal.noteId }
    ) {
        workspace.editorState.selectDocument(proposal.noteId)
        promoteRecentNote(proposal.noteId)
    }
}

/**
 * 切换提案内单个细粒度变更项的「采纳 / 跳过」状态，先乐观更新内存再持久化会话快照（保持 `lastMessageAt` 不变）。
 *
 * @param proposalId 目标提案 ID。
 * @param itemId 目标变更项 ID。
 * @param accepted 为真表示采纳，为假表示跳过。
 */
internal suspend fun WorkspaceAgentHarnessController.toggleProposalItem(
    proposalId: String,
    itemId: String,
    accepted: Boolean,
) {
    val entry = selectedThreadEntry ?: return
    val gateway = workspace.gateway.agentHarness ?: return
    val optimisticProposals = entry.thread.proposals.map { prop ->
        if (prop.id != proposalId) prop else prop.copy(
            items = prop.items.map { item -> if (item.id == itemId) item.copy(accepted = accepted) else item },
        )
    }
    replaceThreadInPlace(entry.copy(thread = entry.thread.copy(proposals = optimisticProposals)))
    runCatching {
        gateway.updateProposalItemAccepted(entry.id, proposalId, itemId, accepted)
    }.onSuccess(::replaceThreadInPlace)
}

/**
 * 主动放弃一条待确认提案（标记为 `DISCARDED`，不修改任何笔记快照与会话排序）。
 *
 * @param proposalId 待放弃提案 ID。
 */
internal suspend fun WorkspaceAgentHarnessController.discardProposal(proposalId: String) {
    val entry = selectedThreadEntry ?: return
    val gateway = workspace.gateway.agentHarness ?: return
    if (proposalBusy) return
    proposalBusy = true
    proposalNoticeCode = null
    try {
        val updated = gateway.discardProposal(entry.id, proposalId)
        replaceThreadInPlace(updated)
        if (activeReviewProposalId == proposalId) {
            activeReviewProposalId = null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        proposalNoticeCode = "apply_failed"
    } finally {
        proposalBusy = false
    }
}

/**
 * 基于目标笔记最新已确认快照重算提案基准修订号与旧值对照预览（若当前编辑器存在未保存手写草稿则先触发保存）。
 *
 * @param proposalId 待重算提案 ID。
 */
internal suspend fun WorkspaceAgentHarnessController.recalculateProposal(proposalId: String) {
    val entry = selectedThreadEntry ?: return
    val gateway = workspace.gateway.agentHarness ?: return
    val proposal = entry.thread.proposals.firstOrNull { it.id == proposalId } ?: return
    if (proposalBusy) return
    proposalBusy = true
    proposalNoticeCode = null
    try {
        val targetDoc = workspace.editorState.documents.firstOrNull { it.id == proposal.noteId }
        if (targetDoc?.dirty == true) {
            workspace.flushPendingNotes()
        }
        val updated = gateway.recalculateProposal(entry.id, proposalId)
        replaceThreadInPlace(updated)
        refreshCitationStatuses(updated)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        proposalNoticeCode = "apply_failed"
    } finally {
        proposalBusy = false
    }
}

/**
 * 确认采纳当前提案中所有已勾选项，通过单次 `TransactionCoordinator` 原子事务同时更新目标笔记快照与会话提案状态。
 *
 * @param proposalId 待提交提案 ID。
 * @return 提交成功时返回 `true`。
 */
internal suspend fun WorkspaceAgentHarnessController.applyProposal(proposalId: String): Boolean {
    val entry = selectedThreadEntry ?: return false
    val gateway = workspace.gateway.agentHarness ?: return false
    val proposal = entry.thread.proposals.firstOrNull { it.id == proposalId } ?: return false
    if (proposalBusy || proposal.acceptedItemCount == 0) return false
    if (evaluateProposalConflict(proposal).hasConflict) {
        proposalNoticeCode = "conflict"
        return false
    }
    proposalBusy = true
    proposalNoticeCode = null
    return try {
        val outcome = gateway.applyProposal(entry.id, proposalId)
        val note = outcome.committedNote
        val persistedDoc = EditorDocument(
            id = note.id,
            note = note.note,
            revision = note.revision,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            persisted = true,
            dirty = false,
        )
        if (workspace.editorState.documents.any { it.id == note.id }) {
            workspace.editorState.acceptPersistedDocument(previousId = note.id, persistedDocument = persistedDoc)
        } else {
            workspace.editorState.documents += persistedDoc
            if (!fullScreenAgentActive) {
                workspace.editorState.selectDocument(note.id)
            }
        }
        promoteRecentNote(note.id)
        replaceThreadInPlace(outcome.entry)
        if (activeReviewProposalId == proposalId) {
            activeReviewProposalId = null
        }
        refreshCitationStatuses(outcome.entry)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        proposalNoticeCode = "apply_failed"
        false
    } finally {
        proposalBusy = false
    }
}

/**
 * 将提案中的建议内容格式化为干净可读的 Markdown 文本，供修订冲突时点击「复制建议文本」兜底使用。
 *
 * @param proposal 目标修改提案。
 * @return 汇总建议文本。
 */
internal fun formatProposalSuggestedText(proposal: AgentMutationProposal): String {
    val chosen: List<AgentProposalItem> = proposal.items.filter { it.accepted }.ifEmpty { proposal.items }
    return buildString {
        appendLine("# ${proposal.noteTitle}")
        chosen.forEach { item ->
            appendLine()
            appendLine("## ${item.label}")
            val body = item.markdown?.takeIf { it.isNotBlank() } ?: item.newPreview
            if (body.isNotBlank()) {
                appendLine(body)
            }
        }
    }.trim()
}
