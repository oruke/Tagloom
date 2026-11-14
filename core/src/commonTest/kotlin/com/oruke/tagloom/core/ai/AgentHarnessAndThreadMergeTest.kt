package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.sync.WorkspaceReplicaAction
import com.oruke.tagloom.core.sync.WorkspaceReplicaMergePlanner
import com.oruke.tagloom.core.sync.WorkspaceReplicaObject
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验证内置 `AgentHarness` 受控只读工具循环、`SourceLedger` 防幻觉角标核验、手动停止与锁定取消区分，以及 `AGENT_THREAD` 三方合并。
 */
class AgentHarnessAndThreadMergeTest {
    /** 验证 `SourceLedger` 仅将已登记编号渲染为可交互角标，伪造 `[99]` 降级为纯文本，并准确评估实时修订状态。 */
    @Test
    fun verifiesSourceLedgerSegmentsAndEvaluatesLiveNoteStatus() {
        val ledger = SourceLedger()
        val entry1 = ledger.register(
            noteId = ObjectId("note-sync"),
            noteTitle = "双向同步设计",
            blockId = BlockId("blk-1"),
            revision = "win:1",
            rawExcerpt = "采用三方合并保留双方证据",
        )
        assertEquals(1, entry1.sourceIndex)

        val segments = SourceLedger.parseSegments(
            content = "结论见 [1]，伪造引用 [99] 与链接 [文档](https://example.com) 保持纯文本。",
            sources = ledger.entries(),
        )
        assertEquals(3, segments.size)
        assertEquals(AgentVerifiedTextSegment.PlainText("结论见 "), segments[0])
        assertEquals(AgentVerifiedTextSegment.VerifiedCitation(1, entry1), segments[1])
        assertEquals(
            AgentVerifiedTextSegment.PlainText("，伪造引用 [99] 与链接 [文档](https://example.com) 保持纯文本。"),
            segments[2],
        )

        val unchangedNote = Note(
            title = "双向同步设计",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
            blocks = listOf(
                Block(
                    id = BlockId("blk-1"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "a",
                    content = RichTextBlockContent(RichText(listOf(InlineRun("采用三方合并保留双方证据")))),
                ),
            ),
        )
        assertEquals(AgentCitationStatus.VERIFIED, SourceLedger.evaluateLiveStatus(entry1, unchangedNote, "win:1"))

        val editedNote = unchangedNote.copy(
            blocks = listOf(
                Block(
                    id = BlockId("blk-1"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "a",
                    content = RichTextBlockContent(RichText(listOf(InlineRun("已完成 19 阶段双端验收")))),
                ),
            ),
        )
        assertEquals(AgentCitationStatus.UPDATED, SourceLedger.evaluateLiveStatus(entry1, editedNote, "win:2"))
        assertEquals(AgentCitationStatus.REMOVED, SourceLedger.evaluateLiveStatus(entry1, null, null))
    }

    /** 验证内置 `AgentHarness` 执行只读工具循环、累加多步 Token 账单、拦截 `LOCKED` 引用并折叠跳过统计。 */
    @Test
    fun executesReadToolLoopAccumulatesUsageAndBlocksLockedRefs() = runSuspendTest {
        var callRound = 0
        val streamPort = object : FakeStreamPort() {
            override fun stream(request: AiStreamRequest): Flow<AiStreamEvent> = flow {
                callRound += 1
                if (callRound == 1) {
                    assertTrue(request.tools.any { it.name == "search_notes" })
                    val tc = AiToolCall("call-1", "search_notes", """{"query":"同步"}""")
                    emit(AiStreamEvent.ToolCallStarted(0, tc.callId, tc.name))
                    emit(AiStreamEvent.ToolCallReady(tc))
                    emit(AiStreamEvent.Completed(AiTokenUsage(uncachedInputTokens = 300, outputTokens = 40), AiFinishReason.TOOL_CALLS))
                } else {
                    emit(AiStreamEvent.ThinkingDelta("先核对检索摘要"))
                    emit(AiStreamEvent.TextDelta("根据检索结果 [1] 完成核对。"))
                    emit(
                        AiStreamEvent.Completed(
                            AiTokenUsage(uncachedInputTokens = 120, cacheReadTokens = 280, outputTokens = 60, reasoningTokens = 20),
                            AiFinishReason.STOP,
                        ),
                    )
                }
            }
        }

        val workspacePort = object : AgentHarnessWorkspacePort {
            override suspend fun resolveExplicitContext(refs: List<AgentContextRef>, budget: AiContextBudget) =
                AgentResolvedContextPayload(
                    formattedContextBlock = "",
                    sources = emptyList(),
                    blockedRefIds = setOf("note:locked-secret"),
                    truncatedByBudget = false,
                )

            override suspend fun executeReadTool(
                callId: String,
                tool: AgentToolName,
                argumentsJson: String,
                retrievalScope: AiRetrievalScope,
                activeRefs: List<AgentContextRef>,
                maxResultCharacters: Int,
            ): AgentReadToolExecutionOutcome {
                assertEquals(AgentToolName.SEARCH_NOTES, tool)
                return AgentReadToolExecutionOutcome(
                    resultJson = """{"items":[{"noteId":"note-1","title":"同步纪要"}]}""",
                    sources = listOf(
                        AgentRawSourceCandidate(
                            noteId = ObjectId("note-1"),
                            noteTitle = "同步纪要",
                            blockId = BlockId("b-1"),
                            revision = "dev:1",
                            excerpt = "已完成加密同步",
                        ),
                    ),
                    trace = AgentToolTrace(
                        callId = callId,
                        toolName = "search_notes",
                        summary = "检索「同步」命中 1 篇笔记",
                        readNoteCount = 1,
                        readBlockCount = 1,
                        skippedProtectedCount = 2,
                    ),
                )
            }
        }

        val harness = AgentHarness(streamPort)
        val conn = AiProviderConnection("c1", "DeepSeek", "https://api.deepseek.com/v1", AiProtocolKind.OPENAI_CHAT_COMPLETIONS)
        val model = AiModelConfig(
            connectionId = "c1",
            modelId = "deepseek-chat",
            displayName = "DeepSeek V3.2",
            contextWindowTokens = 128_000,
            toolsState = AiModelToolsState.VERIFIED,
        )
        val baseThread = AgentThread(
            id = ObjectId("th-1"),
            title = "新对话",
            createdAt = "2026-11-14T00:00:00Z",
            lastMessageAt = "2026-11-14T00:00:00Z",
            retrievalScope = AiRetrievalScope.ALL_NOTES,
        )
        val output = harness.runTurn(
            input = AgentHarnessTurnInput(
                thread = baseThread,
                userMessageId = "msg-u1",
                assistantMessageId = "msg-a1",
                userPrompt = "帮我核对同步纪要",
                contextRefs = listOf(AgentContextRef("note:locked-secret", AgentContextRefKind.NOTE, noteId = ObjectId("locked-secret"), label = "机密")),
                model = model,
                connection = conn,
                resolvedApiKey = "sk-memory",
                preferences = AiAgentPreferences(),
                nowIsoProvider = { "2026-11-14T00:01:00Z" },
            ),
            workspacePort = workspacePort,
        )

        assertEquals(setOf("note:locked-secret"), output.blockedRefIds)
        assertTrue(output.userMessage.contextRefs.isEmpty())
        assertEquals(1, output.assistantMessage.sources.size)
        assertEquals(340 + 460, output.updatedThread.cumulativeUsage.totalTokens)
        val trajectory = AgentReadToolCatalog.summarizeTrajectory(output.assistantMessage.toolTraces)
        assertTrue(trajectory.contains("自动跳过 2 篇受策略保护笔记"))
    }

    /** 验证用户主动点击停止会保留已流式接收的部分回答，而工作区锁定取消会直接向上抛出取消异常不落残缺记录。 */
    @Test
    fun distinguishesUserStopPartialPreservationFromWorkspaceLockCancellation() = runSuspendTest {
        var userStopped = true
        val cancellingStream = object : FakeStreamPort() {
            override fun stream(request: AiStreamRequest): Flow<AiStreamEvent> = flow {
                emit(AiStreamEvent.TextDelta("已生成前半句内容"))
                throw CancellationException("stream cancelled")
            }
        }
        val emptyPort = object : AgentHarnessWorkspacePort {
            override suspend fun resolveExplicitContext(refs: List<AgentContextRef>, budget: AiContextBudget) =
                AgentResolvedContextPayload("", emptyList(), emptySet(), false)

            override suspend fun executeReadTool(
                callId: String,
                tool: AgentToolName,
                argumentsJson: String,
                retrievalScope: AiRetrievalScope,
                activeRefs: List<AgentContextRef>,
                maxResultCharacters: Int,
            ) = error("unexpected")
        }
        val conn = AiProviderConnection("c1", "Local", "http://127.0.0.1:11434/v1", AiProtocolKind.OPENAI_CHAT_COMPLETIONS, authMode = AiAuthMode.NONE)
        val chatOnlyModel = AiModelConfig(connectionId = "c1", modelId = "qwen", displayName = "Qwen", toolsState = AiModelToolsState.CHAT_ONLY)
        val thread = AgentThread(id = ObjectId("th-stop"), title = "Stop test", createdAt = "2026-11-14T00:00:00Z", lastMessageAt = "2026-11-14T00:00:00Z")
        val harness = AgentHarness(cancellingStream)

        val stoppedOut = harness.runTurn(
            input = AgentHarnessTurnInput(
                thread = thread,
                userMessageId = "u1",
                assistantMessageId = "a1",
                userPrompt = "测试手动停止",
                contextRefs = emptyList(),
                model = chatOnlyModel,
                connection = conn,
                resolvedApiKey = null,
                preferences = AiAgentPreferences(),
                nowIsoProvider = { "2026-11-14T00:02:00Z" },
                isStoppedByUser = { userStopped },
            ),
            workspacePort = emptyPort,
        )
        assertTrue(stoppedOut.assistantMessage.stoppedByUser)
        assertEquals("已生成前半句内容", stoppedOut.assistantMessage.content)

        userStopped = false
        assertFailsWith<CancellationException> {
            harness.runTurn(
                input = AgentHarnessTurnInput(
                    thread = thread,
                    userMessageId = "u2",
                    assistantMessageId = "a2",
                    userPrompt = "测试工作区锁定中断",
                    contextRefs = emptyList(),
                    model = chatOnlyModel,
                    connection = conn,
                    resolvedApiKey = null,
                    preferences = AiAgentPreferences(),
                    nowIsoProvider = { "2026-11-14T00:03:00Z" },
                    isStoppedByUser = { userStopped },
                ),
                workspacePort = emptyPort,
            )
        }
    }

    /** 验证双端并发在同一会话追加消息与重命名/归档时，三方合并执行保序并集并精确重算累计计费账单。 */
    @Test
    fun mergesConcurrentAgentThreadMessagesAndRecomputesCumulativeUsage() {
        val threadId = ObjectId("thread-sync-1")
        val baseMsg = AgentThreadMessage(
            id = "m-base",
            role = AiChatRole.USER,
            content = "初始提问",
            createdAt = "2026-11-14T01:00:00Z",
            usage = AiTokenUsage(uncachedInputTokens = 100),
        )
        val baseThread = AgentThread(
            id = threadId,
            title = "初始标题",
            createdAt = "2026-11-14T01:00:00Z",
            lastMessageAt = "2026-11-14T01:00:00Z",
            messages = listOf(baseMsg),
        ).let { it.copy(cumulativeUsage = it.recomputeCumulativeUsage()) }

        val localMsg = AgentThreadMessage(
            id = "m-win",
            role = AiChatRole.ASSISTANT,
            content = "桌面端回答",
            createdAt = "2026-11-14T01:05:00Z",
            usage = AiTokenUsage(uncachedInputTokens = 200, outputTokens = 80),
        )
        val localThread = baseThread.copy(
            title = "桌面重命名标题",
            lastMessageAt = "2026-11-14T01:05:00Z",
            messages = listOf(baseMsg, localMsg),
        ).let { it.copy(cumulativeUsage = it.recomputeCumulativeUsage()) }

        val remoteMsg = AgentThreadMessage(
            id = "m-android",
            role = AiChatRole.ASSISTANT,
            content = "手机端补充追问回答",
            createdAt = "2026-11-14T01:08:00Z",
            usage = AiTokenUsage(uncachedInputTokens = 150, cacheReadTokens = 300, outputTokens = 70),
        )
        val remoteThread = baseThread.copy(
            archived = true,
            lastMessageAt = "2026-11-14T01:08:00Z",
            messages = listOf(baseMsg, remoteMsg),
        ).let { it.copy(cumulativeUsage = it.recomputeCumulativeUsage()) }

        val root = threadChange("th-c0", threadId, "actor-root", 1L, baseThread)
        val local = threadChange("th-c1", threadId, "actor-win", 10L, localThread, listOf(root.changeId), ChangeKind.UPDATE)
        val remote = threadChange("th-c2", threadId, "actor-and", 20L, remoteThread, listOf(root.changeId), ChangeKind.UPDATE)

        val plan = WorkspaceReplicaMergePlanner.plan(
            local = listOf(threadReplica(local, localThread)),
            remote = listOf(threadReplica(remote, remoteThread)),
            changes = listOf(root, local, remote),
            mergedAt = "2026-11-14T01:10:00Z",
        ) { _, _, _, _ -> "unused" }

        assertTrue(plan.conflicts.isEmpty())
        val decision = plan.objects.single()
        assertEquals(WorkspaceReplicaAction.MERGE, decision.action)
        val merged = CanonicalJson.format.decodeFromJsonElement(AgentThread.serializer(), decision.snapshot.payload)
        assertEquals(listOf("m-base", "m-win", "m-android"), merged.messages.map { it.id })
        assertEquals("桌面重命名标题", merged.title)
        assertTrue(merged.archived)
        assertEquals("2026-11-14T01:08:00Z", merged.lastMessageAt)
        assertEquals(100 + 280 + 520, merged.cumulativeUsage.totalTokens)
    }

    /** 构造测试用空实现流式端口基类。 */
    private abstract class FakeStreamPort : AiStreamPort {
        override suspend fun testConnection(connection: AiProviderConnection, resolvedApiKey: String?) =
            AiConnectionTestResult(true, 10L, 1)

        override suspend fun listModels(connection: AiProviderConnection, resolvedApiKey: String?) = emptyList<AiRemoteModelInfo>()

        override suspend fun probeToolCalling(connection: AiProviderConnection, resolvedApiKey: String?, modelId: String) =
            AiToolProbeResult(true, "ping")
    }

    /** 在无外部调度器依赖的多平台 `commonTest` 中同步驱动确定性挂起代码块。 */
    private fun runSuspendTest(block: suspend () -> Unit) {
        var result: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(outcome: Result<Unit>) {
                    result = outcome
                }
            },
        )
        checkNotNull(result) { "测试挂起块未同步完成" }.getOrThrow()
    }

