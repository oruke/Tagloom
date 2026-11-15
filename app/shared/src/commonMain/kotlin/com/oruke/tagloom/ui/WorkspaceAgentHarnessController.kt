package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.ai.AgentCitationStatus
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentHarnessProgress
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.platform.WorkspaceAgentThreadEntry
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import kotlinx.coroutines.CancellationException

/**
 * 统一编排双形态 Agent 界面（全屏工作台与右侧伴随栏）、单左栏「最近会话 / 最近笔记 Top 5」稳定排序及「全部笔记」系统页状态。
 *
 * 严格落实零跳序不变量：
 * 1. 点击侧栏「最近会话」或伴随栏下拉切换会话，仅切换 `selectedThreadId` 视图，绝不改动 `lastMessageAt` 或列表顺序；
 * 2. 点击侧栏「最近笔记」仅切换编辑器活动文档，绝不重排 `recentNoteOrder`。
 */
internal class WorkspaceAgentHarnessController(
    internal val workspace: WorkspaceUiController,
) {
    /** 当前平台是否装配了内置 Agent Harness 网关。 */
    val available: Boolean get() = workspace.gateway.agentHarness != null

    /** 当前是否处于中央全屏 Agent 会话形态。 */
    var fullScreenAgentActive: Boolean by mutableStateOf(false)

    /** 当前是否正在中央主舞台展示系统内置「全部笔记」Database 页面。 */
    var allNotesPageActive: Boolean by mutableStateOf(false)

    /** 右侧 Agent 伴随分栏（或窄屏底部抽屉）是否展开。 */
    var companionVisible: Boolean by mutableStateOf(false)

    /** 中屏（720..1119.dp）打开右侧伴随栏时用户是否手动临时展开左侧导航栏。 */
    var mediumSidebarExpandedOverride: Boolean by mutableStateOf(false)

    /** 全部历史会话搜索与归档管理弹窗是否打开。 */
    var historyDialogOpen: Boolean by mutableStateOf(false)

    /** 窄屏（`< 720.dp`）点击引用角标跳转原文后，是否在左下角显示悬浮返回胶囊。 */
    var showReturnToAgentPill: Boolean by mutableStateOf(false)

    /** 当前会话列表（含持久化与无痕内存会话，按 `lastMessageAt` 降序稳定排列）。 */
    var threads: List<WorkspaceAgentThreadEntry> by mutableStateOf(emptyList())
        internal set

    /** 当前正在查看的会话 ID。 */
    var selectedThreadId: ObjectId? by mutableStateOf(null)
        internal set

    /** 作曲框草稿输入文本。 */
    var promptDraft: String by mutableStateOf("")

    /** 当前作曲框挂载的 `@` 上下文引用列表。 */
    var activeContextRefs: List<AgentContextRef> by mutableStateOf(emptyList())
        internal set

    /** 因策略收紧为 `LOCKED` 被拦截的引用 chip ID 集合。 */
    var blockedRefIds: Set<String> by mutableStateOf(emptySet())
        internal set

    /** 当前作曲框选中的模型复合 ID。 */
    var selectedModelId: String? by mutableStateOf(null)
        internal set

    /** 当前作曲框选中的思考强度档位。 */
    var thinkingEffort: AiThinkingEffort by mutableStateOf(AiThinkingEffort.OFF)
        internal set

    /** 当前作曲框选中的检索暴露范围。 */
    var retrievalScope: AiRetrievalScope by mutableStateOf(AiRetrievalScope.CITED_ONLY)
        internal set

    /** 当前正在执行流式推理的会话 ID；空闲时为空。 */
    var runningThreadId: ObjectId? by mutableStateOf(null)
        private set

    /** 流式推理进行期间的内存增量状态（零写盘）。 */
    var liveProgress: AgentHarnessProgress? by mutableStateOf(null)
        private set

    /** 当前选中会话各来源角标与最新笔记比对后的实时状态映射。 */
    var citationStatuses: Map<Int, AgentCitationStatus> by mutableStateOf(emptyMap())
        internal set

    /** 点击角标定位原文时发出的滚动与段落高亮目标。 */
    var highlightedCitation: AgentCitationHighlight? by mutableStateOf(null)
        internal set

    /** 最近一次对话失败的脱敏错误编码。 */
    var turnErrorCode: String? by mutableStateOf(null)
        internal set

    /** 当前正在双方位共享 Diff 面板（全屏右侧或编辑页左侧正文）中对照审阅的提案 ID。 */
    var activeReviewProposalId: String? by mutableStateOf(null)
        internal set

    /** 当前是否正在提交或重算修改提案事务。 */
    var proposalBusy: Boolean by mutableStateOf(false)
        internal set

    /** 最近一次提案提交或重算的脱敏提示编码。 */
    var proposalNoticeCode: String? by mutableStateOf(null)
        internal set

    /** 每次从选区或块手柄快捷挂入后自增，驱动作曲框输入区自动获取键盘焦点。 */
    var composerFocusVersion: Int by mutableStateOf(0)
        internal set

    /** 当前是否正在执行手动上下文压缩。 */
    var compactionBusy: Boolean by mutableStateOf(false)
        internal set

    /** 编辑器选区或块手柄快捷挂载触发的瞬时提示文案（如禁读拦截或成功挂载提示）。 */
    var editorToastMessage: String? by mutableStateOf(null)
        internal set

    private var recentNoteOrder: List<ObjectId> by mutableStateOf(emptyList())
    private var loadedForMarker: Any? = null
    internal var highlightCounter: Int = 0

    /** 当前选中的会话条目。 */
    val selectedThreadEntry: WorkspaceAgentThreadEntry?
        get() = threads.firstOrNull { it.id == selectedThreadId }

    /** 当前工作区已启用的模型列表。 */
    val enabledModels: List<AiModelConfig>
        get() = workspace.agentSettings.snapshot?.config?.models?.filter { it.enabled }.orEmpty()

    /** 当前工作区已确认的保存查询列表。 */
    val savedQueriesSnapshot: List<WorkspaceSavedQuery>
        get() = workspace.savedQueries

    /** 侧栏「最近会话」默认展示的 Top 5 非归档会话（纯浏览点击零跳序）。 */
    val recentTopThreads: List<WorkspaceAgentThreadEntry>
        get() = threads.filterNot { it.thread.archived }.take(5)

    /** 侧栏「最近笔记」默认展示的 Top 5 笔记（排除受保护系统页，侧栏内点击零跳序）。 */
    val recentTopNotes: List<EditorDocument>
        get() {
            val allDocs = workspace.editorState.visibleDocuments
                .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }
            if (workspace.editorState.filterText.isNotBlank()) return allDocs.take(5)
            val orderedIds = syncStableRecentNoteOrder(recentNoteOrder, allDocs)
            val byId = allDocs.associateBy { it.id }
            return orderedIds.mapNotNull(byId::get).take(5)
        }

    /** 当前解析出的有效启用模型配置。 */
    val effectiveModel: AiModelConfig?
        get() = resolveEffectiveAgentModel(workspace.agentSettings.snapshot, selectedModelId)

    /** 当前模型与本机凭据的就绪状态。 */
    val readiness: AgentReadinessStatus
        get() = evaluateAgentReadiness(workspace.agentSettings.snapshot, effectiveModel)

    /** 在工作区打开后按需初始化配置快照、会话列表与最近笔记初始顺序。 */
    suspend fun ensureInitialized() {
        val marker = workspace.workspaceSessionMarker
        syncRecentNotes()
        if (!available || loadedForMarker === marker) return
        workspace.agentSettings.ensureLoaded()
        val gateway = workspace.gateway.agentHarness ?: return
        try {
            val loaded = gateway.listThreads()
            if (workspace.workspaceSessionMarker !== marker) return
            threads = loaded
            loadedForMarker = marker
            val currentSelected = selectedThreadId?.let { id -> loaded.firstOrNull { it.id == id } }
                ?: loaded.firstOrNull { !it.thread.archived }
            if (currentSelected != null) {
                applySelectedThreadToComposer(currentSelected)
                refreshCitationStatuses(currentSelected)
            } else {
                selectedModelId = effectiveModel?.id
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 初始化列表失败不阻塞笔记主界面。
        }
    }

    /** 在外部同步或事务刷新后静默同步持久化与无痕会话列表。 */
    suspend fun reloadThreadsIfLoaded(expectedMarker: Any) {
        syncRecentNotes()
        val gateway = workspace.gateway.agentHarness ?: return
        if (loadedForMarker !== expectedMarker || workspace.workspaceSessionMarker !== expectedMarker) return
        runCatching { gateway.listThreads() }.onSuccess { fresh ->
            if (workspace.workspaceSessionMarker === expectedMarker) {
                threads = fresh
                selectedThreadEntry?.let { entry ->
                    if (runningThreadId != entry.id) refreshCitationStatuses(entry)
                }
            }
        }
    }

    /** 同步最近笔记稳定列表，保留已有顺序并剔除已删除项。 */
    fun syncRecentNotes() {
        val cleanDocs = workspace.editorState.documents
            .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }
        recentNoteOrder = syncStableRecentNoteOrder(recentNoteOrder, cleanDocs)
    }

    /** 从侧栏「最近笔记」点击切换文档：仅切换选中笔记，严禁重排 `recentNoteOrder`。 */
    fun selectNoteFromSidebarRecent(noteId: ObjectId) {
        fullScreenAgentActive = false
        allNotesPageActive = false
        showReturnToAgentPill = false
        workspace.editorState.selectDocument(noteId)
    }

    /** 在新建笔记、从「全部笔记」/搜索弹窗/引用角标打开或保存编辑时将目标笔记提升至「最近笔记」首位。 */
    fun promoteRecentNote(noteId: ObjectId) {
        if (SystemDatabaseDefinitions.isProtectedSystemPage(noteId)) return
        val base = syncStableRecentNoteOrder(recentNoteOrder, workspace.editorState.documents)
        recentNoteOrder = listOf(noteId) + base.filterNot { it == noteId }
    }

    /** 打开系统内置「全部笔记」Database 页面。 */
    fun openAllNotesPage() {
        fullScreenAgentActive = false
        allNotesPageActive = true
        showReturnToAgentPill = false
    }

    /** 纯浏览切换会话（用于侧栏「最近会话」、历史弹窗或伴随栏下拉）：严禁修改 `lastMessageAt` 或列表排序。 */
    suspend fun selectThreadForViewing(threadId: ObjectId, openFullScreen: Boolean) {
        val target = threads.firstOrNull { it.id == threadId } ?: return
        turnErrorCode = null
        applySelectedThreadToComposer(target)
        if (openFullScreen) {
            allNotesPageActive = false
            companionVisible = false
            fullScreenAgentActive = true
        } else {
            companionVisible = true
        }
        refreshCitationStatuses(target)
    }

    /** 切换右侧 Agent 伴随栏（或从全屏 Agent 切回右侧伴随模式）。 */
    fun toggleCompanionPane() {
        if (fullScreenAgentActive) {
            fullScreenAgentActive = false
            companionVisible = true
            mediumSidebarExpandedOverride = false
            return
        }
        companionVisible = !companionVisible
        if (companionVisible) {
            mediumSidebarExpandedOverride = false
            showReturnToAgentPill = false
            if (selectedThreadEntry == null && activeContextRefs.isEmpty()) {
                activeContextRefs = defaultCompanionNoteRefs()
            }
        }
    }

    /** 从右侧伴随栏展开为中央全屏 Agent 会话形态。 */
    fun expandCompanionToFullScreen() {
        companionVisible = false
        allNotesPageActive = false
        showReturnToAgentPill = false
        fullScreenAgentActive = true
    }

    /** 新建一条持久化或无痕会话并定位至对应形态（新建会话自带最新 `lastMessageAt` 排在首位）。 */
    suspend fun startNewThread(
        incognito: Boolean = false,
        openFullScreen: Boolean = true,
        initialRefs: List<AgentContextRef> = emptyList(),
    ) {
        val gateway = workspace.gateway.agentHarness ?: return
        workspace.agentSettings.ensureLoaded()
        val model = effectiveModel
        val created = gateway.createThread(
            incognito = incognito,
            selectedModelId = model?.id,
            thinkingEffort = coerceThinkingEffortForModel(thinkingEffort, model),
            retrievalScope = coerceRetrievalScopeForModel(retrievalScope, model),
            initialContextRefs = initialRefs,
        )
        threads = listOf(created) + threads.filterNot { it.id == created.id }
        applySelectedThreadToComposer(created)
        promptDraft = ""
        turnErrorCode = null
        citationStatuses = emptyMap()
        if (openFullScreen) {
            allNotesPageActive = false
            companionVisible = false
            fullScreenAgentActive = true
        } else {
            companionVisible = true
        }
    }

    /** 更新作曲框选中的模型与思考强度（仅更新内存草稿，遵循低频落盘纪律，保持 `lastMessageAt` 不变）。 */
    fun updateModelAndThinking(modelId: String?, effort: AiThinkingEffort) {
        val model = resolveEffectiveAgentModel(workspace.agentSettings.snapshot, modelId)
        selectedModelId = model?.id ?: modelId
        thinkingEffort = coerceThinkingEffortForModel(effort, model)
        retrievalScope = coerceRetrievalScopeForModel(retrievalScope, model)
        syncComposerDraftInMemory()
    }

    /** 切换作曲框检索暴露范围（`仅引用` / `全部笔记`，仅更新内存草稿，保持 `lastMessageAt` 不变）。 */
    fun toggleRetrievalScope() {
        val next = if (retrievalScope == AiRetrievalScope.CITED_ONLY) AiRetrievalScope.ALL_NOTES else AiRetrievalScope.CITED_ONLY
        retrievalScope = coerceRetrievalScopeForModel(next, effectiveModel)
        syncComposerDraftInMemory()
    }

    /** 向当前作曲框添加或更新一条 `@` 上下文引用（仅更新内存草稿，发送完成时随会话统一落盘）。 */
    fun upsertContextRef(ref: AgentContextRef) {
        activeContextRefs = if (activeContextRefs.any { it.id == ref.id }) {
            activeContextRefs.map { if (it.id == ref.id) ref else it }
        } else {
            activeContextRefs + ref
        }
        syncComposerDraftInMemory()
    }

    /** 移除一条已挂载的 `@` 上下文引用（仅更新内存草稿）。 */
    fun removeContextRef(refId: String) {
        activeContextRefs = activeContextRefs.filterNot { it.id == refId }
        blockedRefIds = blockedRefIds - refId
        syncComposerDraftInMemory()
    }

    /** 快捷挂载一篇笔记或指定段落列表（同篇多次挂载段落自动合并为 `N 段` 胶囊）。 */
    fun attachNoteRef(noteId: ObjectId, title: String, blockIds: List<BlockId> = emptyList()) {
        activeContextRefs = mergeNoteOrBlocksContextRef(activeContextRefs, noteId, title, blockIds)
        syncComposerDraftInMemory()
    }

    /** 挂载笔记类型范围引用（严格不带 `#` 前缀）。 */
    fun attachTagScopeRef(tagId: TagId, tagName: String) {
        upsertContextRef(buildTagScopeContextRef(tagId, tagName))
    }

    /** 挂载已保存查询范围引用。 */
    fun attachSavedQueryRef(queryId: ObjectId, queryName: String) {
        upsertContextRef(buildSavedQueryContextRef(queryId, queryName))
    }

    /** 发送当前提问并启动内置 AgentHarness 流式推理；仅在消息完成后更新 `lastMessageAt` 并提升排序。 */
    suspend fun sendMessage() {
        val prompt = promptDraft.trim()
        if (prompt.isEmpty() || runningThreadId != null) return
        val gateway = workspace.gateway.agentHarness ?: return
        workspace.agentSettings.ensureLoaded()
        if (readiness != AgentReadinessStatus.READY) return

        val marker = workspace.workspaceSessionMarker
        turnErrorCode = null
        val targetEntry = selectedThreadEntry ?: gateway.createThread(
            incognito = false,
            selectedModelId = effectiveModel?.id,
            thinkingEffort = thinkingEffort,
            retrievalScope = retrievalScope,
            initialContextRefs = activeContextRefs,
        ).also { created ->
            threads = listOf(created) + threads
            selectedThreadId = created.id
        }
        val refsSnapshot = activeContextRefs.filterNot { isContextRefLockedInEditor(it, workspace.editorState, emptySet()) }
        promptDraft = ""
        runningThreadId = targetEntry.id
        liveProgress = null
        try {
            val result = gateway.runTurn(
                threadId = targetEntry.id,
                userPrompt = prompt,
                contextRefs = refsSnapshot,
                selectedModelId = effectiveModel?.id ?: selectedModelId,
                thinkingEffort = thinkingEffort,
                retrievalScope = coerceRetrievalScopeForModel(retrievalScope, effectiveModel),
                onProgress = { progress ->
                    if (workspace.workspaceSessionMarker === marker) {
                        liveProgress = progress
                        if (progress.blockedRefIds.isNotEmpty()) blockedRefIds = blockedRefIds + progress.blockedRefIds
                    }
                },
            )
            if (workspace.workspaceSessionMarker !== marker) return
            blockedRefIds = result.output.blockedRefIds
            activeContextRefs = result.entry.thread.activeContextRefs
            threads = (listOf(result.entry) + threads.filterNot { it.id == result.entry.id }).sortedWith(
                compareByDescending<WorkspaceAgentThreadEntry> { it.thread.lastMessageAt }
                    .thenByDescending { it.thread.createdAt }
                    .thenBy { it.id.value },
            )
            selectedThreadId = result.entry.id
            refreshCitationStatuses(result.entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                promptDraft = prompt
                turnErrorCode = "turn_failed"
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                runningThreadId = null
                liveProgress = null
            }
        }
    }

    /** 用户主动点击红色方块停止当前流式生成。 */
    fun stopGeneration() {
        workspace.gateway.agentHarness?.stopActiveTurn(runningThreadId)
    }

    /** 在工作区锁定时彻底销毁内存中的会话列表、无痕草稿与流式进度。 */
    fun clear() {
        fullScreenAgentActive = false; allNotesPageActive = false; companionVisible = false
        mediumSidebarExpandedOverride = false; historyDialogOpen = false; showReturnToAgentPill = false
        threads = emptyList(); selectedThreadId = null; promptDraft = ""
        activeContextRefs = emptyList(); blockedRefIds = emptySet(); selectedModelId = null
        thinkingEffort = AiThinkingEffort.OFF; retrievalScope = AiRetrievalScope.CITED_ONLY
        runningThreadId = null; liveProgress = null; citationStatuses = emptyMap()
        highlightedCitation = null; turnErrorCode = null; activeReviewProposalId = null
        proposalBusy = false; proposalNoticeCode = null; composerFocusVersion = 0
        compactionBusy = false; editorToastMessage = null; recentNoteOrder = emptyList(); loadedForMarker = null
    }

    /** 将选中会话的模型、思考、检索范围与活跃草稿引用同步至作曲框。 */
    internal fun applySelectedThreadToComposer(entry: WorkspaceAgentThreadEntry) {
        selectedThreadId = entry.id
        if (activeReviewProposalId != null &&
            entry.thread.proposals.none { it.id == activeReviewProposalId && it.status == AgentProposalStatus.PENDING }
        ) {
            activeReviewProposalId = null
            proposalNoticeCode = null
        }
        val model = resolveEffectiveAgentModel(workspace.agentSettings.snapshot, entry.thread.selectedModelId)
        selectedModelId = model?.id ?: entry.thread.selectedModelId
        thinkingEffort = coerceThinkingEffortForModel(entry.thread.thinkingEffort, model)
        retrievalScope = coerceRetrievalScopeForModel(entry.thread.retrievalScope, model)
        activeContextRefs = entry.thread.activeContextRefs
    }

    /** 原位更新会话条目而不改变列表顺序。 */
    internal fun replaceThreadInPlace(updated: WorkspaceAgentThreadEntry) {
        threads = threads.map { if (it.id == updated.id) updated else it }
    }

    /** 在内存中同步当前会话的作曲框模型、思考强度、检索范围与草稿引用，零磁盘写入且保持 `lastMessageAt` 不变。 */
    private fun syncComposerDraftInMemory() {
        val current = selectedThreadEntry ?: return
        replaceThreadInPlace(
            current.copy(
                thread = current.thread.copy(
                    selectedModelId = selectedModelId,
                    thinkingEffort = thinkingEffort,
                    retrievalScope = retrievalScope,
                    activeContextRefs = activeContextRefs,
                ),
            ),
        )
    }

    /** 实时评估会话中已登记角标与最新笔记快照的修订状态。 */
    internal suspend fun refreshCitationStatuses(entry: WorkspaceAgentThreadEntry) {
        val gateway = workspace.gateway.agentHarness ?: return
        val allSources = entry.thread.messages.flatMap { it.sources }
        if (allSources.isEmpty()) {
            citationStatuses = emptyMap()
            return
        }
        runCatching { gateway.evaluateCitations(allSources) }.onSuccess { citationStatuses = it }
    }
}
