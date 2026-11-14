package com.oruke.tagloom.core.ai

/**
 * 为内置 [AgentHarness] 提供无状态的系统提示词构造、上下文消息序列装配、启发式 Token 计量与首轮标题派生能力。
 */
internal object AgentHarnessPromptService {
    /**
     * 构造遵循 Tagloom 隐私边界、ADR-008 术语与 `SourceLedger` 角标纪律的系统指令。
     *
     * @param scope 当前会话激活的检索暴露范围。
     * @param canUseTools 当前模型与范围组合是否开放只读知识工具。
     * @return 系统提示词全文。
     */
    fun buildSystemPrompt(
        scope: AiRetrievalScope,
        canUseTools: Boolean,
    ): String = buildString {
        appendLine("你是 Tagloom 端到端加密知识工作区内置的研究与写作助手。")
        appendLine("规则：")
        appendLine("1. 严格基于用户显式提供的上下文或工具真实读取到的笔记内容作答，不臆造未读取的笔记细节。")
        appendLine("2. 引用工作区笔记事实时，仅使用上下文或工具返回中明确登记的半角方括号编号（如 [1]、[2]）；严禁编造未登记的编号。")
        appendLine("3. 界面术语遵循 ADR-008：称呼分类体系为“笔记类型”（不带 # 前缀），# 仅用于普通标签属性。")
        if (canUseTools) {
            val scopeNote = if (scope == AiRetrievalScope.ALL_NOTES) {
                "当前已授权在工作区允许访问（ALLOW）的全部笔记内按需调用只读检索与读取工具。"
            } else {
                "当前处于受限范围模式，仅可在已挂载的笔记类型或保存查询范围内调用只读工具。"
            }
            appendLine("4. $scopeNote")
        } else {
            appendLine("4. 当前会话仅分析本轮显式挂载的引用片段或进行通用问答。")
        }
    }

    /**
     * 组装发送给 Provider 的历史与本轮消息列表（自动尊重 `activeCompactionId` 压缩游标）。
     *
     * @param thread 当前会话载荷。
     * @param systemPrompt 系统提示词。
     * @param userPrompt 本轮用户提问。
     * @param explicitContextBlock 解析后的显式 `@` 引用结构化文本。
     * @param initialSources 显式引用已分配的来源台账列表。
     * @return 发送给流式端口的标准化消息序列。
     */
    fun buildInitialMessages(
        thread: AgentThread,
        systemPrompt: String,
        userPrompt: String,
        explicitContextBlock: String,
        initialSources: List<AgentSourceEntry>,
    ): List<AiChatMessage> {
        val list = mutableListOf<AiChatMessage>()
        list += AiChatMessage(role = AiChatRole.SYSTEM, content = systemPrompt)

        val activeCompaction = thread.activeCompactionId?.let { id ->
            thread.compactions.firstOrNull { it.id == id }
        }
        val historyMessages = if (activeCompaction != null) {
            val idx = thread.messages.indexOfFirst { it.id == activeCompaction.upToMessageId }
            if (idx >= 0) {
                list += AiChatMessage(
                    role = AiChatRole.SYSTEM,
                    content = "【早期对话已自动压缩脉络摘要】\n${activeCompaction.summary}",
                )
                thread.messages.drop(idx + 1)
            } else {
                thread.messages
            }
        } else {
            thread.messages
        }

        historyMessages.forEach { msg ->
            if (msg.content.isNotBlank()) {
                list += AiChatMessage(role = msg.role, content = msg.content)
            }
        }

        val combinedUserContent = buildString {
            if (explicitContextBlock.isNotBlank()) {
                appendLine("【显式挂载的工作区上下文】")
                appendLine(explicitContextBlock.trim())
                if (initialSources.isNotEmpty()) {
                    val mapping = initialSources.joinToString("；") { entry ->
                        val blockSuffix = entry.blockId?.value?.let { " · $it" }.orEmpty()
                        "[${entry.sourceIndex}] 《${entry.noteTitle}》$blockSuffix"
                    }
                    appendLine("已登记来源索引：$mapping")
                }
                appendLine()
            }
            append(userPrompt)
        }
        list += AiChatMessage(role = AiChatRole.USER, content = combinedUserContent)
        return list
    }