    /** 构造测试用会话快照逻辑变更记录。 */
    private fun threadChange(
        id: String,
        objectId: ObjectId,
        actor: String,
        time: Long,
        thread: AgentThread,
        parents: List<ChangeId> = emptyList(),
        kind: ChangeKind = ChangeKind.CREATE,
    ): ObjectChange = ObjectChange(
        changeId = ChangeId(id),
        objectId = objectId,
        parentChangeIds = parents,
        actorId = ActorId(actor),
        hybridLogicalTime = HybridLogicalTime(time, 0, ActorId(actor)),
        baseSnapshotRevision = null,
        transactionId = TransactionId("tx-$id"),
        kind = kind,
        operations = listOf(
            ChangeOperation(
                type = "replace-agent-thread",
                payload = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), thread),
            ),
        ),
    )

    /** 构造测试用会话副本对象。 */
    private fun threadReplica(change: ObjectChange, thread: AgentThread): WorkspaceReplicaObject = WorkspaceReplicaObject(
        kind = ManifestObjectKind.AGENT_THREAD,
        snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = 1,
                objectId = thread.id,
                objectType = ObjectType.AGENT_THREAD,
                revision = ObjectRevision(change.actorId, change.hybridLogicalTime.physicalTimeMillis),
                lastChangeId = change.changeId,
                createdAt = thread.createdAt,
                updatedAt = thread.lastMessageAt,
            ),
            payload = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), thread),
        ),
    )
}
