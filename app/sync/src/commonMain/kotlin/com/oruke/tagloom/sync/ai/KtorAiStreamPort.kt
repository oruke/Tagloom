package com.oruke.tagloom.sync.ai

import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiChatMessage
import com.oruke.tagloom.core.ai.AiChatRole
import com.oruke.tagloom.core.ai.AiConnectionPresets
import com.oruke.tagloom.core.ai.AiConnectionTestResult
import com.oruke.tagloom.core.ai.AiErrorKind
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiProviderException
import com.oruke.tagloom.core.ai.AiRemoteModelInfo
import com.oruke.tagloom.core.ai.AiStreamEvent
import com.oruke.tagloom.core.ai.AiStreamPort
import com.oruke.tagloom.core.ai.AiStreamRequest
import com.oruke.tagloom.core.ai.AiTokenUsage
import com.oruke.tagloom.core.ai.AiToolCall
import com.oruke.tagloom.core.ai.AiToolDefinition
import com.oruke.tagloom.core.ai.AiToolProbeResult
import com.oruke.tagloom.core.ai.WorkspaceConfigValidator
import com.oruke.tagloom.core.protocol.CanonicalJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * 基于 Ktor 实现的可取消流式模型推理、连接校验、模型拉取与工具调用实测适配器。
 *
 * 严格按连接配置的 [AiProtocolKind] 路由到 `/chat/completions` 或 `/responses`，
 * 不自动跟随重定向，不在异常中附带密钥、请求头、URL 或响应体，并支持在工作区锁定时一键中止在途流。
 *
 * @param httpClient 已配置关闭重定向与 `HttpTimeout` 插件的 Ktor 客户端。
 */
