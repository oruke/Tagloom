package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.AgentThreadReplicaMerge
import com.oruke.tagloom.core.sync.MergeSide
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive

/**
 * 验证内置 `AgentHarness` 写提案工具白名单、同篇同轮提案自动合并与 `AgentThreadReplicaMerge` 三方交换律合并。
 */
class Stage3HarnessAndMergeTest {
    /** 验证内置工具目录仅暴露 14 只读 + 3 写提案工具（严禁暴露删除与导出），且自动合并同笔记同修订号的元数据与块补丁提案。 */
    @Test
    fun builtInCatalogExposesProposalToolsWithoutDeleteOrExportAndCoalescesSameNoteTurnProposals() = runSuspendImmediate {
        val exposedNames = AgentReadToolCatalog.allDefinitions.map { it.name }.toSet()
        assertTrue("create_note" in exposedNames)
        assertTrue("patch_note_blocks" in exposedNames)
        assertTrue("update_note_metadata" in exposedNames)
        assertFalse("delete_note" in exposedNames)
        assertFalse("export_note" in exposedNames)

        var step = 0
        val fakeStream = object : AiStreamPort {
            override fun stream(request: AiStreamRequest) = when (step++) {
                0 -> flowOf(
                    AiStreamEvent.ToolCallReady(
                        AiToolCall("call-meta", "update_note_metadata", """{"noteId":"note-1","expectedRevision":"dev:1","propertyUpdates":{"prop-status":"done"}}"""),
                    ),
                    AiStreamEvent.ToolCallReady(
                        AiToolCall("call-patch", "patch_note_blocks", """{"noteId":"note-1","expectedRevision":"dev:1","operations":[{"op":"replace_block","blockId":"blk-2","markdown":"更新后文本"}]}"""),
                    ),
                    AiStreamEvent.ToolCallReady(
                        AiToolCall("call-del", "delete_note", """{"noteId":"note-1","expectedRevision":"dev:1"}"""),
                    ),
                    AiStreamEvent.Completed(AiTokenUsage(uncachedInputTokens = 120, outputTokens = 40), AiFinishReason.TOOL_CALLS),
                )
                else -> flowOf(
                    AiStreamEvent.TextDelta("已准备好合并修改提案 [1]。"),
                    AiStreamEvent.Completed(AiTokenUsage(uncachedInputTokens = 180, outputTokens = 25), AiFinishReason.STOP),
                )
            }

            override suspend fun testConnection(connection: AiProviderConnection, resolvedApiKey: String?) =
                AiConnectionTestResult(reachable = true, latencyMillis = 1L)

            override suspend fun listModels(connection: AiProviderConnection, resolvedApiKey: String?) = emptyList<AiRemoteModelInfo>()

            override suspend fun probeToolCalling(connection: AiProviderConnection, resolvedApiKey: String?, modelId: String) =
                AiToolProbeResult(supported = true)
        }

        val fakeWorkspace = object : AgentHarnessWorkspacePort {
            override suspend fun resolveExplicitContext(refs: List<AgentContextRef>, budget: AiContextBudget) =
                AgentResolvedContextPayload(
                    formattedContextBlock = "引用笔记 note-1",
                    sources = listOf(AgentRawSourceCandidate(ObjectId("note-1"), "同步设计", BlockId("blk-2"), "dev:1", "原文本")),
                    blockedRefIds = emptySet(),
                    truncatedByBudget = false,
                )

            override suspend fun executeReadTool(
                callId: String,
                tool: AgentToolName,
                argumentsJson: String,
                retrievalScope: AiRetrievalScope,
                activeRefs: List<AgentContextRef>,
                maxResultCharacters: Int,
            ): AgentReadToolExecutionOutcome = error("unexpected read tool")

            override suspend fun prepareWriteProposal(
                callId: String,
                tool: AgentToolName,
                argumentsJson: String,
                assistantMessageId: String,
                retrievalScope: AiRetrievalScope,
                activeRefs: List<AgentContextRef>,
            ): AgentProposalToolOutcome {
                val item = if (tool == AgentToolName.UPDATE_NOTE_METADATA) {
                    AgentProposalItem(
                        id = "item-meta-1",
                        kind = AgentProposalItemKind.PROPERTY,
                        toolLabel = "update_note_metadata",
                        label = "属性 · 状态",
                        oldPreview = "评审中",
                        newPreview = "已验收",
                        propertyId = PropertyId("prop-status"),
                        newPropertyValue = JsonPrimitive("done"),
                    )
                } else {
                    AgentProposalItem(
                        id = "item-blk-1",
                        kind = AgentProposalItemKind.REPLACE_BLOCK,
                        toolLabel = "patch_note_blocks",
                        label = "替换段落 · blk-2",
                        oldPreview = "原文本",
                        newPreview = "更新后文本",
                        targetBlockId = BlockId("blk-2"),
                        markdown = "更新后文本",
                    )
                }
                return AgentProposalToolOutcome(
                    resultJson = """{"status":"pending_confirmation","proposalId":"prop-$callId"}""",
                    proposal = AgentMutationProposal(
                        id = "prop-$callId",
                        messageId = assistantMessageId,
                        kind = AgentProposalKind.PATCH_NOTE,
                        noteId = ObjectId("note-1"),
                        noteTitle = "同步设计",
                        expectedRevision = "dev:1",
                        items = listOf(item),
                        createdAt = "2026-11-14T06:00:00Z",
                        updatedAt = "2026-11-14T06:00:00Z",
                    ),
                    trace = AgentToolTrace(callId = callId, toolName = tool.name.lowercase(), summary = "生成修改提案"),
                )
            }
        }

        val initialThread = AgentThread(
            id = ObjectId("th-1"),
            title = "新会话",
            createdAt = "2026-11-14T05:59:00Z",
            lastMessageAt = "2026-11-14T05:59:00Z",
            retrievalScope = AiRetrievalScope.CITED_ONLY,
        )
        val output = AgentHarness(fakeStream).runTurn(
            input = AgentHarnessTurnInput(
                thread = initialThread,
                userMessageId = "msg-u1",
                assistantMessageId = "msg-a1",
                userPrompt = "帮我更新状态并替换第二段",
                contextRefs = listOf(AgentContextRef("ref-1", AgentContextRefKind.NOTE, noteId = ObjectId("note-1"), label = "同步设计")),
                model = AiModelConfig(
                    id = "conn:deepseek",
                    connectionId = "conn",
                    modelId = "deepseek-chat",
                    displayName = "DeepSeek",
                    toolsState = AiModelToolsState.VERIFIED,
                ),
                connection = AiProviderConnection(
                    connectionId = "conn",
                    name = "Local",
                    baseUrl = "http://127.0.0.1:11434/v1",
                    protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                    authMode = AiAuthMode.NONE,
                ),
                resolvedApiKey = null,
                preferences = AiAgentPreferences(),
                nowIsoProvider = { "2026-11-14T06:00:05Z" },
            ),
            workspacePort = fakeWorkspace,
        )

        assertEquals(1, output.generatedProposals.size)
        val combined = output.generatedProposals.single()
        assertEquals(1, combined.metadataItemCount)
        assertEquals(1, combined.blockItemCount)
        assertEquals(1, output.updatedThread.proposals.size)
        assertEquals(emptyList(), AgentThreadValidator.validate(output.updatedThread))
        assertTrue(output.assistantMessage.toolTraces.any { it.toolName == "delete_note" && !it.succeeded })
    }

