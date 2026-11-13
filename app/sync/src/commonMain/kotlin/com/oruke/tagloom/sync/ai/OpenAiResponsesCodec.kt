package com.oruke.tagloom.sync.ai

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 负责 `OpenAI Responses API`（`/responses`）协议的请求编码与语义化 SSE 事件流解析。
 */
internal object OpenAiResponsesCodec {
    /**
     * 将标准化 [AiStreamRequest] 编码为 `/responses` 流式 JSON 请求体。
     *
     * @param request 标准化流请求。
     * @return 紧凑 JSON 请求字符串。
     */
    fun encodeRequest(request: AiStreamRequest): String {
        val systemInstructions = request.messages
            .filter { it.role == AiChatRole.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }
        val payload = buildJsonObject {
            put("model", request.modelId)
            put("stream", true)
            if (systemInstructions.isNotBlank()) {
                put("instructions", systemInstructions)
            }
            put("input", buildJsonArray {
                request.messages.filter { it.role != AiChatRole.SYSTEM }.forEach { message ->
                    when (message.role) {
                        AiChatRole.USER -> add(buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put("content", message.content)
                        })

                        AiChatRole.ASSISTANT -> {
                            if (message.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "message")
                                    put("role", "assistant")
                                    put("content", message.content)
                                })
                            }
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "function_call")
                                    put("call_id", call.callId)
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson)
                                })
                            }
                        }

                        AiChatRole.TOOL -> {
                            val callId = message.toolCallId
                            if (!callId.isNullOrBlank()) {
                                add(buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", callId)
                                    put("output", message.content)
                                })
                            }
                        }

                        AiChatRole.SYSTEM -> Unit
                    }
                }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parametersSchema)
                        })
                    }
                })
            }
            if (request.thinkingEffort != AiThinkingEffort.OFF) {
                put("reasoning", buildJsonObject {
                    put("effort", request.thinkingEffort.name.lowercase())
                    put("summary", "auto")
                })
            }
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("max_output_tokens", it) }
        }
        return CanonicalJson.format.encodeToString(JsonElement.serializer(), payload)
    }
}

/**
 * 维护单次 `OpenAI Responses API` SSE 事件流的语义状态机与精确 Token 计量提取。
 */
internal class OpenAiResponsesStreamAccumulator {
    /** 按 `output_index` 追踪的函数调用槽位。 */
    private val slotsByIndex = linkedMapOf<Int, MutableResponsesToolSlot>()

    /** `item.id` 到 `output_index` 的映射，用于关联 `response.function_call_arguments.delta`。 */
    private val indexByItemId = mutableMapOf<String, Int>()

    /** 已发出 `ToolCallReady` 的 `callId` 集合，防止重复推送。 */
    private val emittedCallIds = mutableSetOf<String>()

    /** 是否已收到服务端 `response.completed` / `response.incomplete` 终态帧。 */
    private var completedEmitted: Boolean = false

    /** 最近解析到的 Token 账单。 */
    private var latestUsage: AiTokenUsage? = null

