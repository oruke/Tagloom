package com.oruke.tagloom.sync.ai

import com.oruke.tagloom.core.ai.AiChatMessage
import com.oruke.tagloom.core.ai.AiChatRole
import com.oruke.tagloom.core.ai.AiErrorKind
import com.oruke.tagloom.core.ai.AiFinishReason
import com.oruke.tagloom.core.ai.AiProviderException
import com.oruke.tagloom.core.ai.AiStreamEvent
import com.oruke.tagloom.core.ai.AiStreamRequest
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.ai.AiTokenUsage
import com.oruke.tagloom.core.ai.AiToolCall
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 负责 `OpenAI Chat Completions`（`/chat/completions`）协议的请求编码与流式增量事件组装。
 */
internal object OpenAiChatCompletionsCodec {
    /**
     * 将标准化 [AiStreamRequest] 编码为 `/chat/completions` 流式 JSON 请求体。
     *
     * @param request 标准化流请求。
     * @return 紧凑 JSON 字符串。
     */
    fun encodeRequest(request: AiStreamRequest): String {
        val payload = buildJsonObject {
            put("model", request.modelId)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("messages", buildJsonArray {
                request.messages.forEach { message -> add(encodeMessage(message)) }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parametersSchema)
                            })
                        })
                    }
                })
            }
            if (request.thinkingEffort != AiThinkingEffort.OFF) {
                put("reasoning_effort", request.thinkingEffort.name.lowercase())
            }
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("max_completion_tokens", it) }
        }
        return CanonicalJson.format.encodeToString(JsonElement.serializer(), payload)
    }

    /**
     * 编码单条 Chat Completions 消息对象。
     *
     * @param message 领域层消息。
     * @return 符合 `/chat/completions` 格式的 `JsonObject`。
     */
    private fun encodeMessage(message: AiChatMessage): JsonObject = buildJsonObject {
        val role = when (message.role) {
            AiChatRole.SYSTEM -> "system"
            AiChatRole.USER -> "user"
            AiChatRole.ASSISTANT -> "assistant"
            AiChatRole.TOOL -> "tool"
        }
        put("role", role)
        put("content", message.content)
        if (message.role == AiChatRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.callId)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        })
                    })
                }
            })
        }
        if (message.role == AiChatRole.TOOL && message.toolCallId != null) {
            put("tool_call_id", message.toolCallId)
        }
    }
}

/**
 * 维护单次 `OpenAI Chat Completions` SSE 流的增量工具参数拼接、推理摘要提取与精确 Token 统计。
 */
internal class OpenAiChatStreamAccumulator {
    /** 按槽位索引维护的增量工具调用构建器。 */
    private val toolSlots = linkedMapOf<Int, MutableChatToolSlot>()

    /** 已发出的完整工具调用索引集合，避免在流结束时重复推送。 */
    private val emittedReadySlots = mutableSetOf<Int>()

    /** 最近观测到的结束原因。 */
    private var finishReason: AiFinishReason = AiFinishReason.UNKNOWN

    /** 最近观测到的精确 Token 账单。 */
    private var latestUsage: AiTokenUsage? = null

    /**
     * 处理单帧 SSE JSON 数据并返回本帧产生的增量事件列表。
     *
     * @param data 原始 JSON 帧文本。
     * @return 按顺序产出的事件列表。
     */
    fun onDataFrame(data: String): List<AiStreamEvent> {
        val root = try {
            CanonicalJson.format.parseToJsonElement(data).jsonObject
        } catch (_: Throwable) {
            throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "chat_chunk_invalid_json")
        }
        if ("error" in root && root["error"] !is kotlinx.serialization.json.JsonNull) {
            throw AiProviderException(AiErrorKind.SERVER_ERROR, diagnosticCode = "chat_stream_error_frame")
        }

        val events = mutableListOf<AiStreamEvent>()
        (root["usage"] as? JsonObject)?.let { usageObj ->
            latestUsage = parseChatUsage(usageObj)
        }

        val choices = root["choices"] as? JsonArray
        choices?.forEach { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@forEach
            val delta = choice["delta"] as? JsonObject
            if (delta != null) {
                val reasoningText = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                    ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
                if (!reasoningText.isNullOrEmpty()) {
                    events += AiStreamEvent.ThinkingDelta(reasoningText)
                }
                val contentText = (delta["content"] as? JsonPrimitive)?.contentOrNull
                if (!contentText.isNullOrEmpty()) {
                    events += AiStreamEvent.TextDelta(contentText)
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { toolElement ->
                    processToolCallDelta(toolElement as? JsonObject, events)
                }
            }
            val rawFinish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (!rawFinish.isNullOrBlank()) {
                finishReason = mapFinishReason(rawFinish)
                if (finishReason == AiFinishReason.TOOL_CALLS) {
                    flushReadyToolCalls(events)
                }
            }
        }
        return events
    }