    /** 验证双端并发修改同一会话提案状态与逐项选择时满足三方合并收敛与交换律。 */
    @Test
    fun replicaMergeConvergesConcurrentProposalStatusAndPerItemSelection() {
        val baseProposal = AgentMutationProposal(
            id = "prop-1",
            messageId = "msg-a1",
            kind = AgentProposalKind.PATCH_NOTE,
            noteId = ObjectId("note-1"),
            noteTitle = "同步设计",
            expectedRevision = "dev:1",
            status = AgentProposalStatus.PENDING,
            items = listOf(
                AgentProposalItem("i1", AgentProposalItemKind.PROPERTY, "update_note_metadata", "状态", accepted = true, propertyId = PropertyId("p1"), newPropertyValue = JsonPrimitive("ok")),
                AgentProposalItem("i2", AgentProposalItemKind.REPLACE_BLOCK, "patch_note_blocks", "段落", accepted = true, targetBlockId = BlockId("b1"), markdown = "新段"),
            ),
            createdAt = "2026-11-14T06:00:00Z",
            updatedAt = "2026-11-14T06:00:00Z",
        )
        val baseThread = AgentThread(
            id = ObjectId("th-merge"),
            title = "合并测试",
            createdAt = "2026-11-14T06:00:00Z",
            lastMessageAt = "2026-11-14T06:00:00Z",
            messages = listOf(
                AgentThreadMessage("msg-u1", AiChatRole.USER, "修改", createdAt = "2026-11-14T06:00:00Z"),
                AgentThreadMessage("msg-a1", AiChatRole.ASSISTANT, "提案如下", createdAt = "2026-11-14T06:00:01Z"),
            ),
            proposals = listOf(baseProposal),
        )
        val localPendingSkipped = baseThread.copy(
            proposals = listOf(
                baseProposal.copy(
                    items = listOf(baseProposal.items[0].copy(accepted = false), baseProposal.items[1]),
                    updatedAt = "2026-11-14T06:01:00Z",
                ),
            ),
        )
        val remoteApplied = baseThread.copy(
            proposals = listOf(
                baseProposal.copy(
                    status = AgentProposalStatus.APPLIED,
                    appliedRevision = "dev:2",
                    appliedSummary = "已提交",
                    updatedAt = "2026-11-14T06:02:00Z",
                ),
            ),
        )

        val baseJson = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), baseThread)
        val localJson = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), localPendingSkipped)
        val remoteJson = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), remoteApplied)

        val mergedLR = CanonicalJson.format.decodeFromJsonElement(
            AgentThread.serializer(),
            AgentThreadReplicaMerge.merge(baseJson, localJson, remoteJson, MergeSide.LOCAL).payload,
        )
        val mergedRL = CanonicalJson.format.decodeFromJsonElement(
            AgentThread.serializer(),
            AgentThreadReplicaMerge.merge(baseJson, remoteJson, localJson, MergeSide.REMOTE).payload,
        )

        assertEquals(AgentProposalStatus.APPLIED, mergedLR.proposals.single().status)
        assertEquals("dev:2", mergedLR.proposals.single().appliedRevision)
        assertEquals(mergedLR, mergedRL)
    }

    /** 同步执行无挂起点内存测试协程。 */
    private fun runSuspendImmediate(block: suspend () -> Unit) {
        var failure: Throwable? = null
        var finished = false
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    finished = true
                    failure = result.exceptionOrNull()
                }
            },
        )
        failure?.let { throw it }
        assertTrue(finished)
    }
}
