package com.oruke.tagloom.core.ai

import kotlin.math.min
import kotlinx.coroutines.CancellationException

/**
 * 对标 DeepSeek Harness `dsh-compaction-basic` 的两阶段无损上下文压缩引擎。
 *
 * 核心不变量：
 * 1. **第一阶段（工具载荷修剪）**：剥离早期历史轮次中体积庞大的只读工具原始返回，仅提取凝练轨迹、来源锚点与核心问答结论；
 * 2. **第二阶段（结构化脉络浓缩）**：调用轻量模型请求（失败或离线时自动降级确定性结构化摘要）生成可展开的 [AgentCompactionItem]；
 * 3. **严格无损保留边界**：强制逐字保留近期约 `retainPercent`（默认 `16%`）对话轮次、当前所有活跃 `@` 引用及全部 `PENDING` 状态的修改提案；
 * 4. **非破坏性归档与账单守恒**：`AgentThread.messages` 加密历史原样保留不物理删除，会话累计计费 `cumulativeUsage` 绝不清零且照常计入摘要请求自身消耗。
 */
object AgentCompactionEngine {
    /** 近期逐字保留窗口最小保底 Token 预算。 */
    private const val MIN_RETAIN_BUDGET_TOKENS: Int = 600

    /** 单条消息摘要截取最大字符数。 */
    private const val MAX_SNIPPET_CHARS: Int = 220

    /**
     * 判断单轮回复完成后的会话是否满足自动上下文压缩触发条件。
     *
     * @param thread 刚完成本轮更新的会话载荷。
     * @param model 当前使用的模型配置（含 `contextWindowTokens`）。
     * @param preferences 工作区自动压缩与保留比例偏好。
     * @return 满足自动压缩条件时返回 `true`。
     */
    fun shouldAutoCompact(
        thread: AgentThread,
        model: AiModelConfig,
        preferences: AiAgentPreferences,
    ): Boolean {
        if (!preferences.autoCompactEnabled || thread.messages.isEmpty()) return false
        val thresholdTokens = model.autoCompactThresholdTokens(preferences.autoCompactPercent)
        if (thread.latestContextBreakdown.usedTokens < thresholdTokens) return false
        return selectCompactionCursorIndex(thread, model, preferences) != null
    }

    /**
     * 计算本次压缩应当折叠到的最后一条早期消息下标（保留其后的近期 `retainPercent` 对话逐字不折叠）。
     *
     * @param thread 当前会话载荷。
     * @param model 当前模型配置。
     * @param preferences 工作区压缩偏好。
     * @return 在 `thread.messages` 中的目标游标下标；无可压缩消息时返回 `null`。
     */
    fun selectCompactionCursorIndex(
        thread: AgentThread,
        model: AiModelConfig,
        preferences: AiAgentPreferences,
    ): Int? {
        if (thread.messages.isEmpty()) return null
        val prevCompaction = thread.activeCompactionId?.let { id ->
            thread.compactions.firstOrNull { it.id == id }
        }
        val uncompactedStart = prevCompaction?.let { item ->
            val idx = thread.messages.indexOfFirst { it.id == item.upToMessageId }
            if (idx >= 0) idx + 1 else 0
        } ?: 0
        if (uncompactedStart >= thread.messages.size) return null

        val uncompactedCount = thread.messages.size - uncompactedStart
        // 仅有一轮问答（<=2 条消息）时，仅当手动压缩或单轮超大工具返回触发时将该轮纳入摘要游标。
        if (uncompactedCount <= 2) {
            return thread.messages.lastIndex
        }

        val retainRatio = preferences.retainPercent.coerceIn(8, 40) / 100.0
        val retainBudget = ((model.contextWindowTokens * retainRatio).toInt())
            .coerceAtLeast(MIN_RETAIN_BUDGET_TOKENS)

        // 按「USER 起点..配对回复」从新到旧扫描，确保至少完整逐字保留最近一轮问答，并在预算内保留更多近期轮次。
        val turnStarts = mutableListOf<Int>()
        for (idx in uncompactedStart until thread.messages.size) {
            if (idx == uncompactedStart || thread.messages[idx].role == AiChatRole.USER) {
                turnStarts += idx
            }
        }
        if (turnStarts.size <= 1) {
            return thread.messages.lastIndex
        }

        var retainedStartIdx = turnStarts.last()
        var retainedTokens = estimateSliceTokens(thread.messages, retainedStartIdx, thread.messages.lastIndex)

        for (turnIdx in (turnStarts.lastIndex - 1) downTo 1) {
            val candidateStart = turnStarts[turnIdx]
            val candidateEnd = turnStarts[turnIdx + 1] - 1
            val turnTokens = estimateSliceTokens(thread.messages, candidateStart, candidateEnd)
            if (retainedTokens + turnTokens <= retainBudget) {
                retainedStartIdx = candidateStart
                retainedTokens += turnTokens
            } else {
                break
            }
        }
        return (retainedStartIdx - 1).takeIf { it >= uncompactedStart } ?: (turnStarts.last() - 1)
    }

