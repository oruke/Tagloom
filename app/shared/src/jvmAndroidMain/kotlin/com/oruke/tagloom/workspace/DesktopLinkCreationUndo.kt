package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionRequest
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.*
import kotlinx.coroutines.sync.withLock

/**
 * 从最新权威状态撤销一个引用，后续编辑或其他引用会保住目标笔记。
 * @param context 创建时保存的身份和保守比较基线。
 * @return 原子确认的来源与可选目标 tombstone。
 */
internal suspend fun DesktopWorkspaceSession.undoLinkCreation(context: LinkCreationUndo): LinkCreationUndoResult =
    sessionMutex.withLock {
        requireWritable()
        val notes = loadActiveNotes()
        val target = notes.firstOrNull { it.id == context.linkId }?.snapshot
        val source = notes.firstOrNull { it.id == context.sourceId }?.snapshot
        val reference =
            source?.payload?.blocks?.firstOrNull { !it.deleted && it.id == context.referenceId && (it.content as? NoteReferenceBlockContent)?.noteId == context.linkId }
        val catalog = schemaStore.load(unlocked)
        require(catalog.issues.isEmpty()) { "reference-check-failed" }
        val relationIds =
            catalog.properties.filter { it.payload.type == PropertyTypes.RELATION }.map { it.payload.id }.toSet()
        val hasOtherReferences = notes.any { stored ->
            com.oruke.tagloom.core.index.NoteIndexDocumentFactory.fromSnapshot(
                stored.snapshot,
                relationIds
            ).outboundLinks.any { link ->
                link.targetNoteId == context.linkId && !(stored.id == context.sourceId && link.sourceBlockId == context.referenceId)
            }
        }
        val deleteTarget = context.created && target != null && target.header.createdAt == context.createdAt &&
                withoutAutomaticLinkFields(target.payload) == withoutAutomaticLinkFields(context.createdNote) && !hasOtherReferences
        val transactionId = TransactionId(idFactory())
        val now = timestampProvider()
        val requests = buildList {
            if (source != null && reference != null) add(
                NoteSnapshotTransactionRequest(
                    source.header.objectId,
                    source.payload.copy(blocks = source.payload.blocks.map { if (it.id == reference.id) it.copy(deleted = true) else it }),
                    source,
                    actorId,
                    nextActorSequence++,
                    transactionId,
                    hybridClock.next(),
                    now,
                )
            )
            if (deleteTarget) add(
                NoteSnapshotTransactionRequest(
                    requireNotNull(target).header.objectId,
                    target.payload,
                    target,
                    actorId,
                    nextActorSequence++,
                    transactionId,
                    hybridClock.next(),
                    now,
                    deleted = true
                )
            )
        }
        if (requests.isEmpty()) return@withLock LinkCreationUndoResult(null, null)
        val result = try {
            noteTransactionService.commitBatch(unlocked, requests)
        } catch (error: Throwable) {
            runCatching { refreshManifest() }; throw error
        }
        refreshManifest(result.encryptedManifest)
        LinkCreationUndoResult(
            result.snapshots.firstOrNull { !it.header.deleted }?.let(::DesktopStoredNote)?.toSharedWorkspaceNote(),
            context.linkId.takeIf { deleteTarget })
    }

/**
 * 比较创建后除自动标题描述以外的全部内容，自动获取不应阻止撤销，手动编辑必须阻止删除。
 * @param note 待比较链接笔记。
 * @return 移除自动字段差异后的完整比较值。
 */
private fun withoutAutomaticLinkFields(note: Note): Note {
    val source = note.linkMetadata ?: return note
    return note.copy(
        title = if (source.automaticTitle) "" else note.title,
        propertyValues = if (source.automaticDescription) note.propertyValues - WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID else note.propertyValues,
        linkMetadata = source.copy(title = "", description = "")
    )
}
