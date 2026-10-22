package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.editor.*
import com.oruke.tagloom.platform.*
import kotlinx.coroutines.*

/** 链接原位录入的临时会话，不进入 Note 快照。 */
internal data class LinkEntryDraft(
    /** 来源笔记，独立创建时为空。 */
    val sourceId: ObjectId?,
    /** 来源空段落，独立创建时为空。 */
    val blockId: BlockId?,
    /** 当前录入网址。 */
    val url: String = "",
    /** 可空的分组身份。 */
    val groupId: String? = null,
    /** 多个历史重复记录中选定的复用对象。 */
    val reuseId: ObjectId? = null,
)

/** 单次网页信息获取的可观察状态，不作为用户属性保存。 */
internal enum class LinkFetchState {
    /** 当前会话正在请求指定网页。 */
    FETCHING,

    /** 请求已完成，元数据按来源合入确认状态。 */
    READY,

    /** 本次请求失败，保持已保存内容，用户直接编辑缺少的字段。 */
    FAILED,
}

/** 编排链接录入、后台获取和已确认结果合入草稿；锁定时取消全部异步任务。 */
internal class WorkspaceLinkController(
    /** 当前平台唯一工作区应用服务。 */
    private val gateway: WorkspaceGateway,
    /** 当前解锁会话的编辑草稿与目录。 */
    private val editor: TagloomEditorState,
    /** 等待已在进行的工作区写入结束；creating 会阻止下一次自动保存插入本次提交。 */
    private val awaitWorkspaceIdle: suspend () -> Unit = {},
) {
    /** 后台抓取与 UI 状态更新的宿主协程，锁定时取消子任务但允许下次解锁复用。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 请求任务按 Note 去重，重新获取先取消旧任务。 */
    private val jobs = mutableMapOf<ObjectId, Job>()

    /** UI 会话代数，旧异步结果不能带回已清空的明文。 */
    private var epoch = 0

    /** 当前原位或独立录入草稿。 */
    var entry: LinkEntryDraft? by mutableStateOf(null)
        private set

    /** 事务提交期间阻止连续 Enter。 */
    var creating by mutableStateOf(false)
        private set

    /** 录入错误码，不保存异常正文。 */
    var errorCode: String? by mutableStateOf(null)
        private set

    /** 上次录入是否复用已有链接。 */
    var reused by mutableStateOf(false)
        private set

    /** 当前解锁会话内的抓取状态。 */
    val fetchStates = mutableStateMapOf<ObjectId, LinkFetchState>()

    /** 链接集合的临时查询，与笔记编辑状态分离。 */
    val collection = LinkCollectionState()

    /** 从反链返回时一次性定位来源引用，不进入持久化笔记。 */
    var revealBlockId: BlockId? by mutableStateOf(null)

    /** 最近一次可撤销的创建，仅保留在当前解锁会话内。 */
    var undoCreation: LinkCreationUndo? by mutableStateOf(null)
        private set

    /** 只有实际装配的本机能力才能开启入口。 */
    val available: Boolean get() = gateway.links != null

    /**
     * 开始原位录入或独立创建，不生成空链接笔记。
     * @param blockId 来源空段落；为空时独立创建。
     * @param url 粘贴入口可预填的网址。
     */
    fun begin(blockId: BlockId? = null, url: String = "") {
        if (!available || creating) return
        val destination = blockId?.let(editor::prepareLinkInsertion)
        if (blockId != null && destination == null) return
        entry = LinkEntryDraft(editor.selectedDocumentId.takeIf { destination != null }, destination, url)
        if (destination != null && destination != blockId) revealBlockId = destination
        errorCode = null; reused = false
    }

    /**
     * 更新尚未提交的录入内容，修正后立即清除旧错误。
     * @param draft 当前完整录入草稿。
     */
    fun update(draft: LinkEntryDraft) {
        entry = draft; errorCode = null
    }

    /** 取消录入只清除会话数据，原段落和已有笔记不删除。 */
    fun dismiss() {
        if (!creating) {
            entry = null; errorCode = null
        }
    }

    /**
     * 将链接和来源引用交给同一事务，确认后立即启动后台信息获取。
     * @param typeName 初次创建的本地化类型名称。
     * @param groupName 初次创建的本地化分组名称。
     * @return 来源正文应继续获得焦点的位置，独立创建为空。
     */
    suspend fun submit(typeName: String, groupName: String): EditorCursorTarget? {
        val draft = entry ?: return null
        if (creating) return null
        if (LinkUrl.parse(draft.url) == null) {
            errorCode = "link-url-invalid"; return null
        }
        val marker = epoch
        creating = true; errorCode = null
        return try {
            awaitWorkspaceIdle()
            if (marker != epoch) return null
            val source = draft.sourceId?.let { id -> editor.documents.singleOrNull { it.id == id } }
            require(draft.sourceId == null || source != null) { "link-source-unavailable" }
            val result = requireNotNull(gateway.links).create(
                CreateLinkNoteRequest(
                    draft.url, draft.groupId,
                    source?.let { LinkNoteSource(it.id, it.note, requireNotNull(draft.blockId), it.revision) },
                    typeName, groupName, draft.reuseId,
                )
            )
            if (marker != epoch) return null
            editor.upsertTagDefinition(result.type)
            result.properties.forEach(editor::upsertPropertyDefinition)
            mergeConfirmedLinkDocument(editor, result.link, null)
            result.source?.let { mergeConfirmedLinkDocument(editor, it, source) }
            reused = result.reused
            undoCreation = if (!result.reused || source != null) LinkCreationUndo(
                result.link.id,
                result.link.note,
                result.link.createdAt,
                !result.reused,
                source?.id,
                draft.blockId
            ) else null
            entry = null
            if (draft.sourceId == null) editor.selectDocument(result.link.id)
            if (!result.reused) refreshInBackground(result.link.id)
            result.nextBlockId?.let { EditorCursorTarget(it, 0) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (marker == epoch) errorCode = (error as? ExternalCallException)?.code ?: "link-create-failed"
            null
        } finally {
            if (marker == epoch) creating = false
        }
    }

    /**
     * 后台静默获取不阻止正文编辑；失败仅记录运行时状态，界面保持原有字段供用户编辑。
     * @param id 目标链接笔记。
     */
    fun refreshInBackground(id: ObjectId) {
        jobs.remove(id)?.cancel()
        val marker = epoch
        jobs[id] = scope.launch {
            fetchStates[id] = LinkFetchState.FETCHING
            try {
                var before = editor.documents.firstOrNull { it.id == id }
                if (before?.dirty == true) {
                    val saved = gateway.saveNote(id.takeIf { before.persisted }, before.note, before.revision)
                    mergeConfirmedLinkDocument(editor, saved, before)
                    before = editor.documents.firstOrNull { it.id == id }
                }
                val result = requireNotNull(gateway.links).refresh(id)
                if (marker == epoch) {
                    mergeConfirmedLinkDocument(editor, result, before); fetchStates[id] = LinkFetchState.READY
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (marker == epoch) fetchStates[id] = LinkFetchState.FAILED
            }
        }
    }

    /**
     * URL 经自动保存确认后获取新地址，标题和描述的手动编辑不触发网络。
     * @param saved 本次已确认笔记。
     */
    fun onNoteSaved(saved: WorkspaceNote) {
        val source = saved.note.linkMetadata ?: return
        if (LinkNotePolicy.address(saved.note)?.value != source.url) refreshInBackground(saved.id)
    }

    /**
     * 提交当前相关草稿后撤销本次添加，事务层决定是否可以同时删除刚建目标。
     * @return 撤销完成时为真；失败保留创建上下文，可从同一引用菜单重试。
     */
    suspend fun undoLastCreation(): Boolean {
        val context = undoCreation ?: return false
        if (creating) return false
        val marker = epoch
        creating = true; errorCode = null
        return try {
            jobs.remove(context.linkId)?.cancelAndJoin()
            awaitWorkspaceIdle()
            if (marker != epoch) return false
            listOfNotNull(context.sourceId, context.linkId).distinct().forEach { id ->
                val document = editor.documents.firstOrNull { it.id == id }
                if (document?.dirty == true) mergeConfirmedLinkDocument(
                    editor,
                    gateway.saveNote(id.takeIf { document.persisted }, document.note, document.revision),
                    document
                )
            }
            val source = editor.documents.firstOrNull { it.id == context.sourceId }
            val result = requireNotNull(gateway.links).undo(context)
            if (marker != epoch) return false
            result.source?.let { mergeConfirmedLinkDocument(editor, it, source) }
            result.deletedLinkId?.let { editor.removeDocument(it); fetchStates.remove(it) }
            undoCreation = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (marker == epoch) errorCode = "link-undo-failed"; false
        } finally {
            if (marker == epoch) creating = false
        }
    }

    /** 立即取消抓取并清空明文录入、错误和状态，旧回调由代数门禁丢弃。 */
    fun clear() {
        epoch++; scope.coroutineContext.cancelChildren(); jobs.clear(); fetchStates.clear()
        entry = null; creating = false; errorCode = null; reused = false
        collection.clear(); revealBlockId = null; undoCreation = null
    }
}

/** 链接编辑器共用的解锁会话入口，避免在每层 Block 中传递第二套写入回调。 */
internal val LocalWorkspaceLinks = staticCompositionLocalOf<WorkspaceLinkController?> { null }
