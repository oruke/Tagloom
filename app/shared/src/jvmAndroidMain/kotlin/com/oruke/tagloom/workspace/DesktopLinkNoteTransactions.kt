package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionRequest
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.platform.*
import kotlinx.coroutines.sync.withLock

/**
 * 在同一写入锁中去重、补齐 schema 并原子确认链接与来源引用；确认前绝不联网。
 * @param request 录入内容和可选来源完整版本。
 * @return 已确认链接、来源及可复用目录。
 */
internal suspend fun DesktopWorkspaceSession.createLinkNote(request: CreateLinkNoteRequest): CreateLinkNoteResult =
    sessionMutex.withLock {
        requireWritable()
        val address = LinkUrl.parse(request.url) ?: throw ExternalCallException("link-url-invalid")
        val notes = loadActiveNotes()
        val source = request.source
        val previousSource =
            source?.let { draft -> notes.singleOrNull { it.snapshot.header.objectId == draft.id }?.snapshot }
        if (source != null) {
            if (source.expectedRevision != previousSource?.header?.revision) throw ExternalCallException("revision_conflict")
            require(source.note.blocks.any {
                it.id == source.blockId && !it.deleted && it.type == BlockTypes.PARAGRAPH && it.extractPlainText()
                    .orEmpty().isBlank()
            }) { "link-source-block-changed" }
        }
        val matches =
            notes.filter { LinkNoteSchemas.TYPE_ID in it.snapshot.payload.directTagIds && LinkNotePolicy.address(it.snapshot.payload)?.value == address.value }
        val existing =
            if (request.reuseNoteId != null) matches.singleOrNull { it.snapshot.header.objectId == request.reuseNoteId }
                ?: throw ExternalCallException("link-duplicate-changed")
            else when (matches.size) {
                0 -> null
                1 -> matches.single()
                else -> throw ExternalCallException("link-duplicate-ambiguous")
            }
        // schema 可独立复用，先确认缺失定义；后面的双 Note 事务失败最多留下未使用目录。
        ensureLinkNoteSchema(request.typeName, request.groupName)
        val catalog = schemaStore.load(unlocked)
        val type = catalog.tags.single { it.payload.id == LinkNoteSchemas.TYPE_ID }.payload
        val properties = catalog.properties.map { it.payload }
            .filter { it.id == LinkNoteSchemas.URL_ID || it.id == LinkNoteSchemas.GROUP_ID }
        val linkId = existing?.snapshot?.header?.objectId ?: ObjectId(idFactory())
        val now = timestampProvider()
        val linkNote = existing?.snapshot?.payload ?: LinkNotePolicy.create(address, request.groupId, now).copy(
            blocks = listOf(
                Block(
                    BlockId(idFactory()),
                    BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(RichText())
                )
            ),
        )
        LinkNotePolicy.validate(linkNote, properties.single { it.id == LinkNoteSchemas.GROUP_ID })
        val nextId = source?.let { BlockId(idFactory()) }
        val sourceNote = source?.let {
            LinkNotePolicy.protectManualEdits(
                previousSource?.payload,
                insertLinkReference(it.note, it.blockId, linkId, requireNotNull(nextId))
            )
        }
        if (sourceNote != null && LinkNoteSchemas.TYPE_ID in sourceNote.directTagIds) LinkNotePolicy.validate(
            sourceNote,
            properties.single { it.id == LinkNoteSchemas.GROUP_ID })
        val transactionId = TransactionId(idFactory())
        val requests = buildList {
            if (existing == null) add(linkWriteRequest(linkId, linkNote, null, transactionId, now))
            if (source != null) add(
                linkWriteRequest(
                    source.id,
                    requireNotNull(sourceNote),
                    previousSource,
                    transactionId,
                    now
                )
            )
        }
        val snapshots = if (requests.isEmpty()) emptyList() else {
            val result = try {
                noteTransactionService.commitBatch(unlocked, requests)
            } catch (error: Throwable) {
                runCatching { refreshManifest() }; throw error
            }
            refreshManifest(result.encryptedManifest)
            result.snapshots
        }
        val link =
            snapshots.firstOrNull { it.header.objectId == linkId }?.let(::DesktopStoredNote) ?: requireNotNull(existing)
        CreateLinkNoteResult(
            link.toSharedWorkspaceNote(),
            snapshots.firstOrNull { it.header.objectId == source?.id }?.let(::DesktopStoredNote)
                ?.toSharedWorkspaceNote(),
            type,
            properties,
            existing != null,
            nextId
        )
    }

