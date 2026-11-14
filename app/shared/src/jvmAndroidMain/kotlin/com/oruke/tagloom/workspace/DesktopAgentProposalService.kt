package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItemKind
import com.oruke.tagloom.core.ai.AgentProposalKind
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.NoteFineGrainedPatchService
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.LinkNotePolicy
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteNormalizer
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.AgentProposalCommitTransactionService
import com.oruke.tagloom.core.transaction.AgentThreadSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionRequest
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceAgentProposalCommitOutcome
import com.oruke.tagloom.platform.WorkspaceAgentThreadEntry
import com.oruke.tagloom.platform.WorkspaceNote
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull

/**
 * 编排待审阅提案的逐项勾选、放弃、过期重算以及单次 `TransactionCoordinator` 原子落盘确认。
 *
 * 确认提案时，目标 `Note` 快照更新与持久化 `AgentThread` 的 `APPLIED` 状态更新严格共享同一个
 * `transactionId` 与单次 `EncryptedTransactionPlan` 确认点，彻底杜绝半确认分叉。
 *
 * @param session 当前已解锁工作区会话。
 * @param incognitoThreads 仅驻留内存的无痕会话映射。
 * @param onCommitted 持久化对象确认后的无明文刷新回调。
 */
internal class DesktopAgentProposalService(
    private val session: DesktopWorkspaceSession,
    private val incognitoThreads: ConcurrentHashMap<ObjectId, AgentThread>,
    private val onCommitted: () -> Unit,
) {
    /** 内置检索范围与访问策略解析器。 */
    private val contextResolver = DesktopAgentHarnessContextResolver(session)
    /** 细粒度提案预演与重算构建器。 */
    private val previewBuilder = DesktopAgentProposalPreviewBuilder(session, contextResolver)

    /**
     * 切换待审阅提案中单个变更项的「采纳 / 跳过」状态（严格保持 `lastMessageAt` 不变）。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @param itemId 目标变更项 ID。
     * @param accepted 为真表示采纳，为假表示跳过。
     * @return 更新后的会话条目。
     */
    suspend fun updateItemAccepted(
        threadId: ObjectId,
        proposalId: String,
        itemId: String,
        accepted: Boolean,
    ): WorkspaceAgentThreadEntry = mutatePendingProposal(threadId, proposalId) { proposal, now ->
        val updatedItems = proposal.items.map { item ->
            if (item.id == itemId) item.copy(accepted = accepted) else item
        }
        proposal.copy(items = updatedItems, updatedAt = now)
    }

    /**
     * 将指定待确认提案标记为 `DISCARDED`（不修改目标笔记与 `lastMessageAt`）。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 更新后的会话条目。
     */
    suspend fun discardProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry = mutatePendingProposal(threadId, proposalId) { proposal, now ->
        proposal.copy(status = AgentProposalStatus.DISCARDED, updatedAt = now)
    }

    /**
     * 当目标笔记在提案生成后被编辑导致 `expectedRevision` 落后时，基于最新已确认快照重新校验并更新提案基准。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 刷新基准后的会话条目。
     */
    suspend fun recalculateProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry = session.sessionMutex.withLock {
        session.requireWritable()
        val loadedThread = loadThreadRecordLocked(threadId)
        val proposal = loadedThread.thread.proposals.firstOrNull { it.id == proposalId }
            ?: throw ExternalCallException("proposal_not_found")
        if (proposal.status != AgentProposalStatus.PENDING) {
            throw ExternalCallException("proposal_not_pending")
        }
        if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
            return@withLock loadedThread.toEntry()
        }
        val latestStored = session.loadActiveNotes().singleOrNull { it.id == proposal.noteId }
            ?: throw ExternalCallException("not_found")
        if (SystemDatabaseDefinitions.isProtectedSystemPage(proposal.noteId)) {
            throw ExternalCallException("protected_system_page")
        }
        val policy = session.loadAiAccessPolicyResolver()
            .resolveEffectiveNotePolicy(latestStored.snapshot.payload)
        if (policy == AiAccessPolicy.LOCKED) {
            throw ExternalCallException("ai_access_policy_locked")
        }
        val refreshed = previewBuilder.refreshProposalAgainstLatestNoteLocked(proposal, latestStored)
        val updatedThread = loadedThread.thread.copy(
            proposals = loadedThread.thread.proposals.map { if (it.id == proposalId) refreshed else it },
        )
        persistSingleThreadUpdateLocked(loadedThread, updatedThread)
    }

    /**
     * 将所选采纳项与会话提案 `APPLIED` 状态在单次 `TransactionCoordinator` 原子确认点中落盘。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 同时确认后的权威笔记与最新会话条目。
     */
    suspend fun applyProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentProposalCommitOutcome = session.sessionMutex.withLock {
        session.requireWritable()
        val loadedThread = loadThreadRecordLocked(threadId)
        val proposal = loadedThread.thread.proposals.firstOrNull { it.id == proposalId }
            ?: throw ExternalCallException("proposal_not_found")
        if (proposal.status != AgentProposalStatus.PENDING) {
            throw ExternalCallException("proposal_not_pending")
        }
        val acceptedItems = proposal.items.filter { it.accepted }
        if (acceptedItems.isEmpty()) {
            throw ExternalCallException("no_items_accepted")
        }
        if (SystemDatabaseDefinitions.isProtectedSystemPage(proposal.noteId)) {
            throw ExternalCallException("protected_system_page")
        }

        val allNoteSnapshots = session.projection.loadSnapshots()
        val previousSnapshot = allNoteSnapshots.singleOrNull { it.header.objectId == proposal.noteId }
        val baseNote: Note
        if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
            if (previousSnapshot != null) throw ExternalCallException("revision_conflict")
            val initialTitle = acceptedItems.firstOrNull { it.kind == AgentProposalItemKind.TITLE }?.newTitle
                ?: proposal.noteTitle
            baseNote = Note(title = initialTitle, directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID))
        } else {
            val activePrevious = previousSnapshot?.takeUnless { it.header.deleted }
                ?: throw ExternalCallException("not_found")
            if (activePrevious.header.revision.externalText() != proposal.expectedRevision) {
                throw ExternalCallException("revision_conflict")
            }
            val policy = session.loadAiAccessPolicyResolver().resolveEffectiveNotePolicy(activePrevious.payload)
            if (policy == AiAccessPolicy.LOCKED) {
                throw ExternalCallException("ai_access_policy_locked")
            }
            if (proposal.schemaRevisions.isNotEmpty()) {
                val expectedSchema = proposal.schemaRevisions.entries.associate { (objId, revText) ->
                    ObjectId(objId) to parseExternalRevision(revText)
                }
                DesktopAgentNoteSchemaService(session).requireUnchanged(expectedSchema)
            }
            baseNote = activePrevious.payload
        }

        val committedAt = session.timestampProvider()
        val synthesized = NoteFineGrainedPatchService.applyAcceptedProposalItems(
            baseNote = baseNote,
            acceptedItems = acceptedItems,
            updatedAt = committedAt,
            blockIdFactory = { BlockId(session.idFactory()) },
            tableIdFactory = session.idFactory,
        )
        val normalized = NoteNormalizer.normalize(synthesized)
        if (normalized.title.isBlank() || normalized.title.length > 4096 || normalized.blocks.size > 5000 ||
            NoteValidator.validate(normalized).isNotEmpty()
        ) {
            throw ExternalCallException("note_invalid")
        }
        val schemaValidated = DesktopAgentNoteSchemaService(session).prepare(normalized, previousSnapshot?.payload)
        val cleanedProps = schemaValidated.note.copy(
            propertyValues = schemaValidated.note.propertyValues.filterValues { it.value != JsonNull },
        )
        val withRoot = if (previousSnapshot == null && WorkspaceDefaults.DEFAULT_NOTE_TAG_ID !in cleanedProps.directTagIds) {
            cleanedProps.copy(directTagIds = cleanedProps.directTagIds + WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
        } else {
            cleanedProps
        }
        if (LinkNoteSchemas.TYPE_ID in withRoot.directTagIds) {
            val catalog = session.schemaStore.load(session.unlocked)
            LinkNotePolicy.validate(
                withRoot,
                catalog.properties.firstOrNull { it.payload.id == LinkNoteSchemas.GROUP_ID }?.payload,
            )
        }
        val finalNotePayload = LinkNotePolicy.protectManualEdits(previousSnapshot?.payload, withRoot)

        val txId = TransactionId(session.idFactory())
        val noteSequence = session.nextActorSequence
        val noteHlc = session.hybridClock.next()
        val newNoteRevText = "${session.actorId.value}:$noteSequence"

        val appliedProposal = proposal.copy(
            noteTitle = finalNotePayload.title,
            status = AgentProposalStatus.APPLIED,
            appliedRevision = newNoteRevText,
            appliedSummary = "${acceptedItems.size}/${proposal.items.size}",
            updatedAt = committedAt,
        )
        val updatedThread = loadedThread.thread.copy(
            proposals = loadedThread.thread.proposals.map { if (it.id == proposalId) appliedProposal else it },
        )

        val noteRequest = NoteSnapshotTransactionRequest(
            objectId = proposal.noteId,
            note = finalNotePayload,
            previousSnapshot = previousSnapshot,
            actorId = session.actorId,
            actorSequence = noteSequence,
            transactionId = txId,
            hybridLogicalTime = noteHlc,
            committedAt = committedAt,
        )
        val threadSequence = noteSequence + 1L
        val threadRequest = if (loadedThread.incognito) {
            null
        } else {
            AgentThreadSnapshotTransactionRequest(
                objectId = updatedThread.id,
                thread = updatedThread,
                previousSnapshot = loadedThread.snapshot,
                actorId = session.actorId,
                actorSequence = threadSequence,
                transactionId = txId,
                hybridLogicalTime = session.hybridClock.next(),
                committedAt = committedAt,
                deleted = false,
            )
        }

        val compositeService = AgentProposalCommitTransactionService(
            cryptoProvider = session.threadStore.cryptoProvider,
            workspaceManifestService = session.manifestService,
            transactionCoordinator = session.transactionCoordinator,
            noteTransactionService = session.noteTransactionService,
            threadTransactionService = session.threadStore.transactionService,
        )
        val commitResult = try {
            compositeService.commit(session.unlocked, noteRequest, threadRequest)
        } catch (error: Throwable) {
            runCatching { session.refreshManifest() }
            throw error
        }

        session.nextActorSequence += if (loadedThread.incognito) 1L else 2L
        session.refreshManifest(commitResult.encryptedManifest)
        val finalThreadEntry = if (loadedThread.incognito) {
            incognitoThreads[updatedThread.id] = updatedThread
            WorkspaceAgentThreadEntry(updatedThread, incognito = true, revision = null)
        } else {
            val threadSnap = requireNotNull(commitResult.threadSnapshot)
            WorkspaceAgentThreadEntry(threadSnap.payload, incognito = false, revision = threadSnap.header.revision)
        }
        onCommitted()

        val noteSnap = commitResult.noteSnapshot
        WorkspaceAgentProposalCommitOutcome(
            entry = finalThreadEntry,
            committedNote = WorkspaceNote(
                id = noteSnap.header.objectId,
                note = noteSnap.payload,
                revision = noteSnap.header.revision,
                createdAt = noteSnap.header.createdAt,
                updatedAt = noteSnap.header.updatedAt,
            ),
        )
    }

    /** 在会话互斥锁内修改单个 `PENDING` 提案的辅助模板。 */
    private suspend fun mutatePendingProposal(
        threadId: ObjectId,
        proposalId: String,
        transform: (AgentMutationProposal, String) -> AgentMutationProposal,
    ): WorkspaceAgentThreadEntry = session.sessionMutex.withLock {
        session.requireWritable()
        val loaded = loadThreadRecordLocked(threadId)
        val existing = loaded.thread.proposals.firstOrNull { it.id == proposalId }
            ?: throw ExternalCallException("proposal_not_found")
        if (existing.status != AgentProposalStatus.PENDING) {
            return@withLock loaded.toEntry()
        }
        val transformed = transform(existing, session.timestampProvider())
        if (transformed == existing) {
            return@withLock loaded.toEntry()
        }
        val updatedThread = loaded.thread.copy(
            proposals = loaded.thread.proposals.map { if (it.id == proposalId) transformed else it },
        )
        persistSingleThreadUpdateLocked(loaded, updatedThread)
    }

    /** 在持有会话锁时读取目标无痕或持久化会话快照。 */
    private suspend fun loadThreadRecordLocked(threadId: ObjectId): LoadedThreadRecord {
        val incognito = incognitoThreads[threadId]
        if (incognito != null) {
            return LoadedThreadRecord(thread = incognito, snapshot = null, incognito = true)
        }
        val all = session.threadStore.loadAll(session.unlocked)
        val snap = all.rawById[threadId]?.takeUnless { it.header.deleted }
            ?: throw ExternalCallException("thread_not_found")
        return LoadedThreadRecord(thread = snap.payload, snapshot = snap, incognito = false)
    }

    /** 在持有会话锁时保存单条会话元数据/提案状态更新。 */
    private suspend fun persistSingleThreadUpdateLocked(
        loaded: LoadedThreadRecord,
        updatedThread: AgentThread,
    ): WorkspaceAgentThreadEntry {
        if (loaded.incognito) {
            incognitoThreads[updatedThread.id] = updatedThread
            return WorkspaceAgentThreadEntry(updatedThread, incognito = true, revision = null)
        }
        val result = try {
            session.threadStore.save(
                unlocked = session.unlocked,
                thread = updatedThread,
                actorId = session.actorId,
                actorSequence = session.nextActorSequence++,
                transactionId = TransactionId(session.idFactory()),
                hybridLogicalTime = session.hybridClock.next(),
                committedAt = session.timestampProvider(),
            )
        } catch (error: Throwable) {
            runCatching { session.refreshManifest() }
            throw error
        }
        session.refreshManifest(result.encryptedManifest)
        onCommitted()
        return WorkspaceAgentThreadEntry(result.snapshot.payload, incognito = false, revision = result.snapshot.header.revision)
    }
}

/** 封装会话锁内读取到的无痕或持久化会话上下文。 */
private data class LoadedThreadRecord(
    /** 当前会话载荷。 */
    val thread: AgentThread,
    /** 持久化快照头与载荷；无痕会话为空。 */
    val snapshot: ObjectSnapshot<AgentThread>?,
    /** 是否为仅驻留内存的无痕会话。 */
    val incognito: Boolean,
) {
    /** 转换为共享 UI 会话条目。 */
    fun toEntry(): WorkspaceAgentThreadEntry = WorkspaceAgentThreadEntry(
        thread = thread,
        incognito = incognito,
        revision = snapshot?.header?.revision,
    )
}
