package com.oruke.tagloom.core.ai

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 描述作曲框下方左侧 `StatsPills` 触发器及弹出账单浮层所需的会话累计 Token 统计视图。
 */
data class AgentTokenUsageMetrics(
    /** 未命中前缀缓存的输入 Token 累计值。 */
    val uncachedInputTokens: Int,
    /** 命中前缀缓存读取的输入 Token 累计值。 */
    val cacheReadTokens: Int,
    /** 模型输出 Token 累计值（含深度思考推理部分）。 */
    val outputTokens: Int,
    /** 模型输出中包含的深度思考推理 Token 累计值。 */
    val reasoningTokens: Int,
    /** 计费输入总 Token（`uncachedInputTokens + cacheReadTokens`）。 */
    val totalInputTokens: Int,
    /** 会话全生命周期累计计费总 Token（自动或手动压缩绝不清零）。 */
    val totalTokens: Int,
    /** 前缀缓存命中率百分比（`0..100`）；尚无输入计费记录时为 `null`。 */
    val cacheHitPercent: Int?,
)

/**
 * 描述作曲框下方右侧 `14.dp` `ContextMeter` 环形进度表与三色分段明细浮层所需的实时上下文水位。
 */
data class AgentContextMeterMetrics(
    /** 当前所选模型配置的上下文窗口上限 Token 数。 */
    val contextWindowTokens: Int,
    /** 自动压缩触发阈值百分比（`50..95`，默认 `80`）。 */
    val thresholdPercent: Int,
    /** 自动压缩触发阈值对应的精确 Token 水位线。 */
    val thresholdTokens: Int,
    /** 当前窗口中系统提示词（含活跃压缩摘要）占用的 Token 数。 */
    val systemTokens: Int,
    /** 当前窗口中工具 Schema 与显式 `@` 引用上下文占用的 Token 数。 */
    val toolsTokens: Int,
    /** 当前窗口中未折叠近期消息与作曲框输入草稿占用的 Token 数。 */
    val messageTokens: Int,
    /** 当前作曲框未发送输入草稿实时叠加的启发式 Token 数。 */
    val draftTokens: Int,
    /** 当前作曲框未发送新增 `@` 引用实时叠加的启发式 Token 数。 */
    val addedRefTokens: Int,
    /** 当前活跃上下文窗口总占用 Token 数。 */
    val usedTokens: Int,
    /** 相对模型窗口的未取整占用百分比。 */
    val rawPercent: Double,
    /** 面向界面展示的 `0..100` 整数占用百分比。 */
    val percent: Int,
    /** 是否处于接近自动压缩线的琥珀色预警区间（阈值前 `12%` 内）。 */
    val nearThreshold: Boolean,
    /** 是否已达到或超过自动压缩阈值水位线（红色告警态）。 */
    val overThreshold: Boolean,
)

/**
 * 提供混合口径上下文窗口水位计算、会话累计计费统计及紧凑/精确 Token 数字格式化能力。
 */
object AgentTokenMetricsCalculator {
    /** 最小合法上下文窗口下限保护值。 */
    private const val MIN_CONTEXT_WINDOW_TOKENS: Int = 4_000

    /** 单条尚未解析正文的 `@` 引用默认启发式 Token 预估值。 */
    private const val FALLBACK_TOKENS_PER_REF: Int = 180

    /** 单个暴露工具 Schema 的近似基准 Token 开销。 */
    internal const val TOKENS_PER_TOOL_SCHEMA: Int = 95

    /**
     * 将 Token 整数格式化为紧凑单位文本（如 `0`、`850`、`33.9K`、`128K`、`1M`）。
     *
     * @param tokens 非负或任意整数 Token 数。
     * @return 紧凑可读字符串。
     */
    fun formatCompactTokens(tokens: Int): String {
        val safe = tokens.coerceAtLeast(0)
        return when {
            safe >= 1_000_000 -> formatScaledDecimal(safe, 1_000_000, "M")
            safe >= 1_000 -> formatScaledDecimal(safe, 1_000, "K")
            else -> safe.toString()
        }
    }

