package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AgentCitationStatus
import com.oruke.tagloom.core.ai.AgentCompactionEngine
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentHarness
import com.oruke.tagloom.core.ai.AgentHarnessProgress
import com.oruke.tagloom.core.ai.AgentHarnessTurnInput
import com.oruke.tagloom.core.ai.AgentSourceEntry
import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiStreamPort
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.ai.SourceLedger
import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.platform.WorkspaceAgentHarnessGateway
import com.oruke.tagloom.platform.WorkspaceAgentProposalCommitOutcome
import com.oruke.tagloom.platform.WorkspaceAgentThreadEntry
import com.oruke.tagloom.platform.WorkspaceAgentTurnResult
import com.oruke.tagloom.sync.ai.KtorAiStreamPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 在 JVM 与 Android 上编排内置 [AgentHarness]、持久化 `AGENT_THREAD` 加密快照与纯内存无痕会话。
 *
 * 严格遵循低频持久化与会话隔离边界：
 * 1. 流式 delta 与中间工具步期间不持有 `sessionMutex`、不执行任何磁盘写入；
 * 2. 纯浏览点击、重命名、归档或切换模型/引用绝不改动 `lastMessageAt`；
 * 3. 无痕会话仅驻留当前已解锁会话内存，工作区锁定时立即中断在途 HTTP/SSE 请求并彻底清空无痕状态。
 *
 * @param gateway 持有唯一活动会话的桌面工作区网关。
 * @param apiKeyStore 本机私有凭据隔离存储。
 * @param streamPort 底层流式模型通信端口。
 * @param onThreadCommitted 持久化会话发生确认写入后的无明文刷新回调。
 */
