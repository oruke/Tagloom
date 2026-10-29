package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.AgentAuditEvent
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionRequest
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 在调用方持有会话锁时写入完整 Note，与外部成功审计共用原事务。
 * @param objectId 更新目标，新建时为空。
 * @param note 待确认完整 payload。
 * @param expectedRevision 更新的期望 revision。
 * @param auditEvent 外部调用审计；UI 写入为空。
 * @return 新确认 Note。
 */
internal suspend fun DesktopWorkspaceSession.saveNoteLocked(
    objectId: ObjectId?, note: Note, expectedRevision: ObjectRevision?, auditEvent: AgentAuditEvent? = null,
): DesktopStoredNote {
    requireWritable()
    val targetId = objectId ?: ObjectId(idFactory())
    val previous = projection.loadSnapshots().singleOrNull { it.header.objectId == targetId }
    if (expectedRevision != null && previous?.header?.revision != expectedRevision) {
        throw ExternalCallException("revision_conflict")
    }
    val cleared = note.copy(propertyValues = note.propertyValues.filterValues { it.value != kotlinx.serialization.json.JsonNull })
    val withRootType = if (previous == null && WorkspaceDefaults.DEFAULT_NOTE_TAG_ID !in cleared.directTagIds) {
        cleared.copy(directTagIds = cleared.directTagIds + WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
    } else cleared
    if (com.oruke.tagloom.core.workspace.LinkNoteSchemas.TYPE_ID in withRootType.directTagIds) {
        val catalog = schemaStore.load(unlocked)
        LinkNotePolicy.validate(withRootType, catalog.properties.firstOrNull { it.payload.id == com.oruke.tagloom.core.workspace.LinkNoteSchemas.GROUP_ID }?.payload)
    }
    val result = try {
        noteTransactionService.commit(unlocked, NoteSnapshotTransactionRequest(
            objectId = targetId, note = LinkNotePolicy.protectManualEdits(previous?.payload, withRootType), previousSnapshot = previous,
            actorId = actorId, actorSequence = nextActorSequence++, transactionId = TransactionId(idFactory()),
            hybridLogicalTime = hybridClock.next(), committedAt = timestampProvider(),
            auditEvent = auditEvent?.copy(targetNoteIds = setOf(targetId)),
        ))
    } catch (error: Throwable) {
        // manifest 可能已确认而派生更新失败，必须刷新确认点，不能继续使用旧 revision。
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    return DesktopStoredNote(result.snapshot)
}

/**
 * 在同一会话锁中校验 revision 并提交 Note tombstone。
 * @param objectId 待删除 Note。
 * @param expectedRevision 可选旧 revision；外部入口必须提供。
 * @param auditEvent 与 tombstone 原子确认的可选审计。
 */
internal suspend fun DesktopWorkspaceSession.deleteNoteLocked(
    objectId: ObjectId, expectedRevision: ObjectRevision? = null, auditEvent: AgentAuditEvent? = null,
) {
    requireWritable()
    val previous = projection.loadSnapshots().singleOrNull { it.header.objectId == objectId && !it.header.deleted }
        ?: throw ExternalCallException("not_found")
    if (expectedRevision != null && previous.header.revision != expectedRevision) throw ExternalCallException("revision_conflict")
    val result = try {
        noteTransactionService.commit(unlocked, NoteSnapshotTransactionRequest(
            objectId = objectId, note = previous.payload, previousSnapshot = previous,
            actorId = actorId, actorSequence = nextActorSequence++, transactionId = TransactionId(idFactory()),
            hybridLogicalTime = hybridClock.next(), committedAt = timestampProvider(),
            deleted = true, auditEvent = auditEvent,
        ))
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
}