    /**
     * 将 Token 整数格式化为带千分位逗号的精确账单字符串（纯跨平台实现，不依赖平台 Locale）。
     *
     * @param tokens 待格式化的 Token 数量。
     * @return 千分位分组数字字符串（如 `48,210`）。
     */
    fun formatExactTokens(tokens: Int): String {
        val negative = tokens < 0
        val digits = abs(tokens.toLong()).toString()
        if (digits.length <= 3) {
            return if (negative) "-$digits" else digits
        }
        val sb = StringBuilder(digits.length + digits.length / 3)
        val firstGroupLen = digits.length % 3
        var index = 0
        if (firstGroupLen > 0) {
            sb.append(digits, 0, firstGroupLen)
            index = firstGroupLen
        }
        while (index < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, index, index + 3)
            index += 3
        }
        return if (negative) "-$sb" else sb.toString()
    }

    /**
     * 基于会话累计 `AiTokenUsage` 计算左侧 `StatsPills` 触发器与累计账单浮层指标。
     *
     * @param usage 会话累计计费 Token 数据。
     * @return 结构化 [AgentTokenUsageMetrics]。
     */
    fun computeUsageMetrics(usage: AiTokenUsage): AgentTokenUsageMetrics {
        val uncached = usage.uncachedInputTokens.coerceAtLeast(0)
        val cacheRead = usage.cacheReadTokens.coerceAtLeast(0)
        val output = usage.outputTokens.coerceAtLeast(0)
        val reasoning = usage.reasoningTokens.coerceAtLeast(0)
        val totalInput = uncached + cacheRead
        val total = totalInput + output
        val hitPct = if (totalInput > 0) {
            ((cacheRead.toDouble() / totalInput.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        return AgentTokenUsageMetrics(
            uncachedInputTokens = uncached,
            cacheReadTokens = cacheRead,
            outputTokens = output,
            reasoningTokens = reasoning,
            totalInputTokens = totalInput,
            totalTokens = total,
            cacheHitPercent = hitPct,
        )
    }

    /**
     * 计算对标 DeepSeek Harness `dsh-token-meter` 的混合口径实时上下文水位。
     *
     * 计算分为两阶段：
     * 1. 已完成回合以 `thread.latestContextBreakdown`（经 Provider 真实输入校准）或初始系统/工具基线为准；
     * 2. 编辑作曲框草稿或增删 `@` 引用时，实时叠加启发式 Token 增量；切换不同 `contextWindowTokens` 的模型即时重算分母与 `80%` 线。
     *
     * @param thread 当前选中的会话载荷；尚未创建会话时为空。
     * @param model 当前选中的模型配置；未配置模型时回退默认 `128K` 窗口。
     * @param preferences 工作区自动压缩与运行偏好。
     * @param retrievalScope 当前作曲框选中的检索暴露范围。
     * @param composerDraft 当前作曲框未发送输入草稿文本。
     * @param activeContextRefs 当前作曲框挂载的活跃 `@` 引用列表。
     * @param resolvedRefText 当前活跃 `@` 引用在编辑器中解析出的近似纯文本（可选，用于提高引用预估精度）。
     * @return 结构化 [AgentContextMeterMetrics]。
     */
    fun computeContextMeterMetrics(
        thread: AgentThread?,
        model: AiModelConfig?,
        preferences: AiAgentPreferences,
        retrievalScope: AiRetrievalScope = thread?.retrievalScope ?: AiRetrievalScope.CITED_ONLY,
        composerDraft: String = "",
        activeContextRefs: List<AgentContextRef> = thread?.activeContextRefs.orEmpty(),
        resolvedRefText: String = "",
    ): AgentContextMeterMetrics {
        val contextWindow = (model?.contextWindowTokens ?: AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS)
            .coerceAtLeast(MIN_CONTEXT_WINDOW_TOKENS)
        val thresholdPercent = preferences.autoCompactPercent.coerceIn(50, 95)
        val thresholdTokens = ((contextWindow.toLong() * thresholdPercent) / 100L).toInt()

        val canUseTools = model?.toolsState != AiModelToolsState.CHAT_ONLY
        val effectiveScope = if (canUseTools) retrievalScope else AiRetrievalScope.CITED_ONLY
        val hasPersistedBaseline = thread != null &&
            (thread.messages.isNotEmpty() || thread.latestContextBreakdown.usedTokens > 0)
        val baselineBreakdown = if (hasPersistedBaseline) {
            thread.latestContextBreakdown
        } else {
            val sysPrompt = AgentHarnessPromptService.buildSystemPrompt(effectiveScope, canUseTools)
            val toolCount = if (canUseTools) AgentReadToolCatalog.allDefinitions.size else 0
            AgentContextBreakdown(
                systemTokens = AgentHarnessPromptService.estimateTokens(sysPrompt),
                toolsTokens = toolCount * TOKENS_PER_TOOL_SCHEMA,
                messageTokens = 0,
            )
        }

        val persistedRefIds = if (hasPersistedBaseline) {
            thread.activeContextRefs.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        val newlyAddedRefs = activeContextRefs.filter { it.id !in persistedRefIds }
        val removedRefCount = if (hasPersistedBaseline) {
            persistedRefIds.count { id -> activeContextRefs.none { it.id == id } }
        } else {
            0
        }
        val addedRefTokens = when {
            newlyAddedRefs.isEmpty() -> 0
            resolvedRefText.isNotBlank() -> AgentHarnessPromptService.estimateTokens(resolvedRefText)
            else -> newlyAddedRefs.size * FALLBACK_TOKENS_PER_REF
        }
        val draftTokens = if (composerDraft.isBlank()) {
            0
        } else {
            AgentHarnessPromptService.estimateTokens(composerDraft)
        }

        val systemTokens = baselineBreakdown.systemTokens.coerceAtLeast(0)
        val toolsTokens = (baselineBreakdown.toolsTokens + addedRefTokens - removedRefCount * FALLBACK_TOKENS_PER_REF)
            .coerceAtLeast(0)
        val messageTokens = (baselineBreakdown.messageTokens + draftTokens).coerceAtLeast(0)
        val usedTokens = systemTokens + toolsTokens + messageTokens

        val rawPercent = (usedTokens.toDouble() / contextWindow.toDouble()) * 100.0
        val percent = rawPercent.roundToInt().coerceIn(0, 100)
        val nearThreshold = rawPercent >= (thresholdPercent - 12).toDouble() && usedTokens < thresholdTokens
        val overThreshold = usedTokens >= thresholdTokens

        return AgentContextMeterMetrics(
            contextWindowTokens = contextWindow,
            thresholdPercent = thresholdPercent,
            thresholdTokens = thresholdTokens,
            systemTokens = systemTokens,
            toolsTokens = toolsTokens,
            messageTokens = messageTokens,
            draftTokens = draftTokens,
            addedRefTokens = addedRefTokens,
            usedTokens = usedTokens,
            rawPercent = rawPercent,
            percent = percent,
            nearThreshold = nearThreshold,
            overThreshold = overThreshold,
        )
    }

    /**
     * 将整数按 `1_000` 或 `1_000_000` 缩放为最多一位小数的紧凑字符串（整除时省略 `.0`）。
     */
    private fun formatScaledDecimal(value: Int, unit: Int, suffix: String): String {
        val tenths = ((value.toLong() * 10L) + unit / 2) / unit
        val whole = tenths / 10L
        val frac = tenths % 10L
        return if (frac == 0L || whole >= 100L) {
            "$whole$suffix"
        } else {
            "$whole.$frac$suffix"
        }
    }
}