/**
 * 在已持有会话锁时按最新字段来源回填网页信息，过期 URL 和手动内容均不覆盖。
 * @param id 目标链接身份。
 * @param url 请求启动时的 URL。
 * @param metadata 已有界解析的网页元数据。
 * @param isCurrentRequest 在写入锁内确认本次仍是该笔记的最新请求。
 * @return 最新确认笔记。
 */
internal suspend fun DesktopWorkspaceSession.applyLinkMetadata(
    id: ObjectId,
    url: String,
    metadata: WebPageMetadata,
    isCurrentRequest: () -> Boolean
): WorkspaceNote = sessionMutex.withLock {
    requireWritable()
    val previous = loadActiveNotes().singleOrNull { it.snapshot.header.objectId == id }?.snapshot
        ?: throw ExternalCallException("not_found")
    // 请求版本必须与快照写入共用临界区，避免等锁期间被更新请求反超后仍覆盖结果。
    if (!isCurrentRequest()) return@withLock DesktopStoredNote(previous).toSharedWorkspaceNote()
    val next = LinkNotePolicy.mergeMetadata(previous.payload, url, metadata, timestampProvider())
    if (next == previous.payload) return@withLock DesktopStoredNote(previous).toSharedWorkspaceNote()
    val result = try {
        noteTransactionService.commit(
            unlocked,
            linkWriteRequest(id, next, previous, TransactionId(idFactory()), timestampProvider())
        )
    } catch (error: Throwable) {
        runCatching { refreshManifest() }; throw error
    }
    refreshManifest(result.encryptedManifest)
    DesktopStoredNote(result.snapshot).toSharedWorkspaceNote()
}

/**
 * 为一个跨对象事务消耗不可复用的 actor 序号。
 * @param id 目标笔记。
 * @param note 完整待确认 payload。
 * @param previous 当前权威快照，新建为空。
 * @param transactionId 同一操作共享的事务身份。
 * @param now UTC 确认时间。
 * @return Note 统一写入请求。
 */
private fun DesktopWorkspaceSession.linkWriteRequest(
    id: ObjectId,
    note: Note,
    previous: ObjectSnapshot<Note>?,
    transactionId: TransactionId,
    now: String
) =
    NoteSnapshotTransactionRequest(
        id,
        note,
        previous,
        actorId,
        nextActorSequence++,
        transactionId,
        HybridLogicalTime(currentTimeMillis(), 0, actorId),
        now
    )

/**
 * 原位替换空段落为引用并插入后续正文，只重排受影响同级，不改变其他树结构。
 * @param note 来源完整草稿。
 * @param anchorId 空段落身份。
 * @param targetId 目标链接笔记。
 * @param nextId 新正文段落身份。
 * @return 带稳定引用的来源笔记。
 */
internal fun insertLinkReference(note: Note, anchorId: BlockId, targetId: ObjectId, nextId: BlockId): Note {
    val anchor = note.blocks.single { it.id == anchorId }
    val siblings = note.blocks.filter { !it.deleted && it.parentId == anchor.parentId }
        .sortedWith(compareBy({ it.orderKey }, { it.id.value })).toMutableList()
    siblings.add(siblings.indexOfFirst { it.id == anchorId } + 1,
        Block(nextId, BlockTypes.PARAGRAPH, anchor.parentId, "0", RichTextBlockContent(RichText())))
    val order = siblings.mapIndexed { index, block -> block.id to index.toString().padStart(10, '0') }.toMap()
    val blocks = note.blocks.map { block ->
        val ordered = order[block.id]?.let { block.copy(orderKey = it) } ?: block
        if (block.id == anchorId) ordered.copy(
            type = BlockTypes.NOTE_REFERENCE,
            content = NoteReferenceBlockContent(targetId)
        ) else ordered
    }
    return note.copy(blocks = blocks + siblings.single { it.id == nextId }.copy(orderKey = order.getValue(nextId)))
}