class KtorAiStreamPort(
    private val httpClient: HttpClient,
) : AiStreamPort {
    /** 追踪当前所有活动请求 Job，供工作区锁定时立即取消。 */
    private val activeRequestJobs = mutableSetOf<Job>()

    /**
     * 发起可取消 SSE 流式推理请求。
     *
     * @param request 完整流式请求。
     * @return 按到达顺序发出的增量事件冷流。
     */
    override fun stream(request: AiStreamRequest): Flow<AiStreamEvent> = flow {
        val baseUrl = validateAndNormalizeBaseUrl(request.connection, request.resolvedApiKey)
        val endpoint = when (request.connection.protocol) {
            AiProtocolKind.OPENAI_CHAT_COMPLETIONS -> "$baseUrl/chat/completions"
            AiProtocolKind.OPENAI_RESPONSES -> "$baseUrl/responses"
        }
        val bodyJson = when (request.connection.protocol) {
            AiProtocolKind.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsCodec.encodeRequest(request)
            AiProtocolKind.OPENAI_RESPONSES -> OpenAiResponsesCodec.encodeRequest(request)
        }

        withTrackedJob {
            try {
                httpClient.preparePost(endpoint) {
                    applyConnectionConfig(request.connection, request.resolvedApiKey, streaming = true)
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(bodyJson)
                }.execute { response ->
                    ensureSuccessStatus(response)
                    when (request.connection.protocol) {
                        AiProtocolKind.OPENAI_CHAT_COMPLETIONS -> {
                            val accumulator = OpenAiChatStreamAccumulator()
                            SseLineReader.readFrames(response.bodyAsChannel()) { frame ->
                                accumulator.onDataFrame(frame.data).forEach { event -> emit(event) }
                            }
                            accumulator.finish().forEach { event -> emit(event) }
                        }

                        AiProtocolKind.OPENAI_RESPONSES -> {
                            val accumulator = OpenAiResponsesStreamAccumulator()
                            SseLineReader.readFrames(response.bodyAsChannel()) { frame ->
                                accumulator.onFrame(frame).forEach { event -> emit(event) }
                            }
                            accumulator.finishIfNeeded().forEach { event -> emit(event) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (providerError: AiProviderException) {
                throw providerError
            } catch (timeout: HttpRequestTimeoutException) {
                throw AiProviderException(AiErrorKind.TIMEOUT, diagnosticCode = "request_timeout")
            } catch (_: Throwable) {
                throw AiProviderException(AiErrorKind.NETWORK, diagnosticCode = "network_transport_failed")
            }
        }
    }

    /**
     * 校验连接可达性与认证凭据有效性。
     *
     * @param connection 待测试连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥。
     * @return 耗时与可选远端模型数量。
     */
    override suspend fun testConnection(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
    ): AiConnectionTestResult {
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val models = listModels(connection, resolvedApiKey)
            AiConnectionTestResult(
                reachable = true,
                latencyMillis = mark.elapsedNow().inWholeMilliseconds,
                remoteModelCount = models.size,
            )
        } catch (error: AiProviderException) {
            if (error.kind == AiErrorKind.NOT_FOUND) {
                AiConnectionTestResult(
                    reachable = true,
                    latencyMillis = mark.elapsedNow().inWholeMilliseconds,
                    remoteModelCount = null,
                )
            } else {
                throw error
            }
        }
    }

    /**
     * 从 Provider `/models` 端点拉取候选模型列表并自动推断推荐上下文窗口。
     *
     * @param connection 目标连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥。
     * @return 按 `modelId` 排序的候选模型列表。
     */
    override suspend fun listModels(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
    ): List<AiRemoteModelInfo> = withTrackedJob {
        val baseUrl = validateAndNormalizeBaseUrl(connection, resolvedApiKey)
        val rawBody = try {
            httpClient.prepareGet("$baseUrl/models") {
                applyConnectionConfig(connection, resolvedApiKey, streaming = false)
                header(HttpHeaders.Accept, "application/json")
            }.execute { response ->
                ensureSuccessStatus(response)
                readBoundedUtf8(response, MAX_MODELS_RESPONSE_BYTES)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (providerError: AiProviderException) {
            throw providerError
        } catch (_: HttpRequestTimeoutException) {
            throw AiProviderException(AiErrorKind.TIMEOUT, diagnosticCode = "models_timeout")
        } catch (_: Throwable) {
            throw AiProviderException(AiErrorKind.NETWORK, diagnosticCode = "models_network_failed")
        }

        val root = try {
            CanonicalJson.format.parseToJsonElement(rawBody).jsonObject
        } catch (_: Throwable) {
            throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "models_invalid_json")
        }
        val items = (root["data"] as? JsonArray).orEmpty()
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val displayName = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: id
            AiRemoteModelInfo(
                modelId = id,
                displayName = displayName,
                suggestedContextWindowTokens = AiConnectionPresets.inferRecommendedContextWindowTokens(id),
                suggestedThinkingEfforts = AiConnectionPresets.inferSupportedThinkingEfforts(id),
            )
        }.distinctBy(AiRemoteModelInfo::modelId).sortedBy(AiRemoteModelInfo::modelId)
    }

    /**
     * 发送不含任何工作区笔记数据的最小探测函数请求，实测模型结构化工具调用闭环。
     *
     * @param connection 目标连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥。
     * @param modelId 待验证的远端原始 `modelId`。
     * @return 工具能力实测结果。
     */
    override suspend fun probeToolCalling(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
        modelId: String,
    ): AiToolProbeResult {
        require(modelId.isNotBlank()) { "待实测模型 ID 不能为空" }
        val probeTool = AiToolDefinition(
            name = PROBE_TOOL_NAME,
            description = "Minimal capability ping used to verify structured tool calling support.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("echo", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("echo")) })
            },
        )
        val probeRequest = AiStreamRequest(
            connection = connection,
            resolvedApiKey = resolvedApiKey,
            modelId = modelId.trim(),
            messages = listOf(
                AiChatMessage(
                    role = AiChatRole.USER,
                    content = "Invoke the tool $PROBE_TOOL_NAME with {\"echo\":\"ok\"} and do not answer in plain text.",
                ),
            ),
            tools = listOf(probeTool),
            maxOutputTokens = 128,
        )
        var detectedCall: AiToolCall? = null
        var finalUsage: AiTokenUsage? = null
        stream(probeRequest).collect { event ->
            when (event) {
                is AiStreamEvent.ToolCallReady -> if (detectedCall == null) detectedCall = event.toolCall
                is AiStreamEvent.Completed -> finalUsage = event.usage
                else -> Unit
            }
        }
        return AiToolProbeResult(
            supported = detectedCall != null,
            invokedToolName = detectedCall?.name,
            usage = finalUsage,
        )
    }

    /**
     * 立即取消当前端口追踪的所有在途请求（供工作区锁定或切换时调用）。
     */
    fun cancelActiveRequests() {
        val snapshot = synchronized(activeRequestJobs) { activeRequestJobs.toList().also { activeRequestJobs.clear() } }
        snapshot.forEach { job -> job.cancel(CancellationException("workspace_locked")) }
    }

    /**
     * 取消所有在途请求并彻底释放底层 Ktor 客户端资源。
     */
    fun close() {
        cancelActiveRequests()
        httpClient.coroutineContext.cancel()
        httpClient.close()
    }

    /**
     * 校验连接地址与认证凭据完整性，拒绝向非回环明文 HTTP 地址发送真实 API Key。
     *
     * @param connection 连接配置。
     * @param resolvedApiKey 当前会话内存解析密钥。
     * @return 去除尾部斜杠的规范化基础地址。
     */
    private fun validateAndNormalizeBaseUrl(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
    ): String {
        if (!WorkspaceConfigValidator.isValidEndpointBaseUrl(connection.baseUrl)) {
            throw AiProviderException(AiErrorKind.INVALID_REQUEST, diagnosticCode = "invalid_base_url")
        }
        val trimmedKey = resolvedApiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (connection.authMode != AiAuthMode.NONE && trimmedKey == null) {
            throw AiProviderException(AiErrorKind.UNAUTHORIZED, diagnosticCode = "missing_api_key")
        }
        val parsed = Url(connection.baseUrl.trim())
        val isLoopback = parsed.host.lowercase() in LOOPBACK_HOSTS
        if (parsed.protocol.name == "http" && !isLoopback && (connection.authMode != AiAuthMode.NONE || trimmedKey != null)) {
            throw AiProviderException(AiErrorKind.INVALID_REQUEST, diagnosticCode = "cleartext_http_forbidden_with_credentials")
        }
        return connection.baseUrl.trim().trimEnd('/')
    }

    /**
     * 为单次请求注入超时、可选 Bearer 密钥与经过清洗的非敏感自定义请求头。
     *
     * @param connection 连接配置。
     * @param resolvedApiKey 当前会话内存密钥。
     * @param streaming 是否为长耗时流式生成请求。
     */
    private fun HttpRequestBuilder.applyConnectionConfig(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
        streaming: Boolean,
    ) {
        val socketMillis = connection.timeoutSeconds.coerceIn(5, 600) * 1_000L
        timeout {
            connectTimeoutMillis = socketMillis.coerceAtMost(20_000L)
            socketTimeoutMillis = socketMillis
            requestTimeoutMillis = if (streaming) socketMillis * 6L else socketMillis
        }
        if (connection.authMode != AiAuthMode.NONE) {
            val token = resolvedApiKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw AiProviderException(AiErrorKind.UNAUTHORIZED, diagnosticCode = "missing_api_key")
            bearerAuth(token)
        }
        connection.customHeaders.forEach { (name, value) ->
            val trimmedName = name.trim()
            if (trimmedName.isNotEmpty() && trimmedName.lowercase() != "authorization") {
                header(trimmedName, value)
            }
        }
    }

    /**
     * 将非 2xx HTTP 状态码确定性映射为脱敏 [AiProviderException]，绝不回显远端错误正文。
     *
     * @param response HTTP 响应头状态。
     */
    private fun ensureSuccessStatus(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        val kind = when (status) {
            401 -> AiErrorKind.UNAUTHORIZED
            403 -> AiErrorKind.FORBIDDEN
            404 -> AiErrorKind.NOT_FOUND
            408, 504 -> AiErrorKind.TIMEOUT
            429 -> AiErrorKind.RATE_LIMITED
            400, 422 -> AiErrorKind.INVALID_REQUEST
            in 500..599 -> AiErrorKind.SERVER_ERROR
            else -> AiErrorKind.PROTOCOL
        }
        throw AiProviderException(kind = kind, statusCode = status, diagnosticCode = "http_$status")
    }

    /**
     * 读取受最大字节数上限保护的 UTF-8 JSON 响应体。
     *
     * @param response HTTP 响应。
     * @param maxBytes 允许读取的最大字节数。
     * @return 解码后的 UTF-8 文本。
     */
    private suspend fun readBoundedUtf8(response: HttpResponse, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes + 1)
        val channel = response.bodyAsChannel()
        var total = 0
        while (total < buffer.size) {
            val read = channel.readAvailable(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > maxBytes) {
            throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "response_body_too_large")
        }
        return buffer.decodeToString(0, total)
    }

    /**
     * 将当前协程 Job 注册到活动集合中，执行完毕后自动移除。
     *
     * @param T 返回值类型。
     * @param block 需要在受控取消域内执行的挂起操作。
     * @return [block] 返回结果。
     */
    private suspend fun <T> withTrackedJob(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        if (job != null) {
            synchronized(activeRequestJobs) { activeRequestJobs += job }
        }
        return try {
            block()
        } finally {
            if (job != null) {
                synchronized(activeRequestJobs) { activeRequestJobs -= job }
            }
        }
    }

    private companion object {
        /** `/models` 列表响应允许的最大字节数（1 MiB）。 */
        const val MAX_MODELS_RESPONSE_BYTES: Int = 1024 * 1024

        /** 零笔记工具调用实测固定函数名。 */
        const val PROBE_TOOL_NAME: String = "tagloom_capability_ping"

        /** 允许使用明文 HTTP 本地通信的字面回环主机集合。 */
        val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