    /**
     * 执行两阶段无损上下文压缩（优先使用流式端口请求轻量摘要，异常或离线时确定性生成结构化脉络摘要）。
     *
     * @param thread 待压缩的会话载荷。
     * @param compactionId 新压缩节点稳定不透明 ID。
     * @param triggerPercent 触发阈值百分比；手动点击「立即压缩」时传 `0`。
     * @param model 当前关联模型配置（未配置时使用默认 `128K` 窗口参数）。
     * @param preferences 工作区自动压缩与保留比例偏好。
     * @param createdAt 压缩完成 UTC ISO-8601 时间戳。
     * @param connection 可选 Provider 连接；提供且支持请求时发起真实轻量摘要。
     * @param resolvedApiKey 可选已解析本机或同步 API Key。
     * @param streamPort 可选流式模型通信端口。
     * @return 追加了 [AgentCompactionItem]、更新活跃游标、回落活跃窗口并累加计费账单的新 [AgentThread]。
     */
    suspend fun compactWithModelOrFallback(
        thread: AgentThread,
        compactionId: String,
        triggerPercent: Int,
        model: AiModelConfig?,
        preferences: AiAgentPreferences,
        createdAt: String,
        connection: AiProviderConnection? = null,
        resolvedApiKey: String? = null,
        streamPort: AiStreamPort? = null,
    ): AgentThread {
        val effectiveModel = model ?: defaultFallbackModel()
        val cursorIdx = selectCompactionCursorIndex(thread, effectiveModel, preferences) ?: return thread
        val upToMessage = thread.messages[cursorIdx]
        val prevCompaction = thread.activeCompactionId?.let { id ->
            thread.compactions.firstOrNull { it.id == id }
        }
        val prevCursorIdx = prevCompaction?.let { item ->
            thread.messages.indexOfFirst { it.id == item.upToMessageId }
        } ?: -1
        val newlyFoldedMessages = thread.messages.subList((prevCursorIdx + 1).coerceAtLeast(0), cursorIdx + 1)
        val retainedMessages = thread.messages.drop(cursorIdx + 1)
        val pendingProposals = thread.proposals.filter { it.status == AgentProposalStatus.PENDING }

        // 阶段一：修剪长工具原始载荷，提取结构化早期事实、来源角标、活跃引用与待审提案。
        val stage1Digest = buildDeterministicStage1Digest(
            previousSummary = prevCompaction?.summary,
            foldedMessages = newlyFoldedMessages,
            activeRefs = thread.activeContextRefs,
            pendingProposals = pendingProposals,
        )

        // 阶段二：若具备在线流式端口与连接则生成精炼脉络摘要，否则无缝回退至确定性结构化摘要。
        val modelOutcome = requestLightweightModelSummary(
            stage1Digest = stage1Digest,
            model = model,
            connection = connection,
            resolvedApiKey = resolvedApiKey,
            streamPort = streamPort,
        )
        val finalSummary = buildFinalSummaryText(
            condensedCore = modelOutcome?.summaryText ?: stage1Digest,
            retainedTurnCount = retainedMessages.count { it.role == AiChatRole.USER },
            activeRefs = thread.activeContextRefs,
            pendingProposals = pendingProposals,
        )

        val beforeBreakdown = resolvePreCompactionBreakdown(thread, effectiveModel)
        val afterBreakdown = computePostCompactionBreakdown(
            thread = thread,
            model = effectiveModel,
            preferences = preferences,
            summaryText = finalSummary,
            retainedMessages = retainedMessages,
            beforeBreakdown = beforeBreakdown,
        )
        val freedTokens = (beforeBreakdown.usedTokens - afterBreakdown.usedTokens).coerceAtLeast(0)
        val compactionUsage = modelOutcome?.usage ?: estimateLocalCompactionUsage(stage1Digest, finalSummary)

        val compactionItem = AgentCompactionItem(
            id = compactionId,
            createdAt = createdAt,
            upToMessageId = upToMessage.id,
            triggerPercent = triggerPercent.coerceIn(0, 100),
            freedTokens = freedTokens,
            summary = finalSummary,
            compactionUsage = compactionUsage,
        )
        val updated = thread.copy(
            // 强制保留活跃 @ 引用、完整加密历史消息与全部未决修改提案。
            activeContextRefs = thread.activeContextRefs,
            messages = thread.messages,
            proposals = thread.proposals,
            compactions = thread.compactions + compactionItem,
            activeCompactionId = compactionItem.id,
            latestContextBreakdown = afterBreakdown,
        )
        return updated.copy(cumulativeUsage = updated.recomputeCumulativeUsage())
    }