    /**
     * 启发式估算近似 Token 数（兼容未回传 `usage` 的本机 Ollama/LM Studio 端点）。
     *
     * @param text 待估算文本。
     * @return 近似 Token 计数。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjkCount = 0
        var otherCount = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x30FF || ch.code in 0xFF00..0xFFEF) {
                cjkCount += 1
            } else {
                otherCount += 1
            }
        }
        return (cjkCount * 1.5 + otherCount * 0.3).toInt().coerceAtLeast(1)
    }

    /**
     * 当本机模型未返回 `usage` 时，基于输入输出字符生成兜底估算账单。
     *
     * @param messages 本轮发送的上下文消息。
     * @param outputText 模型输出文本。
     * @param thinkingText 模型思考文本。
     * @return 估算的 [AiTokenUsage]。
     */
    fun estimateFallbackUsage(
        messages: List<AiChatMessage>,
        outputText: String,
        thinkingText: String,
    ): AiTokenUsage {
        val inputEst = messages.sumOf { estimateTokens(it.content) }
        val reasoningEst = estimateTokens(thinkingText)
        val outputEst = estimateTokens(outputText) + reasoningEst
        return AiTokenUsage(
            uncachedInputTokens = inputEst,
            cacheReadTokens = 0,
            outputTokens = outputEst,
            reasoningTokens = reasoningEst,
        )
    }

    /**
     * 计算三色分段上下文水位基准（优先按 Provider 真实输入 Token 等比校准）。
     *
     * @param systemPrompt 系统提示词。
     * @param exposedToolsCount 暴露工具数量。
     * @param explicitContextText 显式注入上下文文本。
     * @param messages 更新后的完整消息列表。
     * @param activeCompaction 当前活跃压缩节点。
     * @param providerReportedInputTokens Provider 返回的真实输入 Token（未返回时为 `0`）。
     * @return 三色分段占用明细 [AgentContextBreakdown]。
     */
    fun estimateContextBreakdown(
        systemPrompt: String,
        exposedToolsCount: Int,
        explicitContextText: String,
        messages: List<AgentThreadMessage>,
        activeCompaction: AgentCompactionItem?,
        providerReportedInputTokens: Int,
    ): AgentContextBreakdown {
        val rawSys = estimateTokens(systemPrompt) + (activeCompaction?.summary?.let(::estimateTokens) ?: 0)
        val rawTools = exposedToolsCount * 95 + estimateTokens(explicitContextText)
        val retainedMessages = if (activeCompaction != null) {
            val idx = messages.indexOfFirst { it.id == activeCompaction.upToMessageId }
            if (idx >= 0) messages.drop(idx + 1) else messages
        } else {
            messages
        }
        val rawMsg = retainedMessages.sumOf { estimateTokens(it.content) + estimateTokens(it.thinkingSummary) }
        val rawTotal = (rawSys + rawTools + rawMsg).coerceAtLeast(1)
        if (providerReportedInputTokens <= 0) {
            return AgentContextBreakdown(systemTokens = rawSys, toolsTokens = rawTools, messageTokens = rawMsg)
        }
        val lastTurnOutput = retainedMessages.lastOrNull()?.let { estimateTokens(it.content) } ?: 0
        val total = providerReportedInputTokens + lastTurnOutput
        val sysScaled = ((total.toLong() * rawSys) / rawTotal).toInt()
        val toolsScaled = ((total.toLong() * rawTools) / rawTotal).toInt()
        val msgScaled = (total - sysScaled - toolsScaled).coerceAtLeast(0)
        return AgentContextBreakdown(systemTokens = sysScaled, toolsTokens = toolsScaled, messageTokens = msgScaled)
    }

    /**
     * 判断当前会话标题是否仍为初始默认占位标题。
     *
     * @param title 当前会话标题。
     * @return 仍为默认占位标题时为真。
     */
    fun isDefaultPlaceholderTitle(title: String): Boolean {
        val trimmed = title.trim()
        return trimmed.isEmpty() || trimmed == "新会话" || trimmed == "New chat" || trimmed == "无痕会话" || trimmed == "Incognito chat"
    }

    /**
     * 从首轮用户提问文本提取紧凑会话标题摘要。
     *
     * @param prompt 用户首轮输入文本。
     * @return 最长不超过 29 字符的单行标题。
     */
    fun deriveInitialTitle(prompt: String): String {
        val singleLine = prompt.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
        if (singleLine.isEmpty()) return "新会话"
        return if (singleLine.length <= 28) singleLine else singleLine.take(28) + "…"
    }
}
