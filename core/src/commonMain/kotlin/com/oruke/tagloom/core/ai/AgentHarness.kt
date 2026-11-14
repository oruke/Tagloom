package com.oruke.tagloom.core.ai

import kotlinx.coroutines.CancellationException

/**
 * 描述流式生成与工具循环进行期间向纯内存 UI 视图发出的实时增量状态（不触发任何磁盘写入）。
 */
data class AgentHarnessProgress(
    /** 当前会话对象 ID。 */
    val threadId: com.oruke.tagloom.core.model.ObjectId,
    /** 正在生成的助手消息稳定 ID。 */
    val assistantMessageId: String,
    /** 已流式接收的正文文本。 */
    val streamingContent: String,
    /** 已流式接收的思考推理摘要。 */
    val streamingThinking: String,
    /** 已执行完毕或进行中的只读工具轨迹列表。 */
    val toolTraces: List<AgentToolTrace>,
    /** 当前轮次已在 `SourceLedger` 登记的真实来源列表。 */
    val sources: List<AgentSourceEntry>,
    /** 因策略变为 `LOCKED` 而被自动拦截未发送的 `@` 引用 ID 集合。 */
    val blockedRefIds: Set<String> = emptySet(),
)

/**
 * 描述调用内置 [AgentHarness] 执行单轮问答与受控只读工具循环的输入上下文。
 */
data class AgentHarnessTurnInput(
    /** 当前会话快照载荷。 */
    val thread: AgentThread,
    /** 本轮新增的用户消息 ID。 */
    val userMessageId: String,
    /** 本轮待生成的助手消息 ID。 */
    val assistantMessageId: String,
    /** 用户输入的问题文本。 */
    val userPrompt: String,
    /** 用户在作曲框挂载的 `@` 引用列表。 */
    val contextRefs: List<AgentContextRef>,
    /** 选中的模型配置。 */
    val model: AiModelConfig,
    /** 模型所属的 Provider 连接配置。 */
    val connection: AiProviderConnection,
    /** 仅在已解锁会话内存中按需解析的 API Key；免密模式为空。 */
    val resolvedApiKey: String?,
    /** 工作区共享运行偏好与单轮护栏。 */
    val preferences: AiAgentPreferences,
    /** 用户提问与回答落盘使用的 UTC ISO-8601 时间戳提供器。 */
    val nowIsoProvider: () -> String,
    /** 判断当前取消是否来自用户主动点击「停止」按钮（而非工作区锁定销毁）。 */
    val isStoppedByUser: () -> Boolean = { false },
)

/**
 * 描述内置 [AgentHarness] 单轮运行完成（或被用户手动停止）后的确定性内存结果，供调用方执行单次低频持久化。
 */
data class AgentHarnessTurnOutput(
    /** 追加了本轮用户消息、助手回复、累计 Token 账单与最新上下文分段基准的完整会话。 */
    val updatedThread: AgentThread,
    /** 本轮新生成的用户消息。 */
    val userMessage: AgentThreadMessage,
    /** 本轮新生成的助手消息。 */
    val assistantMessage: AgentThreadMessage,
    /** 因策略变为 `LOCKED` 而被拦截失效的 `@` 引用 ID 集合。 */
    val blockedRefIds: Set<String>,
)

/**
 * Tagloom 唯一内置 Agent Harness 执行状态机。
 *
 * 负责在进程内组装 `@` 上下文、按模型能力分级挂载只读知识工具、驱动多步流式推理循环、
 * 维护防幻觉 [SourceLedger] 来源编号并计算混合上下文水位基准。
 *
 * @param streamPort 底层可取消流式模型通信端口。
 */
