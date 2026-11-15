package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf

/**
 * 验证阶段四会话累计计费 `StatsPills` 核算、混合口径 `ContextMeter` 实时重算、
 * `80%` 自动/手动两阶段上下文压缩、近期 `16%` 原文与活跃 `@` 引用/`PENDING` 提案保留以及账单守恒不变量。
 */
class Stage4TokenMeterAndCompactionTest {
    /** 验证紧凑/千分位格式化、前缀缓存命中率计算、草稿与 `@` 引用实时增量以及切换模型窗口后的即时重算。 */
    @Test
    fun calculatesTokenUsageLedgerAndReactiveContextMeterAcrossWindowChanges() {
        assertEquals("0", AgentTokenMetricsCalculator.formatCompactTokens(0))
        assertEquals("850", AgentTokenMetricsCalculator.formatCompactTokens(850))
        assertEquals("33.9K", AgentTokenMetricsCalculator.formatCompactTokens(33_910))
        assertEquals("128K", AgentTokenMetricsCalculator.formatCompactTokens(128_000))
        assertEquals("1M", AgentTokenMetricsCalculator.formatCompactTokens(1_000_000))
        assertEquals("48,210", AgentTokenMetricsCalculator.formatExactTokens(48_210))
        assertEquals("1,024,500", AgentTokenMetricsCalculator.formatExactTokens(1_024_500))

        val emptyUsage = AgentTokenMetricsCalculator.computeUsageMetrics(AiTokenUsage())
        assertEquals(0, emptyUsage.totalTokens)
        assertNull(emptyUsage.cacheHitPercent)

        val activeUsage = AgentTokenMetricsCalculator.computeUsageMetrics(
            AiTokenUsage(
                uncachedInputTokens = 10_800,
                cacheReadTokens = 34_200,
                outputTokens = 3_210,
                reasoningTokens = 1_120,
            ),
        )
        assertEquals(45_000, activeUsage.totalInputTokens)
        assertEquals(48_210, activeUsage.totalTokens)
        assertEquals(76, activeUsage.cacheHitPercent)
        assertEquals(1_120, activeUsage.reasoningTokens)

        val prefs = AiAgentPreferences(autoCompactEnabled = true, autoCompactPercent = 80, retainPercent = 16)
        val model128k = AiModelConfig(
            id = "conn:deepseek-v3",
            connectionId = "conn",
            modelId = "deepseek-chat",
            displayName = "DeepSeek 128K",
            contextWindowTokens = 128_000,
            toolsState = AiModelToolsState.VERIFIED,
        )
        val model64k = model128k.copy(
            id = "conn:local-64k",
            displayName = "Local 64K",
            contextWindowTokens = 64_000,
        )
        val thread = AgentThread(
            id = ObjectId("th-meter"),
            title = "水位重算验证",
            createdAt = "2026-11-14T07:00:00Z",
            lastMessageAt = "2026-11-14T07:05:00Z",
            messages = listOf(
                AgentThreadMessage("m1", AiChatRole.USER, "检查同步快照体积", createdAt = "2026-11-14T07:00:00Z"),
                AgentThreadMessage("m2", AiChatRole.ASSISTANT, "当前水位处于安全区间。", createdAt = "2026-11-14T07:00:02Z"),
            ),
            latestContextBreakdown = AgentContextBreakdown(
                systemTokens = 2_000,
                toolsTokens = 6_000,
                messageTokens = 38_000,
            ),
        )

        // 128K 窗口下 46K/128K ≈ 36%，自动压缩线 102,400，处于正常青绿色区间
        val meter128k = AgentTokenMetricsCalculator.computeContextMeterMetrics(
            thread = thread,
            model = model128k,
            preferences = prefs,
        )
        assertEquals(128_000, meter128k.contextWindowTokens)
        assertEquals(102_400, meter128k.thresholdTokens)
        assertEquals(46_000, meter128k.usedTokens)
        assertEquals(36, meter128k.percent)
        assertFalse(meter128k.nearThreshold)
        assertFalse(meter128k.overThreshold)

        // 切换到 64K 模型窗口后无需发消息即时重算：46K/64K ≈ 72%（位于 80%-12%=68%..80% 琥珀色预警区间）
        val meter64k = AgentTokenMetricsCalculator.computeContextMeterMetrics(
            thread = thread,
            model = model64k,
            preferences = prefs,
        )
        assertEquals(64_000, meter64k.contextWindowTokens)
        assertEquals(51_200, meter64k.thresholdTokens)
        assertEquals(72, meter64k.percent)
        assertTrue(meter64k.nearThreshold)
        assertFalse(meter64k.overThreshold)

        // 在作曲框输入长草稿并挂载新增 @ 引用后，实时叠加增量越过 51.2K 自动压缩线进入红色告警态
        val longDraft = "请结合这段详细压测日志继续分析同步吞吐率波动原因。".repeat(260)
        val meterWithDraft = AgentTokenMetricsCalculator.computeContextMeterMetrics(
            thread = thread,
            model = model64k,
            preferences = prefs,
            composerDraft = longDraft,
            activeContextRefs = listOf(
                AgentContextRef("ref-note", AgentContextRefKind.NOTE, noteId = ObjectId("n1"), label = "压测记录"),
            ),
            resolvedRefText = "单事务双层信封写入延迟分布数据段落。".repeat(120),
        )
        assertTrue(meterWithDraft.draftTokens > 0)
        assertTrue(meterWithDraft.addedRefTokens > 0)
        assertTrue(meterWithDraft.usedTokens >= meter64k.thresholdTokens)
        assertTrue(meterWithDraft.overThreshold)
    }