    /**
     * 处理一帧 SSE 事件并返回转换后的标准化 [AiStreamEvent] 列表。
     *
     * @param frame SSE 事件帧。
     * @return 当前帧产生的增量事件列表。
     */
    fun onFrame(frame: SseEventFrame): List<AiStreamEvent> {
        val root = try {
            CanonicalJson.format.parseToJsonElement(frame.data).jsonObject
        } catch (_: Throwable) {
            throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "responses_frame_invalid_json")
        }
        val eventType = frame.event?.takeIf { it.isNotBlank() }
            ?: (root["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val events = mutableListOf<AiStreamEvent>()
        when (eventType) {
            "response.output_text.delta" -> {
                val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull
                if (!delta.isNullOrEmpty()) {
                    events += AiStreamEvent.TextDelta(delta)
                }
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull
                if (!delta.isNullOrEmpty()) {
                    events += AiStreamEvent.ThinkingDelta(delta)
                }
            }

            "response.output_item.added" -> {
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                val item = root["item"] as? JsonObject
                if ((item?.get("type") as? JsonPrimitive)?.contentOrNull == "function_call") {
                    val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                    val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: itemId ?: "call_$outputIndex"
                    val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val slot = MutableResponsesToolSlot(outputIndex = outputIndex, callId = callId, name = name)
                    slotsByIndex[outputIndex] = slot
                    if (!itemId.isNullOrBlank()) {
                        indexByItemId[itemId] = outputIndex
                    }
                    if (name.isNotBlank()) {
                        events += AiStreamEvent.ToolCallStarted(
                            index = outputIndex,
                            callId = callId,
                            name = name,
                        )
                    }
                }
            }

            "response.function_call_arguments.delta" -> {
                val itemId = (root["item_id"] as? JsonPrimitive)?.contentOrNull
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.intOrNull
                    ?: itemId?.let(indexByItemId::get)
                    ?: 0
                val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull
                if (!delta.isNullOrEmpty()) {
                    val slot = slotsByIndex.getOrPut(outputIndex) {
                        MutableResponsesToolSlot(
                            outputIndex = outputIndex,
                            callId = itemId ?: "call_$outputIndex",
                            name = "",
                        )
                    }
                    slot.arguments.append(delta)
                    events += AiStreamEvent.ToolCallArgumentsDelta(
                        index = outputIndex,
                        callId = slot.callId,
                        argumentsDelta = delta,
                    )
                }
            }

            "response.output_item.done" -> {
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                val item = root["item"] as? JsonObject
                if ((item?.get("type") as? JsonPrimitive)?.contentOrNull == "function_call") {
                    val slot = slotsByIndex[outputIndex]
                    val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
                        ?: slot?.callId
                        ?: "call_$outputIndex"
                    val name = (item["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: slot?.name.orEmpty()
                    val arguments = (item["arguments"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: slot?.arguments?.toString().orEmpty().ifBlank { "{}" }
                    if (name.isNotBlank() && emittedCallIds.add(callId)) {
                        events += AiStreamEvent.ToolCallReady(
                            AiToolCall(callId = callId, name = name, argumentsJson = arguments),
                        )
                    }
                }
            }

            "response.completed", "response.incomplete" -> {
                val responseObj = root["response"] as? JsonObject
                (responseObj?.get("usage") as? JsonObject)?.let { usageObj ->
                    latestUsage = parseResponsesUsage(usageObj)
                }
                (responseObj?.get("output") as? JsonArray)?.forEachIndexed { idx, elem ->
                    val item = elem as? JsonObject ?: return@forEachIndexed
                    if ((item["type"] as? JsonPrimitive)?.contentOrNull == "function_call") {
                        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: "call_$idx"
                        val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val arguments = (item["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "{}" }
                        if (name.isNotBlank() && emittedCallIds.add(callId)) {
                            events += AiStreamEvent.ToolCallReady(
                                AiToolCall(callId = callId, name = name, argumentsJson = arguments),
                            )
                        }
                    }
                }
                flushRemainingSlots(events)
                val reason = when {
                    eventType == "response.incomplete" -> AiFinishReason.LENGTH
                    emittedCallIds.isNotEmpty() -> AiFinishReason.TOOL_CALLS
                    else -> AiFinishReason.STOP
                }
                completedEmitted = true
                events += AiStreamEvent.Completed(usage = latestUsage, finishReason = reason)
            }

            "response.failed", "error" -> {
                throw AiProviderException(AiErrorKind.SERVER_ERROR, diagnosticCode = "responses_stream_failed")
            }
        }
        return events
    }

    /**
     * 当底层连接未发送显式 `response.completed` 即自然关闭时补发收尾事件。
     *
     * @return 若尚未发出终态事件则返回补齐列表，否则返回空列表。
     */
    fun finishIfNeeded(): List<AiStreamEvent> {
        if (completedEmitted) return emptyList()
        val events = mutableListOf<AiStreamEvent>()
        flushRemainingSlots(events)
        val reason = if (emittedCallIds.isNotEmpty()) AiFinishReason.TOOL_CALLS else AiFinishReason.STOP
        completedEmitted = true
        events += AiStreamEvent.Completed(usage = latestUsage, finishReason = reason)
        return events
    }

    /**
     * 将尚未发出 `ToolCallReady` 的缓冲工具调用补齐推送。
     *
     * @param events 输出事件列表。
     */
    private fun flushRemainingSlots(events: MutableList<AiStreamEvent>) {
        slotsByIndex.values.forEach { slot ->
            if (slot.name.isNotBlank() && emittedCallIds.add(slot.callId)) {
                val args = slot.arguments.toString().ifBlank { "{}" }
                events += AiStreamEvent.ToolCallReady(
                    AiToolCall(callId = slot.callId, name = slot.name, argumentsJson = args),
                )
            }
        }
    }

    /**
     * 从 `response.usage` 解析精确的未缓存输入、缓存读取输入、总输出与推理 Token。
     *
     * @param usageObj `response.usage` JSON 对象。
     * @return 标准化的 [AiTokenUsage]。
     */
    private fun parseResponsesUsage(usageObj: JsonObject): AiTokenUsage {
        val inputTokens = ((usageObj["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val inputDetails = usageObj["input_tokens_details"] as? JsonObject
        val cachedTokens = ((inputDetails?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val uncachedInput = (inputTokens - cachedTokens).coerceAtLeast(0)
        val outputTokens = ((usageObj["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val outputDetails = usageObj["output_tokens_details"] as? JsonObject
        val reasoningTokens = ((outputDetails?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceAtLeast(0)
        return AiTokenUsage(
            uncachedInputTokens = uncachedInput,
            cacheReadTokens = cachedTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
        )
    }
}

/**
 * Responses 协议工具调用可变拼接槽位。
 */
private class MutableResponsesToolSlot(
    /** 输出项序号。 */
    val outputIndex: Int,
    /** 工具调用关联 ID。 */
    val callId: String,
    /** 工具函数名称。 */
    val name: String,
) {
    /** 增量参数 JSON 字符串缓冲。 */
    val arguments: StringBuilder = StringBuilder()
}