class AgentHarness(
    private val streamPort: AiStreamPort,
) {
    /**
     * 执行一轮完整的用户提问与受控只读工具循环。
     *
     * @param input 单轮执行输入参数与连接上下文。
     * @param workspacePort 当前已解锁工作区只读检索端口。
     * @param onProgress 增量流式回调（仅更新内存 UI 状态，禁止写盘）。
     * @return 可用于单次快照事务确认的会话更新结果。
     */
    suspend fun runTurn(
        input: AgentHarnessTurnInput,
        workspacePort: AgentHarnessWorkspacePort,
        onProgress: (AgentHarnessProgress) -> Unit = {},
    ): AgentHarnessTurnOutput {
        val startedAt = input.nowIsoProvider()
        val budget = input.preferences.toContextBudget()
        val resolvedContext = workspacePort.resolveExplicitContext(input.contextRefs, budget)
        val validContextRefs = input.contextRefs.filterNot { ref -> ref.id in resolvedContext.blockedRefIds }
        val ledger = SourceLedger()
        resolvedContext.sources.forEach { candidate ->
            ledger.register(
                noteId = candidate.noteId,
                noteTitle = candidate.noteTitle,
                blockId = candidate.blockId,
                revision = candidate.revision,
                rawExcerpt = candidate.excerpt,
            )
        }

        val effectiveScope = if (input.model.toolsState == AiModelToolsState.CHAT_ONLY) {
            AiRetrievalScope.CITED_ONLY
        } else {
            input.thread.retrievalScope
        }
        val canUseTools = input.model.toolsState != AiModelToolsState.CHAT_ONLY &&
            (effectiveScope == AiRetrievalScope.ALL_NOTES || validContextRefs.any { ref ->
                ref.kind == AgentContextRefKind.TAG_SCOPE || ref.kind == AgentContextRefKind.SAVED_QUERY_SCOPE
            })
        val exposedTools = if (canUseTools) AgentReadToolCatalog.definitions else emptyList()
        val systemPrompt = AgentHarnessPromptService.buildSystemPrompt(effectiveScope, canUseTools)
        val userMessage = AgentThreadMessage(
            id = input.userMessageId,
            role = AiChatRole.USER,
            content = input.userPrompt.trim(),
            createdAt = startedAt,
            contextRefs = validContextRefs,
        )

        val conversationMessages = AgentHarnessPromptService.buildInitialMessages(
            thread = input.thread,
            systemPrompt = systemPrompt,
            userPrompt = userMessage.content,
            explicitContextBlock = resolvedContext.formattedContextBlock,
            initialSources = ledger.entries(),
        ).toMutableList()

        val toolTraces = mutableListOf<AgentToolTrace>()
        val contentBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var turnUsage = AiTokenUsage()
        var latestStepInputTokens = 0
        val guardrails = mutableListOf<String>()
        if (resolvedContext.truncatedByBudget) {
            guardrails += "已按单轮上下文预算（最多 ${budget.maxNotes} 篇笔记 / ${budget.maxBlocks} 个段落）自动裁剪显式引用内容"
        }

        fun emitProgress() {
            onProgress(
                AgentHarnessProgress(
                    threadId = input.thread.id,
                    assistantMessageId = input.assistantMessageId,
                    streamingContent = contentBuffer.toString(),
                    streamingThinking = thinkingBuffer.toString(),
                    toolTraces = toolTraces.toList(),
                    sources = ledger.entries(),
                    blockedRefIds = resolvedContext.blockedRefIds,
                ),
            )
        }

        emitProgress()
        val effectiveThinking = if (input.thread.thinkingEffort in input.model.supportedThinkingEfforts) {
            input.thread.thinkingEffort
        } else {
            AiThinkingEffort.OFF
        }

        var stoppedByUser = false
        val maxRounds = input.preferences.maxToolRounds.coerceIn(1, 20)
        try {
            var round = 0
            while (round < maxRounds) {
                round += 1
                val stepContent = StringBuilder()
                val readyCalls = mutableListOf<AiToolCall>()
                var stepUsage: AiTokenUsage? = null
                streamPort.stream(
                    AiStreamRequest(
                        connection = input.connection,
                        resolvedApiKey = input.resolvedApiKey,
                        modelId = input.model.modelId,
                        messages = conversationMessages.toList(),
                        tools = exposedTools,
                        thinkingEffort = effectiveThinking,
                    ),
                ).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> {
                            stepContent.append(event.text)
                            contentBuffer.append(event.text)
                            emitProgress()
                        }
                        is AiStreamEvent.ThinkingDelta -> {
                            thinkingBuffer.append(event.text)
                            emitProgress()
                        }
                        is AiStreamEvent.ToolCallReady -> {
                            readyCalls += event.toolCall
                        }
                        is AiStreamEvent.Completed -> {
                            stepUsage = event.usage
                        }
                        is AiStreamEvent.ToolCallStarted,
                        is AiStreamEvent.ToolCallArgumentsDelta,
                        -> Unit
                    }
                }

                stepUsage?.let { usage ->
                    turnUsage += usage
                    if (usage.totalInputTokens > 0) {
                        latestStepInputTokens = usage.totalInputTokens
                    }
                }

                if (readyCalls.isEmpty() || !canUseTools) {
                    break
                }

                conversationMessages += AiChatMessage(
                    role = AiChatRole.ASSISTANT,
                    content = stepContent.toString(),
                    toolCalls = readyCalls.toList(),
                )

                for (call in readyCalls) {
                    val toolEnum = AgentReadToolCatalog.resolveReadTool(call.name)
                    if (toolEnum == null) {
                        val unsupportedJson = """{"error":"tool_not_allowed"}"""
                        toolTraces += AgentToolTrace(
                            callId = call.callId,
                            toolName = call.name,
                            summary = "拒绝未开放工具 ${call.name}",
                            succeeded = false,
                        )
                        conversationMessages += AiChatMessage(
                            role = AiChatRole.TOOL,
                            content = unsupportedJson,
                            toolCallId = call.callId,
                            toolName = call.name,
                        )
                        emitProgress()
                        continue
                    }
                    val outcome = workspacePort.executeReadTool(
                        callId = call.callId,
                        tool = toolEnum,
                        argumentsJson = call.argumentsJson,
                        retrievalScope = effectiveScope,
                        activeRefs = validContextRefs,
                        maxResultCharacters = budget.maxCharacters,
                    )
                    val assignedIndices = outcome.sources.map { raw ->
                        ledger.register(
                            noteId = raw.noteId,
                            noteTitle = raw.noteTitle,
                            blockId = raw.blockId,
                            revision = raw.revision,
                            rawExcerpt = raw.excerpt,
                        ).sourceIndex
                    }.distinct()
                    toolTraces += outcome.trace
                    val enrichedToolContent = if (assignedIndices.isEmpty()) {
                        outcome.resultJson
                    } else {
                        val indexHint = assignedIndices.joinToString(", ") { "[$it]" }
                        "${outcome.resultJson}\n(可用来源角标: $indexHint)"
                    }
                    conversationMessages += AiChatMessage(
                        role = AiChatRole.TOOL,
                        content = enrichedToolContent,
                        toolCallId = call.callId,
                        toolName = call.name,
                    )
                    emitProgress()
                }

                if (round == maxRounds) {
                    guardrails += "已达到单轮最大工具循环步数（$maxRounds 步），以下基于已获取证据总结回答"
                    // 最后追加一次不带工具定义的收尾生成，保证用户获得完整结论而非停在半截工具调用上。
                    streamPort.stream(
                        AiStreamRequest(
                            connection = input.connection,
                            resolvedApiKey = input.resolvedApiKey,
                            modelId = input.model.modelId,
                            messages = conversationMessages + AiChatMessage(
                                role = AiChatRole.USER,
                                content = "已达到单轮最大工具读取步数，请直接基于上面已经获取到的笔记证据给出准确回答，并标注已登记的 [序号] 来源角标。",
                            ),
                            tools = emptyList(),
                            thinkingEffort = effectiveThinking,
                        ),
                    ).collect { event ->
                        when (event) {
                            is AiStreamEvent.TextDelta -> {
                                contentBuffer.append(event.text)
                                emitProgress()
                            }
                            is AiStreamEvent.ThinkingDelta -> {
                                thinkingBuffer.append(event.text)
                                emitProgress()
                            }
                            is AiStreamEvent.Completed -> {
                                event.usage?.let { usage ->
                                    turnUsage += usage
                                    if (usage.totalInputTokens > 0) {
                                        latestStepInputTokens = usage.totalInputTokens
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!input.isStoppedByUser()) {
                throw cancelled
            }
            stoppedByUser = true
        }

        val completedAt = input.nowIsoProvider()
        val finalUsage = if (turnUsage.totalTokens > 0) {
            turnUsage
        } else {
            AgentHarnessPromptService.estimateFallbackUsage(
                conversationMessages,
                contentBuffer.toString(),
                thinkingBuffer.toString(),
            )
        }
        val assistantMessage = AgentThreadMessage(
            id = input.assistantMessageId,
            role = AiChatRole.ASSISTANT,
            content = contentBuffer.toString().trim(),
            thinkingSummary = thinkingBuffer.toString().trim(),
            createdAt = completedAt,
            toolTraces = toolTraces.toList(),
            sources = ledger.entries(),
            usage = finalUsage,
            stoppedByUser = stoppedByUser,
            guardrailNotice = guardrails.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
        val updatedMessages = input.thread.messages + userMessage + assistantMessage
        val resolvedTitle = if (
            input.thread.messages.isEmpty() &&
            AgentHarnessPromptService.isDefaultPlaceholderTitle(input.thread.title)
        ) {
            AgentHarnessPromptService.deriveInitialTitle(input.userPrompt)
        } else {
            input.thread.title
        }
        val breakdown = AgentHarnessPromptService.estimateContextBreakdown(
            systemPrompt = systemPrompt,
            exposedToolsCount = exposedTools.size,
            explicitContextText = resolvedContext.formattedContextBlock,
            messages = updatedMessages,
            activeCompaction = input.thread.activeCompactionId?.let { activeId ->
                input.thread.compactions.firstOrNull { it.id == activeId }
            },
            providerReportedInputTokens = latestStepInputTokens,
        )
        val draftUpdated = input.thread.copy(
            title = resolvedTitle,
            lastMessageAt = completedAt,
            selectedModelId = input.model.id,
            retrievalScope = effectiveScope,
            activeContextRefs = validContextRefs,
            messages = updatedMessages,
            latestContextBreakdown = breakdown,
        )
        val finalThread = draftUpdated.copy(cumulativeUsage = draftUpdated.recomputeCumulativeUsage())
        return AgentHarnessTurnOutput(
            updatedThread = finalThread,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            blockedRefIds = resolvedContext.blockedRefIds,
        )
    }
}