internal class DesktopAgentHarnessGateway(
    private val gateway: DesktopWorkspaceGateway,
    private val apiKeyStore: AgentApiKeyStore,
    private val streamPort: AiStreamPort,
    private val onThreadCommitted: () -> Unit = {},
) : WorkspaceAgentHarnessGateway {
    private val harness = AgentHarness(streamPort)
    private val stateLock = Any()
    private var boundActorId: String? = null
    private val incognitoThreads = ConcurrentHashMap<ObjectId, AgentThread>()
    private val activeTurn = AtomicReference<ActiveTurnHandle?>(null)

    /**
     * 读取全部活动持久化会话与当前已解锁会话内的无痕会话（按 `lastMessageAt` 降序排列，纯只读）。
     */
    override suspend fun listThreads(): List<WorkspaceAgentThreadEntry> = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        val persistent = session.loadAgentThreads().map { snapshot ->
            WorkspaceAgentThreadEntry(
                thread = snapshot.payload,
                incognito = false,
                revision = snapshot.header.revision,
            )
        }
        val incognito = incognitoThreads.values.map { thread ->
            WorkspaceAgentThreadEntry(thread = thread, incognito = true, revision = null)
        }
        (persistent + incognito).sortedWith(
            compareByDescending<WorkspaceAgentThreadEntry> { it.thread.lastMessageAt }
                .thenByDescending { it.thread.createdAt }
                .thenBy { it.id.value },
        )
    }

    /**
     * 创建一条新的持久化或无痕会话。
     */
    override suspend fun createThread(
        incognito: Boolean,
        selectedModelId: String?,
        thinkingEffort: AiThinkingEffort,
        retrievalScope: AiRetrievalScope,
        initialContextRefs: List<AgentContextRef>,
        initialTitle: String,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        val config = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        val resolvedModelId = selectedModelId
            ?: config.preferences.defaultModelId
            ?: config.models.firstOrNull { it.enabled }?.id
        val now = gateway.currentUtcTimestamp()
        val draft = AgentThread(
            id = ObjectId(gateway.createOpaqueId("agent-thread")),
            title = initialTitle.trim().ifEmpty { "新对话" },
            createdAt = now,
            lastMessageAt = now,
            archived = false,
            selectedModelId = resolvedModelId,
            thinkingEffort = thinkingEffort,
            retrievalScope = retrievalScope,
            activeContextRefs = initialContextRefs,
        )
        if (incognito) {
            incognitoThreads[draft.id] = draft
            WorkspaceAgentThreadEntry(thread = draft, incognito = true, revision = null)
        } else {
            val saved = session.saveAgentThread(draft)
            onThreadCommitted()
            WorkspaceAgentThreadEntry(thread = saved.payload, incognito = false, revision = saved.header.revision)
        }
    }

    /**
     * 更新会话元数据或作曲框配置，不修改 `lastMessageAt` 以杜绝「最近会话」跳序。
     */
    override suspend fun updateThreadState(
        threadId: ObjectId,
        title: String?,
        archived: Boolean?,
        selectedModelId: String?,
        updateSelectedModel: Boolean,
        thinkingEffort: AiThinkingEffort?,
        retrievalScope: AiRetrievalScope?,
        activeContextRefs: List<AgentContextRef>?,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        val memoryExisting = incognitoThreads[threadId]
        if (memoryExisting != null) {
            val updated = applyMetadataPatch(
                current = memoryExisting,
                title = title,
                archived = archived,
                selectedModelId = selectedModelId,
                updateSelectedModel = updateSelectedModel,
                thinkingEffort = thinkingEffort,
                retrievalScope = retrievalScope,
                activeContextRefs = activeContextRefs,
            )
            incognitoThreads[threadId] = updated
            return@withContext WorkspaceAgentThreadEntry(updated, incognito = true, revision = null)
        }
        val snapshot = requireNotNull(session.loadAgentThread(threadId)) { "目标会话不存在" }
        val updated = applyMetadataPatch(
            current = snapshot.payload,
            title = title,
            archived = archived,
            selectedModelId = selectedModelId,
            updateSelectedModel = updateSelectedModel,
            thinkingEffort = thinkingEffort,
            retrievalScope = retrievalScope,
            activeContextRefs = activeContextRefs,
        )
        if (updated == snapshot.payload) {
            return@withContext WorkspaceAgentThreadEntry(snapshot.payload, incognito = false, revision = snapshot.header.revision)
        }
        val saved = session.saveAgentThread(updated)
        onThreadCommitted()
        WorkspaceAgentThreadEntry(saved.payload, incognito = false, revision = saved.header.revision)
    }

    /**
     * 删除持久化或无痕会话。
     */
    override suspend fun deleteThread(threadId: ObjectId): Unit = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        if (incognitoThreads.remove(threadId) != null) {
            return@withContext
        }
        session.deleteAgentThread(threadId)
        onThreadCommitted()
    }

    /**
     * 执行单轮用户提问与受控只读工具循环，仅在结束或被用户主动停止后执行一次持久化提交。
     */
    override suspend fun runTurn(
        threadId: ObjectId,
        userPrompt: String,
        contextRefs: List<AgentContextRef>,
        selectedModelId: String?,
        thinkingEffort: AiThinkingEffort,
        retrievalScope: AiRetrievalScope,
        onProgress: (AgentHarnessProgress) -> Unit,
    ): WorkspaceAgentTurnResult = withContext(Dispatchers.IO) {
        require(userPrompt.isNotBlank()) { "agent-empty-prompt" }
        val session = ensureSessionBound()
        val isIncognito = incognitoThreads.containsKey(threadId)
        val initialThread = if (isIncognito) {
            requireNotNull(incognitoThreads[threadId]) { "目标无痕会话不存在" }
        } else {
            requireNotNull(session.loadAgentThread(threadId)) { "目标会话不存在" }.payload
        }
        val config = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        val targetModelId = selectedModelId
            ?: initialThread.selectedModelId
            ?: config.preferences.defaultModelId
            ?: config.models.firstOrNull { it.enabled }?.id
            ?: error("agent-model-not-configured")
        val model = config.models.firstOrNull { it.id == targetModelId && it.enabled }
            ?: error("agent-model-not-configured")
        val connection = config.connections.firstOrNull { it.connectionId == model.connectionId }
            ?: error("agent-connection-not-found")
        val resolvedApiKey = resolveRequiredApiKey(session.workspaceId, connection)

        val preparedThread = initialThread.copy(
            selectedModelId = model.id,
            thinkingEffort = thinkingEffort,
            retrievalScope = retrievalScope,
            activeContextRefs = contextRefs,
        )
        val currentJob = requireNotNull(currentCoroutineContext()[Job])
        val turnHandle = ActiveTurnHandle(threadId = threadId, job = currentJob)
        val previousHandle = activeTurn.getAndSet(turnHandle)
        previousHandle?.let { old ->
            old.stoppedByUser = true
            old.job.cancel(CancellationException("replaced-by-new-turn"))
        }

        val output = try {
            harness.runTurn(
                input = AgentHarnessTurnInput(
                    thread = preparedThread,
                    userMessageId = gateway.createOpaqueId("agent-msg"),
                    assistantMessageId = gateway.createOpaqueId("agent-msg"),
                    userPrompt = userPrompt,
                    contextRefs = contextRefs,
                    model = model,
                    connection = connection,
                    resolvedApiKey = resolvedApiKey,
                    preferences = config.preferences,
                    nowIsoProvider = gateway::currentUtcTimestamp,
                    isStoppedByUser = { turnHandle.stoppedByUser },
                    compactionIdFactory = { gateway.createOpaqueId("agent-compact") },
                ),
                workspacePort = DesktopAgentHarnessWorkspaceAdapter(session),
                onProgress = onProgress,
            )
        } finally {
            activeTurn.compareAndSet(turnHandle, null)
        }

        // 保留本轮校验通过的活跃 @ 引用（自动压缩与后续追问继续携带，直至用户手动移除或策略收紧拦截）。
        val persistedThread = output.updatedThread
        val finalEntry = if (isIncognito) {
            incognitoThreads[threadId] = persistedThread
            WorkspaceAgentThreadEntry(persistedThread, incognito = true, revision = null)
        } else {
            val latestSession = ensureSessionBound()
            val saved = latestSession.saveAgentThread(persistedThread)
            onThreadCommitted()
            WorkspaceAgentThreadEntry(saved.payload, incognito = false, revision = saved.header.revision)
        }
        WorkspaceAgentTurnResult(
            entry = finalEntry,
            output = output.copy(updatedThread = persistedThread),
        )
    }

    /**
     * 由用户主动点击「停止」按钮终止当前生成，保留已接收正文并触发正常收尾落盘。
     */
    override fun stopActiveTurn(threadId: ObjectId?) {
        val current = activeTurn.get() ?: return
        if (threadId != null && current.threadId != threadId) return
        current.stoppedByUser = true
        (streamPort as? KtorAiStreamPort)?.cancelActiveRequests()
        current.job.cancel(CancellationException("stopped-by-user"))
    }

    /**
     * 针对已登记来源列表实时核对当前工作区活动笔记的最新修订与段落存续状态。
     */
    override suspend fun evaluateCitations(entries: List<AgentSourceEntry>): Map<Int, AgentCitationStatus> =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext emptyMap()
            val session = ensureSessionBound()
            session.sessionMutex.withLock {
                session.requireOpen()
                val notesById = session.loadActiveNotes().associateBy { it.id }
                entries.associate { entry ->
                    val stored = notesById[entry.noteId]
                    entry.sourceIndex to SourceLedger.evaluateLiveStatus(
                        entry = entry,
                        currentNote = stored?.snapshot?.payload,
                        currentRevision = stored?.snapshot?.header?.revision?.externalText(),
                    )
                }
            }
        }

    /**
     * 切换待审阅提案中单个细粒度变更项的「采纳 / 跳过」状态。
     */
    override suspend fun updateProposalItemAccepted(
        threadId: ObjectId,
        proposalId: String,
        itemId: String,
        accepted: Boolean,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        DesktopAgentProposalService(session, incognitoThreads, onThreadCommitted)
            .updateItemAccepted(threadId, proposalId, itemId, accepted)
    }

    /**
     * 放弃指定待确认修改提案。
     */
    override suspend fun discardProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        DesktopAgentProposalService(session, incognitoThreads, onThreadCommitted)
            .discardProposal(threadId, proposalId)
    }

    /**
     * 基于目标笔记最新已确认快照重算过期提案的 `expectedRevision` 与旧值预览。
     */
    override suspend fun recalculateProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        DesktopAgentProposalService(session, incognitoThreads, onThreadCommitted)
            .recalculateProposal(threadId, proposalId)
    }

    /**
     * 在单次 `TransactionCoordinator` 原子确认点中同时提交所选笔记修改与会话提案 `APPLIED` 状态。
     */
    override suspend fun applyProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentProposalCommitOutcome = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        DesktopAgentProposalService(session, incognitoThreads, onThreadCommitted)
            .applyProposal(threadId, proposalId)
    }

    /**
     * 手动执行两阶段无损上下文压缩，保持 `lastMessageAt` 不变并以单次事务确认（无痕会话仅更新内存）。
     */
    override suspend fun compactThread(
        threadId: ObjectId,
        activeContextRefs: List<AgentContextRef>?,
    ): WorkspaceAgentThreadEntry = withContext(Dispatchers.IO) {
        val session = ensureSessionBound()
        val isIncognito = incognitoThreads.containsKey(threadId)
        val existingSnapshot = if (isIncognito) null else requireNotNull(session.loadAgentThread(threadId)) { "目标会话不存在" }
        val baseThread = if (isIncognito) {
            requireNotNull(incognitoThreads[threadId]) { "目标无痕会话不存在" }
        } else {
            requireNotNull(existingSnapshot).payload
        }
        val currentThread = if (activeContextRefs != null) {
            baseThread.copy(activeContextRefs = activeContextRefs)
        } else {
            baseThread
        }
        if (currentThread.messages.isEmpty()) {
            return@withContext WorkspaceAgentThreadEntry(
                thread = currentThread,
                incognito = isIncognito,
                revision = existingSnapshot?.header?.revision,
            )
        }
        val config = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        val targetModelId = currentThread.selectedModelId
            ?: config.preferences.defaultModelId
            ?: config.models.firstOrNull { it.enabled }?.id
        val model = config.models.firstOrNull { it.id == targetModelId && it.enabled }
            ?: config.models.firstOrNull { it.enabled }
        val connection = model?.let { m -> config.connections.firstOrNull { it.connectionId == m.connectionId } }
        val resolvedKey = connection?.let { conn ->
            if (conn.authMode == AiAuthMode.NONE) null else apiKeyStore.resolveForRequest(session.workspaceId, conn)
        }
        val compacted = AgentCompactionEngine.compactWithModelOrFallback(
            thread = currentThread,
            compactionId = gateway.createOpaqueId("agent-compact"),
            triggerPercent = 0,
            model = model,
            preferences = config.preferences,
            createdAt = gateway.currentUtcTimestamp(),
            connection = if (connection != null && (connection.authMode == AiAuthMode.NONE || !resolvedKey.isNullOrBlank())) {
                connection
            } else {
                null
            },
            resolvedApiKey = resolvedKey,
            streamPort = streamPort,
        )
        if (isIncognito) {
            incognitoThreads[threadId] = compacted
            WorkspaceAgentThreadEntry(compacted, incognito = true, revision = null)
        } else {
            val latestSession = ensureSessionBound()
            val saved = latestSession.saveAgentThread(compacted)
            onThreadCommitted()
            WorkspaceAgentThreadEntry(saved.payload, incognito = false, revision = saved.header.revision)
        }
    }

    /**
     * 在工作区锁定或主密钥轮换关闭会话时立即中断在途 HTTP/SSE 流，并清空全部无痕会话内存。
     */
    fun cancelAndClearSessionState() {
        val running = activeTurn.getAndSet(null)
        if (running != null) {
            running.stoppedByUser = false
            running.job.cancel(CancellationException("workspace-locked"))
        }
        (streamPort as? KtorAiStreamPort)?.cancelActiveRequests()
        synchronized(stateLock) {
            incognitoThreads.clear()
            boundActorId = null
        }
    }

    /**
     * 绑定当前活动 `DesktopWorkspaceSession` 并在首次访问时登记外部关闭监听。
     */
    private fun ensureSessionBound(): DesktopWorkspaceSession {
        val session = requireNotNull(gateway.sessionOrNull()) { "工作区尚未解锁" }
        synchronized(stateLock) {
            if (boundActorId != session.actorId.value) {
                incognitoThreads.clear()
                boundActorId = session.actorId.value
                session.onExternalClosed { cancelAndClearSessionState() }
            }
        }
        return session
    }

    /**
     * 解析请求所需的 API Key；当连接声明需要凭据但当前设备尚未配置时抛出明确语义错误码。
     */
    private fun resolveRequiredApiKey(
        workspaceId: com.oruke.tagloom.core.model.WorkspaceId,
        connection: AiProviderConnection,
    ): String? {
        if (connection.authMode == AiAuthMode.NONE) return null
        val resolved = apiKeyStore.resolveForRequest(workspaceId, connection)
        if (resolved.isNullOrBlank()) {
            error("agent-missing-local-api-key")
        }
        return resolved
    }

    /**
     * 在保持 `lastMessageAt` 完全不变的前提下更新会话非消息元数据。
     */
    private fun applyMetadataPatch(
        current: AgentThread,
        title: String?,
        archived: Boolean?,
        selectedModelId: String?,
        updateSelectedModel: Boolean,
        thinkingEffort: AiThinkingEffort?,
        retrievalScope: AiRetrievalScope?,
        activeContextRefs: List<AgentContextRef>?,
    ): AgentThread = current.copy(
        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
        archived = archived ?: current.archived,
        selectedModelId = if (updateSelectedModel) selectedModelId else current.selectedModelId,
        thinkingEffort = thinkingEffort ?: current.thinkingEffort,
        retrievalScope = retrievalScope ?: current.retrievalScope,
        activeContextRefs = activeContextRefs ?: current.activeContextRefs,
    )
}

/**
 * 追踪当前正在流式执行的单轮会话协程句柄与用户主动停止标志。
 */
private class ActiveTurnHandle(
    val threadId: ObjectId,
    val job: Job,
    @Volatile var stoppedByUser: Boolean = false,
)