    /** 验证单轮回复越过 `80%` 水位线时自动触发两阶段压缩，保留近期对话、活跃 `@` 引用、待审提案且绝不清零累计账单。 */
    @Test
    fun autoCompactsAtEightyPercentThresholdPreservingRecentTurnsRefsPendingProposalsAndCumulativeBilling() = runSuspendImmediate {
        var callSeq = 0
        val fakeStream = object : AiStreamPort {
            override fun stream(request: AiStreamRequest) = when (callSeq++) {
                // 第 0 次请求：本轮正常业务问答，模型上报输入 Token 14,200（超过 16,000 窗口的 80% = 12,800）
                0 -> flowOf(
                    AiStreamEvent.TextDelta("已完成第 4 轮压测结论核对 [1]。"),
                    AiStreamEvent.Completed(
                        usage = AiTokenUsage(
                            uncachedInputTokens = 4_200,
                            cacheReadTokens = 10_000,
                            outputTokens = 180,
                            reasoningTokens = 60,
                        ),
                        finishReason = AiFinishReason.STOP,
                    ),
                )
                // 第 1 次请求：两阶段压缩引擎自动发起的无工具轻量浓缩请求
                else -> {
                    assertTrue(request.tools.isEmpty(), "轻量压缩摘要请求严禁挂载工具 Schema")
                    assertEquals(AiThinkingEffort.OFF, request.thinkingEffort, "轻量压缩摘要请求应关闭深度思考")
                    flowOf(
                        AiStreamEvent.TextDelta("早期三轮讨论已确认双层加密快照结构与同步确认点契约，修剪了长工具返回。"),
                        AiStreamEvent.Completed(
                            usage = AiTokenUsage(
                                uncachedInputTokens = 140,
                                cacheReadTokens = 420,
                                outputTokens = 55,
                                reasoningTokens = 0,
                            ),
                            finishReason = AiFinishReason.STOP,
                        ),
                    )
                }
            }

            override suspend fun testConnection(connection: AiProviderConnection, resolvedApiKey: String?) =
                AiConnectionTestResult(reachable = true, latencyMillis = 1L)

            override suspend fun listModels(connection: AiProviderConnection, resolvedApiKey: String?) = emptyList<AiRemoteModelInfo>()

            override suspend fun probeToolCalling(connection: AiProviderConnection, resolvedApiKey: String?, modelId: String) =
                AiToolProbeResult(supported = true)
        }

        val activeRef = AgentContextRef(
            id = "ref-blocks-1",
            kind = AgentContextRefKind.BLOCKS,
            noteId = ObjectId("note-sync"),
            blockIds = listOf(BlockId("blk-1"), BlockId("blk-2")),
            label = "同步设计 · 2 段",
        )
        val fakeWorkspace = object : AgentHarnessWorkspacePort {
            override suspend fun resolveExplicitContext(refs: List<AgentContextRef>, budget: AiContextBudget) =
                AgentResolvedContextPayload(
                    formattedContextBlock = "引用段落 blk-1, blk-2",
                    sources = listOf(AgentRawSourceCandidate(ObjectId("note-sync"), "同步设计", BlockId("blk-1"), "dev:3", "摘录")),
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
        }

        val pendingProposal = AgentMutationProposal(
            id = "prop-pending-1",
            messageId = "msg-a2",
            kind = AgentProposalKind.PATCH_NOTE,
            noteId = ObjectId("note-sync"),
            noteTitle = "同步设计",
            expectedRevision = "dev:3",
            status = AgentProposalStatus.PENDING,
            items = listOf(
                AgentProposalItem(
                    id = "item-1",
                    kind = AgentProposalItemKind.REPLACE_BLOCK,
                    toolLabel = "patch_note_blocks",
                    label = "替换段落 · blk-1",
                    oldPreview = "旧文案",
                    newPreview = "新文案",
                    targetBlockId = BlockId("blk-1"),
                    markdown = "新文案",
                ),
            ),
            createdAt = "2026-11-14T07:10:00Z",
            updatedAt = "2026-11-14T07:10:00Z",
        )

        val priorMessages = listOf(
            AgentThreadMessage("msg-u1", AiChatRole.USER, "第一轮：请读取整篇同步设计全量结构", createdAt = "2026-11-14T07:01:00Z"),
            AgentThreadMessage(
                id = "msg-a1",
                role = AiChatRole.ASSISTANT,
                content = "第一轮结论：包含加密信封与修订链。",
                toolTraces = listOf(AgentToolTrace("c1", "get_note_document", "读取 42 段正文")),
                sources = listOf(AgentSourceEntry(1, ObjectId("note-sync"), "同步设计", BlockId("blk-1"), "dev:3", "摘录")),
                usage = AiTokenUsage(uncachedInputTokens = 900, cacheReadTokens = 1_200, outputTokens = 200),
                createdAt = "2026-11-14T07:01:05Z",
            ),
            AgentThreadMessage("msg-u2", AiChatRole.USER, "第二轮：生成一段修订提案供我稍后审阅", createdAt = "2026-11-14T07:05:00Z"),
            AgentThreadMessage(
                id = "msg-a2",
                role = AiChatRole.ASSISTANT,
                content = "第二轮结论：已生成待审 Diff 提案。",
                usage = AiTokenUsage(uncachedInputTokens = 800, cacheReadTokens = 2_100, outputTokens = 160),
                createdAt = "2026-11-14T07:05:05Z",
            ),
            AgentThreadMessage("msg-u3", AiChatRole.USER, "第三轮：确认离线三方合并的交换律约束", createdAt = "2026-11-14T07:08:00Z"),
            AgentThreadMessage(
                id = "msg-a3",
                role = AiChatRole.ASSISTANT,
                content = "第三轮结论：LOCAL 与 REMOTE 对调结果恒等。",
                usage = AiTokenUsage(uncachedInputTokens = 700, cacheReadTokens = 3_400, outputTokens = 150),
                createdAt = "2026-11-14T07:08:05Z",
            ),
        )
        val seedThread = AgentThread(
            id = ObjectId("th-compact"),
            title = "自动压缩测试",
            createdAt = "2026-11-14T07:00:00Z",
            lastMessageAt = "2026-11-14T07:08:05Z",
            activeContextRefs = listOf(activeRef),
            messages = priorMessages,
            proposals = listOf(pendingProposal),
        ).let { it.copy(cumulativeUsage = it.recomputeCumulativeUsage()) }

        val cumulativeBeforeTurn = seedThread.cumulativeUsage.totalTokens
        val model16k = AiModelConfig(
            id = "conn:compact-16k",
            connectionId = "conn",
            modelId = "deepseek-chat",
            displayName = "Compact 16K",
            contextWindowTokens = 16_000,
            toolsState = AiModelToolsState.VERIFIED,
        )
        val connection = AiProviderConnection(
            connectionId = "conn",
            name = "Mock",
            baseUrl = "http://127.0.0.1:11434/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.NONE,
        )

        val turnOutput = AgentHarness(fakeStream).runTurn(
            input = AgentHarnessTurnInput(
                thread = seedThread,
                userMessageId = "msg-u4",
                assistantMessageId = "msg-a4",
                userPrompt = "第四轮：核对压测吞吐指标",
                contextRefs = listOf(activeRef),
                model = model16k,
                connection = connection,
                resolvedApiKey = null,
                preferences = AiAgentPreferences(autoCompactEnabled = true, autoCompactPercent = 80, retainPercent = 16),
                nowIsoProvider = { "2026-11-14T07:12:00Z" },
                compactionIdFactory = { "cmp-auto-1" },
            ),
            workspacePort = fakeWorkspace,
        )

        val compacted = assertNotNull(turnOutput.triggeredCompaction, "达到 80% 阈值后应在单轮末尾自动生成压缩节点")
        assertEquals("cmp-auto-1", compacted.id)
        assertEquals(80, compacted.triggerPercent)
        assertTrue(compacted.freedTokens > 0, "自动压缩必须释放活跃上下文窗口 Token")
        assertTrue(compacted.summary.contains("早期三轮讨论已确认"))
        assertTrue(compacted.summary.contains("同步设计 · 2 段"))

        val updated = turnOutput.updatedThread
        assertEquals(emptyList(), AgentThreadValidator.validate(updated))
        // 非破坏性归档：全部 8 条原始消息（前 3 轮 6 条 + 本轮 2 条）100% 保留在加密快照中
        assertEquals(8, updated.messages.size)
        // 活跃 @ 引用与未决 PENDING 提案 100% 保留
        assertEquals(listOf(activeRef), updated.activeContextRefs)
        assertEquals(AgentProposalStatus.PENDING, updated.proposals.single().status)
        // 压缩后活跃窗口水位已显著回落至 80%（12,800）以下
        assertTrue(updated.latestContextBreakdown.usedTokens < 12_800)

        // 累计计费账单绝不清零，且精确等于历史消息消耗 + 本轮消耗 + 压缩摘要请求自身消耗
        assertEquals(updated.recomputeCumulativeUsage(), updated.cumulativeUsage)
        assertEquals(
            cumulativeBeforeTurn + (4_200 + 10_000 + 180) + (140 + 420 + 55),
            updated.cumulativeUsage.totalTokens,
        )

        // 验证下一轮组装 Prompt 时，已折叠的早期消息不再逐字发给模型，而近期保留轮次与活跃压缩摘要完整注入
        val nextPromptMessages = AgentHarnessPromptService.buildInitialMessages(
            thread = updated,
            systemPrompt = AgentHarnessPromptService.buildSystemPrompt(AiRetrievalScope.CITED_ONLY, true),
            userPrompt = "第五轮继续追问",
            explicitContextBlock = "引用段落 blk-1",
            initialSources = emptyList(),
        )
        assertTrue(nextPromptMessages.any { it.content.contains("【早期对话已自动压缩脉络摘要】") && it.content.contains("《同步设计》") })
        assertFalse(nextPromptMessages.any { it.content.contains("第一轮：请读取整篇同步设计全量结构") })
        assertTrue(nextPromptMessages.any { it.content.contains("第四轮：核对压测吞吐指标") })
    }

    /** 同步运行纯内存挂起测试块。 */
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