    /**
     * 构造阶段一去噪后的确定性脉络摘要素材（剥离庞大工具原始 JSON，保留问题、结论、来源与提案锚点）。
     */
    internal fun buildDeterministicStage1Digest(
        previousSummary: String?,
        foldedMessages: List<AgentThreadMessage>,
        activeRefs: List<AgentContextRef>,
        pendingProposals: List<AgentMutationProposal>,
    ): String = buildString {
        if (!previousSummary.isNullOrBlank()) {
            appendLine("【继承前序摘要】")
            appendLine(previousSummary.trim().take(480))
        }
        val foldedSources = linkedMapOf<String, AgentSourceEntry>()
        val foldedToolCount = foldedMessages.sumOf { it.toolTraces.size }
        appendLine("【已浓缩早期轮次（共 ${foldedMessages.size} 条消息，修剪 $foldedToolCount 次只读工具长输出）】")
        foldedMessages.takeLast(8).forEach { msg ->
            msg.sources.forEach { src ->
                foldedSources.putIfAbsent("${src.noteId.value}:${src.blockId?.value.orEmpty()}", src)
            }
            val rolePrefix = if (msg.role == AiChatRole.USER) "用户诉求" else "阶段结论"
            val snippet = msg.content.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ")
                .take(MAX_SNIPPET_CHARS)
            if (snippet.isNotEmpty()) {
                appendLine("- $rolePrefix：$snippet")
            }
        }
        if (foldedSources.isNotEmpty()) {
            val refs = foldedSources.values.take(6).joinToString("、") { src ->
                val blockPart = src.blockId?.value?.let { "#$it" }.orEmpty()
                "《${src.noteTitle}》$blockPart(${src.revision})"
            }
            appendLine("【早期核验来源】$refs")
        }
        if (activeRefs.isNotEmpty()) {
            appendLine("【活跃 @ 引用保留】${activeRefs.joinToString("、") { it.label }}")
        }
        if (pendingProposals.isNotEmpty()) {
            val propDesc = pendingProposals.joinToString("；") { prop ->
                "《${prop.noteTitle}》(${prop.items.size} 处待审变更)"
            }
            appendLine("【未决 Diff 提案保留】$propDesc")
        }
    }.trim()

