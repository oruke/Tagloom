package com.oruke.tagloom.sync.ai

import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiChatMessage
import com.oruke.tagloom.core.ai.AiChatRole
import com.oruke.tagloom.core.ai.AiErrorKind
import com.oruke.tagloom.core.ai.AiFinishReason
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiProviderException
import com.oruke.tagloom.core.ai.AiStreamEvent
import com.oruke.tagloom.core.ai.AiStreamRequest
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通过真实回环 TCP HTTP/SSE 服务验证 OpenAI Chat Completions 与 Responses 协议流式适配器、
 * `/models` 拉取、最小工具调用实测、错误脱敏与工作区锁定取消。
 */
class OpenAiStreamAdaptersTest {
    /** 验证 `/chat/completions` 流式解析思考摘要、正文增量、分片工具参数与缓存命中 Token 账单。 */
    @Test
    fun streamsChatCompletionsTextThinkingToolCallsAndCachedUsage() = runBlocking {
        withLoopbackServer { server, baseUrl ->
            var capturedAuth: String? = null
            var capturedPath: String? = null
            server.createContext("/v1/chat/completions") { exchange ->
                capturedAuth = exchange.requestHeaders.getFirst("Authorization")
                capturedPath = exchange.requestURI.path
                val sse = buildString {
                    appendLine("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"先检索相关段落"}}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[{"index":0,"delta":{"content":"已找到两处记录"}}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_chat_1","type":"function","function":{"name":"search_notes","arguments":"{\"q\":"}}]}}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"同步\"}"}}]},"finish_reason":"tool_calls"}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[],"usage":{"prompt_tokens":2000,"prompt_tokens_details":{"cached_tokens":1500},"completion_tokens":120,"completion_tokens_details":{"reasoning_tokens":40}}}""")
                    appendLine()
                    appendLine("data: [DONE]")
                    appendLine()
                }
                sendSse(exchange, sse)
            }

            val port = JvmKtorAiStreamPortFactory.create()
            try {
                val connection = AiProviderConnection(
                    connectionId = "conn-chat",
                    name = "Local Chat",
                    baseUrl = "$baseUrl/v1",
                    protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                    authMode = AiAuthMode.KEYSTORE,
                )
                val events = mutableListOf<AiStreamEvent>()
                port.stream(
                    AiStreamRequest(
                        connection = connection,
                        resolvedApiKey = "sk-test-chat-secret",
                        modelId = "deepseek-chat",
                        messages = listOf(AiChatMessage(AiChatRole.USER, "查找同步笔记")),
                        thinkingEffort = AiThinkingEffort.MEDIUM,
                    ),
                ).collect { events += it }

                assertEquals("Bearer sk-test-chat-secret", capturedAuth)
                assertEquals("/v1/chat/completions", capturedPath)
                assertTrue(events.any { it == AiStreamEvent.ThinkingDelta("先检索相关段落") })
                assertTrue(events.any { it == AiStreamEvent.TextDelta("已找到两处记录") })
                val ready = events.filterIsInstance<AiStreamEvent.ToolCallReady>().single()
                assertEquals("call_chat_1", ready.toolCall.callId)
                assertEquals("search_notes", ready.toolCall.name)
                assertEquals("""{"q":"同步"}""", ready.toolCall.argumentsJson)

                val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
                assertEquals(AiFinishReason.TOOL_CALLS, completed.finishReason)
                val usage = requireNotNull(completed.usage)
                assertEquals(500, usage.uncachedInputTokens)
                assertEquals(1500, usage.cacheReadTokens)
                assertEquals(120, usage.outputTokens)
                assertEquals(40, usage.reasoningTokens)
            } finally {
                port.close()
            }
        }
    }

    /** 验证 `/responses` 语义化 SSE 事件流解析思考摘要、正文增量、函数调用与精确 Token 明细。 */
    @Test
    fun streamsResponsesApiEventsAndExtractsUsage() = runBlocking {
        withLoopbackServer { server, baseUrl ->
            server.createContext("/v1/responses") { exchange ->
                val sse = buildString {
                    appendLine("event: response.reasoning_summary_text.delta")
                    appendLine("""data: {"type":"response.reasoning_summary_text.delta","delta":"核对快照 revision"}""")
                    appendLine()
                    appendLine("event: response.output_text.delta")
                    appendLine("""data: {"type":"response.output_text.delta","delta":"准备就绪"}""")
                    appendLine()
                    appendLine("event: response.output_item.added")
                    appendLine("""data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","call_id":"call_resp_1","type":"function_call","name":"get_note","arguments":""}}""")
                    appendLine()
                    appendLine("event: response.function_call_arguments.delta")
                    appendLine("""data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"noteId\":\"n-1\"}"}""")
                    appendLine()
                    appendLine("event: response.output_item.done")
                    appendLine("""data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_1","call_id":"call_resp_1","type":"function_call","name":"get_note","arguments":"{\"noteId\":\"n-1\"}"}}""")
                    appendLine()
                    appendLine("event: response.completed")
                    appendLine("""data: {"type":"response.completed","response":{"usage":{"input_tokens":4000,"input_tokens_details":{"cached_tokens":3200},"output_tokens":250,"output_tokens_details":{"reasoning_tokens":90}}}}""")
                    appendLine()
                }
                sendSse(exchange, sse)
            }

            val port = JvmKtorAiStreamPortFactory.create()
            try {
                val connection = AiProviderConnection(
                    connectionId = "conn-resp",
                    name = "OpenAI Responses",
                    baseUrl = "$baseUrl/v1",
                    protocol = AiProtocolKind.OPENAI_RESPONSES,
                    authMode = AiAuthMode.KEYSTORE,
                )
                val events = mutableListOf<AiStreamEvent>()
                port.stream(
                    AiStreamRequest(
                        connection = connection,
                        resolvedApiKey = "sk-test-resp-secret",
                        modelId = "gpt-4.1",
                        messages = listOf(
                            AiChatMessage(AiChatRole.SYSTEM, "保持严谨"),
                            AiChatMessage(AiChatRole.USER, "读取 n-1"),
                        ),
                        thinkingEffort = AiThinkingEffort.HIGH,
                    ),
                ).collect { events += it }

                assertTrue(events.any { it == AiStreamEvent.ThinkingDelta("核对快照 revision") })
                assertTrue(events.any { it == AiStreamEvent.TextDelta("准备就绪") })
                val ready = events.filterIsInstance<AiStreamEvent.ToolCallReady>().single()
                assertEquals("call_resp_1", ready.toolCall.callId)
                assertEquals("get_note", ready.toolCall.name)
                assertEquals("""{"noteId":"n-1"}""", ready.toolCall.argumentsJson)

                val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
                assertEquals(AiFinishReason.TOOL_CALLS, completed.finishReason)
                val usage = requireNotNull(completed.usage)
                assertEquals(800, usage.uncachedInputTokens)
                assertEquals(3200, usage.cacheReadTokens)
                assertEquals(250, usage.outputTokens)
                assertEquals(90, usage.reasoningTokens)
            } finally {
                port.close()
            }
        }
    }

    /** 验证 `/models` 模型列表拉取、连接测试、工具调用实测及异常零密钥泄露与即时取消。 */
    @Test
    fun listsModelsProbesToolsSanitizesErrorsAndCancelsInFlightStream() = runBlocking {
        withLoopbackServer { server, baseUrl ->
            server.createContext("/v1/models") { exchange ->
                val auth = exchange.requestHeaders.getFirst("Authorization")
                if (auth == "Bearer bad-secret-key") {
                    val body = """{"error":{"message":"Invalid key bad-secret-key for secret note"}}""".encodeToByteArray()
                    exchange.sendResponseHeaders(401, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    val body = """{"data":[{"id":"gpt-4.1"},{"id":"deepseek-r1"}]}""".encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            server.createContext("/v1/chat/completions") { exchange ->
                val sse = buildString {
                    appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"probe_1","type":"function","function":{"name":"tagloom_capability_ping","arguments":"{\"echo\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}""")
                    appendLine()
                    appendLine("data: [DONE]")
                    appendLine()
                }
                sendSse(exchange, sse)
            }
            val streamEntered = CompletableDeferred<Unit>()
            server.createContext("/stall/chat/completions") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0L)
                exchange.responseBody.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"开始\"}}]}\n\n".encodeToByteArray())
                exchange.responseBody.flush()
                streamEntered.complete(Unit)
                try {
                    Thread.sleep(10_000L)
                } catch (_: InterruptedException) {
                } finally {
                    runCatching { exchange.close() }
                }
            }

            val port = JvmKtorAiStreamPortFactory.create()
            try {
                val connection = AiProviderConnection(
                    connectionId = "conn-local",
                    name = "Ollama",
                    baseUrl = "$baseUrl/v1",
                    protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                    authMode = AiAuthMode.NONE,
                )
                val testResult = port.testConnection(connection, null)
                assertTrue(testResult.reachable)
                assertEquals(2, testResult.remoteModelCount)

                val models = port.listModels(connection, null)
                assertEquals(listOf("deepseek-r1", "gpt-4.1"), models.map { it.modelId })
                assertEquals(1_000_000, models.first { it.modelId == "gpt-4.1" }.suggestedContextWindowTokens)
                assertTrue(models.first { it.modelId == "deepseek-r1" }.suggestedThinkingEfforts.isNotEmpty())

                val probe = port.probeToolCalling(connection, null, "deepseek-r1")
                assertTrue(probe.supported)
                assertEquals("tagloom_capability_ping", probe.invokedToolName)

                val authedConn = connection.copy(authMode = AiAuthMode.KEYSTORE)
                val unauthorized = assertFailsWith<AiProviderException> {
                    port.listModels(authedConn, "bad-secret-key")
                }
                assertEquals(AiErrorKind.UNAUTHORIZED, unauthorized.kind)
                assertEquals(401, unauthorized.statusCode)
                assertFalse(unauthorized.message.orEmpty().contains("bad-secret-key"))

                val remoteInsecure = connection.copy(
                    baseUrl = "http://example.com/v1",
                    authMode = AiAuthMode.KEYSTORE,
                )
                val insecureError = assertFailsWith<AiProviderException> {
                    port.listModels(remoteInsecure, "sk-real-secret")
                }
                assertEquals(AiErrorKind.INVALID_REQUEST, insecureError.kind)

                val stallConn = connection.copy(baseUrl = "$baseUrl/stall")
                val firstChunkSeen = CompletableDeferred<Unit>()
                val job = async {
                    port.stream(
                        AiStreamRequest(
                            connection = stallConn,
                            resolvedApiKey = null,
                            modelId = "qwen3",
                            messages = listOf(AiChatMessage(AiChatRole.USER, "hello")),
                        ),
                    ).collect { event ->
                        if (event is AiStreamEvent.TextDelta) firstChunkSeen.complete(Unit)
                    }
                }
                streamEntered.await()
                firstChunkSeen.await()
                port.cancelActiveRequests()
                assertFailsWith<CancellationException> { job.await() }
            } finally {
                port.close()
            }
        }
    }

    /**
     * 启动绑定随机回环端口的测试 HTTP 服务并在块结束时立即关闭。
     *
     * @param block 接收服务实例与基础地址的测试逻辑。
     */
    private suspend fun withLoopbackServer(block: suspend (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            block(server, baseUrl)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /**
     * 向回环 HTTP 交换写入标准 `text/event-stream` 响应。
     *
     * @param exchange HTTP 交换对象。
     * @param ssePayload SSE 文本。
     */
    private fun sendSse(exchange: HttpExchange, ssePayload: String) {
        val bytes = ssePayload.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { out ->
            out.write(bytes)
            out.flush()
        }
    }
}