    /**
     * 在 SSE 流自然收尾时补齐尚未发出的完整工具调用并发出最终 [AiStreamEvent.Completed] 事件。
     *
     * @return 收尾事件列表。
     */
    fun finish(): List<AiStreamEvent> {
        val events = mutableListOf<AiStreamEvent>()
        flushReadyToolCalls(events)
        val resolvedReason = when {
            finishReason != AiFinishReason.UNKNOWN -> finishReason
            emittedReadySlots.isNotEmpty() -> AiFinishReason.TOOL_CALLS
            else -> AiFinishReason.STOP
        }
        events += AiStreamEvent.Completed(usage = latestUsage, finishReason = resolvedReason)
        return events
    }

    /**
     * 解析单个 `delta.tool_calls[]` 条目并更新槽位缓冲。
     *
     * @param toolDelta 工具增量 JSON。
     * @param events 输出事件收集器。
     */
    private fun processToolCallDelta(
        toolDelta: JsonObject?,
        events: MutableList<AiStreamEvent>,
    ) {
        if (toolDelta == null) return
        val index = (toolDelta["index"] as? JsonPrimitive)?.intOrNull ?: 0
        val slot = toolSlots.getOrPut(index) { MutableChatToolSlot(index = index) }
        (toolDelta["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { slot.callId = it }
        val functionObj = toolDelta["function"] as? JsonObject
        functionObj?.let { fn ->
            (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { slot.name = it }
            if (!slot.startedEmitted && slot.callId != null && slot.name != null) {
                slot.startedEmitted = true
                events += AiStreamEvent.ToolCallStarted(
                    index = slot.index,
                    callId = requireNotNull(slot.callId),
                    name = requireNotNull(slot.name),
                )
            }
            val argsChunk = (fn["arguments"] as? JsonPrimitive)?.contentOrNull
            if (!argsChunk.isNullOrEmpty()) {
                slot.arguments.append(argsChunk)
                events += AiStreamEvent.ToolCallArgumentsDelta(
                    index = slot.index,
                    callId = slot.callId,
                    argumentsDelta = argsChunk,
                )
            }
        }
    }

    /**
     * 将已收集完毕但尚未通知的工具调用统一转换为 [AiStreamEvent.ToolCallReady]。
     *
     * @param events 输出事件收集器。
     */
    private fun flushReadyToolCalls(events: MutableList<AiStreamEvent>) {
        toolSlots.values.forEach { slot ->
            val name = slot.name
            if (slot.index !in emittedReadySlots && !name.isNullOrBlank()) {
                emittedReadySlots += slot.index
                val callId = slot.callId ?: "call_${slot.index}"
                val args = slot.arguments.toString().ifBlank { "{}" }
                events += AiStreamEvent.ToolCallReady(
                    AiToolCall(callId = callId, name = name, argumentsJson = args),
                )
            }
        }
    }

    /**
     * 解析 `/chat/completions` 的 `usage` 字段，同时兼容 OpenAI `prompt_tokens_details.cached_tokens`
     * 与 DeepSeek 原生 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`。
     *
     * @param usageObj `usage` JSON 对象。
     * @return 标准化的精确 Token 明细。
     */
    private fun parseChatUsage(usageObj: JsonObject): AiTokenUsage {
        val promptTokens = (usageObj["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val promptDetails = usageObj["prompt_tokens_details"] as? JsonObject
        val cachedFromDetails = (promptDetails?.get("cached_tokens") as? JsonPrimitive)?.intOrNull
        val cachedFromDeepSeek = (usageObj["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
        val cachedTokens = (cachedFromDetails ?: cachedFromDeepSeek ?: 0).coerceAtLeast(0)
        val uncachedFromDeepSeek = (usageObj["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull
        val uncachedInput = uncachedFromDeepSeek?.coerceAtLeast(0)
            ?: (promptTokens - cachedTokens).coerceAtLeast(0)
        val completionTokens = ((usageObj["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val completionDetails = usageObj["completion_tokens_details"] as? JsonObject
        val reasoningTokens = ((completionDetails?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceAtLeast(0)
        return AiTokenUsage(
            uncachedInputTokens = uncachedInput,
            cacheReadTokens = cachedTokens,
            outputTokens = completionTokens,
            reasoningTokens = reasoningTokens,
        )
    }

    /**
     * 映射供应商 `finish_reason` 字符串到标准化枚举。
     *
     * @param raw 原始结束原因。
     * @return 对应 [AiFinishReason]。
     */
    private fun mapFinishReason(raw: String): AiFinishReason = when (raw.lowercase()) {
        "stop" -> AiFinishReason.STOP
        "tool_calls", "function_call" -> AiFinishReason.TOOL_CALLS
        "length", "max_tokens" -> AiFinishReason.LENGTH
        "content_filter" -> AiFinishReason.CONTENT_FILTER
        else -> AiFinishReason.UNKNOWN
    }
}

/**
 * 内部可变工具调用拼接槽位。
 */
private class MutableChatToolSlot(
    /** 槽位序号。 */
    val index: Int,
) {
    /** 工具调用 ID。 */
    var callId: String? = null

    /** 工具函数名称。 */
    var name: String? = null

    /** 是否已发出 `ToolCallStarted` 事件。 */
    var startedEmitted: Boolean = false

    /** 累积的 JSON 参数文本缓冲。 */
    val arguments: StringBuilder = StringBuilder()
}