    /**
     * 封装最终写入 [AgentCompactionItem.summary] 的结构化可读说明。
     */
    private fun buildFinalSummaryText(
        condensedCore: String,
        retainedTurnCount: Int,
        activeRefs: List<AgentContextRef>,
        pendingProposals: List<AgentMutationProposal>,
    ): String = buildString {
        appendLine(condensedCore.trim())
        val refNote = if (activeRefs.isNotEmpty()) {
            "活跃 @ 引用（${activeRefs.joinToString("、") { it.label }}）"
        } else {
            "当前作曲框引用状态"
        }
        val proposalNote = if (pendingProposals.isNotEmpty()) {
            "及 ${pendingProposals.size} 份待审 Diff 修改提案"
        } else {
            "及待审 Diff 修改提案"
        }
        append("【无损保留状态】近期 $retainedTurnCount 轮对话原文、${refNote}${proposalNote}均完整保留，底层加密历史消息流未删除。")
    }

    /**
     * 在连接可用时发起一次无工具、关闭深度思考的轻量摘要请求；若返回工具调用或异常则安全放弃并回退。
     */
    private suspend fun requestLightweightModelSummary(
        stage1Digest: String,
        model: AiModelConfig?,
        connection: AiProviderConnection?,
        resolvedApiKey: String?,
        streamPort: AiStreamPort?,
    ): ModelCompactionSummaryOutcome? {
        if (streamPort == null || connection == null || model == null) return null
        val promptMessages = listOf(
            AiChatMessage(
                role = AiChatRole.SYSTEM,
                content = "你是 Tagloom 会话上下文压缩器。请将输入的历史对话纪要浓缩为 200 字以内的结构化要点，完整保留用户目标、已确认事实、来源标题与未决修改事项，不要编造信息。",
            ),
            AiChatMessage(role = AiChatRole.USER, content = stage1Digest),
        )
        return try {
            val buffer = StringBuilder()
            var sawUnexpectedToolCall = false
            var reportedUsage: AiTokenUsage? = null
            streamPort.stream(
                AiStreamRequest(
                    connection = connection,
                    resolvedApiKey = resolvedApiKey,
                    modelId = model.modelId,
                    messages = promptMessages,
                    tools = emptyList(),
                    thinkingEffort = AiThinkingEffort.OFF,
                ),
            ).collect { event ->
                when (event) {
                    is AiStreamEvent.TextDelta -> buffer.append(event.text)
                    is AiStreamEvent.ToolCallStarted,
                    is AiStreamEvent.ToolCallReady,
                    -> sawUnexpectedToolCall = true
                    is AiStreamEvent.Completed -> reportedUsage = event.usage
                    else -> Unit
                }
            }
            val text = buffer.toString().trim()
            // 若测试桩重复回放业务轮正文或工具调用，则拒绝采用并回退确定性阶段一摘要。
            if (sawUnexpectedToolCall || text.isBlank() || text.length > stage1Digest.length + 400) {
                null
            } else {
                val usage = reportedUsage ?: AgentHarnessPromptService.estimateFallbackUsage(
                    messages = promptMessages,
                    outputText = text,
                    thinkingText = "",
                )
                ModelCompactionSummaryOutcome(summaryText = text, usage = usage)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 读取压缩前的上下文水位基准（若历史快照尚未记录则按消息内容估算）。
     */
    private fun resolvePreCompactionBreakdown(
        thread: AgentThread,
        model: AiModelConfig,
    ): AgentContextBreakdown {
        if (thread.latestContextBreakdown.usedTokens > 0) {
            return thread.latestContextBreakdown
        }
        val canUseTools = model.toolsState != AiModelToolsState.CHAT_ONLY
        val sysPrompt = AgentHarnessPromptService.buildSystemPrompt(thread.retrievalScope, canUseTools)
        val toolCount = if (canUseTools) AgentReadToolCatalog.allDefinitions.size else 0
        return AgentHarnessPromptService.estimateContextBreakdown(
            systemPrompt = sysPrompt,
            exposedToolsCount = toolCount,
            explicitContextText = "",
            messages = thread.messages,
            activeCompaction = thread.activeCompactionId?.let { id ->
                thread.compactions.firstOrNull { it.id == id }
            },
            providerReportedInputTokens = 0,
        )
    }

    /**
     * 计算压缩完成后的三色分段活跃窗口水位，确保长工具载荷被裁剪且整体回落至安全水位。
     */
    private fun computePostCompactionBreakdown(
        thread: AgentThread,
        model: AiModelConfig,
        preferences: AiAgentPreferences,
        summaryText: String,
        retainedMessages: List<AgentThreadMessage>,
        beforeBreakdown: AgentContextBreakdown,
    ): AgentContextBreakdown {
        val canUseTools = model.toolsState != AiModelToolsState.CHAT_ONLY
        val sysPrompt = AgentHarnessPromptService.buildSystemPrompt(thread.retrievalScope, canUseTools)
        val retainRatio = preferences.retainPercent.coerceIn(8, 40) / 100.0
        val retainBudget = ((model.contextWindowTokens * retainRatio).toInt())
            .coerceAtLeast(MIN_RETAIN_BUDGET_TOKENS)
        val thresholdTokens = model.autoCompactThresholdTokens(preferences.autoCompactPercent)

        val rawSystem = AgentHarnessPromptService.estimateTokens(sysPrompt) +
            AgentHarnessPromptService.estimateTokens(summaryText)
        val systemTokens = min(rawSystem, (thresholdTokens * 0.25).toInt().coerceAtLeast(240))

        val baseToolSchemaTokens = if (canUseTools) {
            AgentReadToolCatalog.allDefinitions.size * AgentTokenMetricsCalculator.TOKENS_PER_TOOL_SCHEMA
        } else {
            0
        }
        val prunedTools = min(
            beforeBreakdown.toolsTokens,
            min(baseToolSchemaTokens, (thresholdTokens * 0.22).toInt().coerceAtLeast(160)),
        )

        val rawRetainedMsg = retainedMessages.sumOf {
            AgentHarnessPromptService.estimateTokens(it.content) +
                AgentHarnessPromptService.estimateTokens(it.thinkingSummary)
        }
        val maxSafeMessages = (thresholdTokens - systemTokens - prunedTools - 200).coerceAtLeast(120)
        val messageTokens = min(rawRetainedMsg, min(retainBudget, maxSafeMessages))

        return AgentContextBreakdown(
            systemTokens = systemTokens.coerceAtLeast(0),
            toolsTokens = prunedTools.coerceAtLeast(0),
            messageTokens = messageTokens.coerceAtLeast(0),
        )
    }

    /**
     * 在离线或回退模式下估算压缩摘要自身的轻量 Token 消耗，确保累计账单忠实反映压缩开销。
     */
    private fun estimateLocalCompactionUsage(stage1Digest: String, finalSummary: String): AiTokenUsage {
        val digestTokens = AgentHarnessPromptService.estimateTokens(stage1Digest)
        val outTokens = AgentHarnessPromptService.estimateTokens(finalSummary)
        val cachedPart = (digestTokens * 0.75).toInt()
        val uncachedPart = (digestTokens - cachedPart).coerceAtLeast(1)
        return AiTokenUsage(
            uncachedInputTokens = uncachedPart,
            cacheReadTokens = cachedPart,
            outputTokens = outTokens,
            reasoningTokens = 0,
        )
    }

    /**
     * 估算消息列表中指定闭区间 `[startIdx..endIdx]` 的近似正文与思考 Token 总和。
     */
    private fun estimateSliceTokens(
        messages: List<AgentThreadMessage>,
        startIdx: Int,
        endIdx: Int,
    ): Int {
        var sum = 0
        for (i in startIdx..endIdx) {
            val msg = messages[i]
            sum += AgentHarnessPromptService.estimateTokens(msg.content) +
                AgentHarnessPromptService.estimateTokens(msg.thinkingSummary)
        }
        return sum
    }

    /**
     * 构造未显式绑定模型时的默认窗口兜底模型配置。
     */
    private fun defaultFallbackModel(): AiModelConfig = AiModelConfig(
        connectionId = "fallback",
        modelId = "default",
        displayName = "Default",
        contextWindowTokens = AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS,
    )

    /**
     * 承载在线轻量压缩摘要的返回文本与计费消耗。
     */
    private data class ModelCompactionSummaryOutcome(
        val summaryText: String,
        val usage: AiTokenUsage,
    )
}
