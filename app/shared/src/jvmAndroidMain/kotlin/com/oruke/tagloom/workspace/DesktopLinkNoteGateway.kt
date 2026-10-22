package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.LinkNotePolicy
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 链接应用服务的平台适配，网络结果只回到启动请求的同一解锁会话。 */
internal class DesktopLinkNoteGateway(
    /** 提供当前唯一工作区会话。 */
    private val gateway: DesktopWorkspaceGateway,
) : WorkspaceLinkGateway {
    /** 不带浏览器凭据的元数据网络端口。 */
    private val client = HttpWebPageMetadataClient()

    /** 同一目标只接受最新一次请求结果，令重试覆盖旧请求而不创建新笔记。 */
    private val requests = ConcurrentHashMap<Pair<String, ObjectId>, Any>()

    /**
     * 在当前单写入会话内确认创建与引用。
     * @param request 用户录入和来源版本。
     * @return 同一事务确认的结果。
     */
    override suspend fun create(request: CreateLinkNoteRequest): CreateLinkNoteResult = withContext(Dispatchers.IO) {
        requireNotNull(gateway.sessionOrNull()) { "workspace-locked" }.createLinkNote(request)
    }

    /**
     * 通过同一会话写入锁撤销，不删除后续编辑或其他引用仍在使用的目标。
     * @param context 本会话的创建记录。
     * @return 原子确认的撤销结果。
     */
    override suspend fun undo(context: LinkCreationUndo): LinkCreationUndoResult = withContext(Dispatchers.IO) {
        requireNotNull(gateway.sessionOrNull()) { "workspace-locked" }.undoLinkCreation(context)
    }

    /**
     * 读取已确认地址，网络阶段释放写入锁，回填时重新校验会话与 URL。
     * @param noteId 目标链接身份。
     * @return 新确认笔记，过期请求返回当前确认值。
     */
    override suspend fun refresh(noteId: ObjectId): WorkspaceNote = coroutineScope {
        val session = requireNotNull(gateway.sessionOrNull()) { "workspace-locked" }
        val requestJob = currentCoroutineContext().job
        val onClose: () -> Unit = { requestJob.cancel(CancellationException("workspace-session-expired")) }
        val url = session.sessionMutex.withLock {
            session.requireWritable()
            val address = LinkNotePolicy.address(
                session.loadActiveNotes().single { it.snapshot.header.objectId == noteId }.snapshot.payload
            )?.value
                ?: error("link-url-invalid")
            session.externalCloseListeners.add(onClose)
            address
        }
        val key = session.actorId.value to noteId
        val marker = Any()
        requests[key] = marker
        try {
            val metadata = client.fetch(url)
            currentCoroutineContext().ensureActive()
            require(gateway.sessionOrNull() === session) { "workspace-session-expired" }
            session.applyLinkMetadata(noteId, url, metadata) { requests[key] === marker }
        } finally {
            requests.remove(key, marker)
            withContext(NonCancellable) { session.sessionMutex.withLock { session.externalCloseListeners.remove(onClose) } }
        }
    }
}
