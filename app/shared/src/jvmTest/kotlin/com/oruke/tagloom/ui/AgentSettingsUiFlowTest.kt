package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 从正式 `App` 根节点驱动「设置 → Agent 与模型」完整交互闭环与桌面/窄屏真实渲染截图校验。
 */
@OptIn(ExperimentalTestApi::class)
class AgentSettingsUiFlowTest {
    /**
     * 验证预设填表、本机密钥保存与零回显、`/models` 拉取导入、模型上下文窗口切换（128K → 200K）、
     * 工具调用实测以及自动压缩比例调整（80% → 85%）的完整交互流转。
     */
    @Test
    fun configuresConnectionContextWindowProbeAndAutoCompactionThroughProductionUi() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val secretApiKey = "sk-ui-local-secret-8821"
        LoopbackMockAiProvider(expectedBearerToken = secretApiKey).use { mockProvider ->
            try {
                ServerRecoveryTestEnvironment().use { env ->
                    val device = env.device("agent-ui-device")
                    runBlocking {
                        checkNotNull(device.gateway.prepareCreation(null))
                        device.gateway.confirmCreation()
                        device.gateway.lock()
                    }

                    var savedConnId = ""
                    runDesktopComposeUiTest(width = 1280, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForUiText("设置")
                        onNode(hasText("设置") and hasClickAction()).performClick()
                        waitUntil(timeoutMillis = 15_000) {
                            onAllNodesWithTag("settings_open_agent").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("settings_open_agent").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 15_000) {
                            onAllNodesWithTag("agent_settings_screen").fetchSemanticsNodes().isNotEmpty()
                        }

                        onNodeWithTag("conn_preset_openai_compatible").performScrollTo().performClick()
                        onNodeWithTag("conn_name_input").performTextReplacement("Local Mock DeepSeek")
                        onNodeWithTag("conn_base_url_input").performTextReplacement(mockProvider.baseUrl)
                        onNodeWithTag("conn_api_key_input").performTextReplacement(secretApiKey)

                        onNodeWithTag("conn_test_button").performScrollTo().performClick()
                        waitForUiText("连接连通性测试成功。")

                        onNodeWithTag("conn_save_button").performScrollTo().performClick()
                        waitForUiText("API 连接已保存。")
                        waitForUiText("已就绪 · 本机安全存储")

                        val snapshotAfterConn = runBlocking { device.gateway.agentConfig.load() }
                        val savedConn = snapshotAfterConn.config.connections.single()
                        savedConnId = savedConn.connectionId
                        assertNull(savedConn.syncedApiKey, "脱敏快照严禁回显 API Key")
                        assertTrue(
                            onAllNodesWithText(secretApiKey, substring = true).fetchSemanticsNodes().isEmpty(),
                            "界面语义树严禁回显原始 API Key 明文",
                        )

                        onNodeWithTag("conn_fetch_models_$savedConnId").performScrollTo().performClick()
                        waitForUiText("已成功从端点拉取模型列表。")
                        onNodeWithTag("discovered_model_${savedConnId}_deepseek-chat").performScrollTo().performClick()
                        waitForUiText("模型与上下文窗口配置已保存。")

                        val compositeId = "$savedConnId::deepseek-chat"
                        waitForUiText("上下文窗口: 128K tok · 80% 自动压缩线: 102.4K tok（保留近期约 20.4K tok）")

                        onNodeWithTag("model_ctx_${compositeId}_200000").performScrollTo().performClick()
                        waitForUiText("上下文窗口: 200K tok · 80% 自动压缩线: 160K tok（保留近期约 32K tok）")

                        onNodeWithTag("model_probe_$compositeId").performScrollTo().performClick()
                        waitForUiText("工具调用实测通过，已标记为「工具: 已实测」。")
                        onNodeWithTag("model_tools_badge_$compositeId").assertTextEquals("工具: 已实测")

                        onNodeWithTag("pref_compact_chip_85").performScrollTo().performClick()
                        onNodeWithTag("pref_save_button").performScrollTo().performClick()
                        waitForUiText("自动压缩与运行边界偏好已保存。")
                        waitForUiText("上下文窗口: 200K tok · 85% 自动压缩线: 170K tok（保留近期约 32K tok）")

                        captureAgentUiScreenshot("agent-settings-desktop.png")
                    }

                    val persisted = runBlocking { device.gateway.agentConfig.load() }
                    val model = persisted.config.models.single()
                    assertEquals(200_000, model.contextWindowTokens)
                    assertEquals(170_000, model.autoCompactThresholdTokens(persisted.config.preferences.autoCompactPercent))
                    assertFalse(savedConnId.isBlank())
                    assertTrue(mockProvider.probeRequestCount >= 1)

                    runBlocking { device.gateway.lock() }

                    runDesktopComposeUiTest(width = 440, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForUiText("设置")
                        onNode(hasText("设置") and hasClickAction()).performClick()
                        waitUntil(timeoutMillis = 15_000) {
                            onAllNodesWithTag("settings_open_agent").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("settings_open_agent").performScrollTo().performClick()
                        waitForUiText("上下文窗口: 200K tok · 85% 自动压缩线: 170K tok（保留近期约 32K tok）")
                        captureAgentUiScreenshot("agent-settings-compact.png")
                    }
                }
            } finally {
                Locale.setDefault(previousLocale)
            }
        }
    }

    /**
     * 等待目标文本出现在语义树中，超时时输出当前截图以便诊断。
     */
    private fun ComposeUiTest.waitForUiText(expectedText: String) {
        try {
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText(expectedText).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            captureAgentUiScreenshot("agent-settings-failure.png")
            throw error
        }
    }

    /**
     * 将真实 Skia 渲染帧保存为 PNG 供视觉验收检查。
     */
    private fun ComposeUiTest.captureAgentUiScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "agent-settings-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                Files.write(directory.resolve(filename), encoded.bytes)
            }
        }
    }
}

/**
 * 提供回环 HTTP `/v1/models` 与 `/v1/chat/completions` SSE 工具探测响应的本地模拟服务端。
 */
private class LoopbackMockAiProvider(
    /** 预期客户端携带的 Bearer Token。 */
    private val expectedBearerToken: String,
) : AutoCloseable {
    /** 记录收到的工具调用实测次数。 */
    @Volatile
    var probeRequestCount: Int = 0
        private set

    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LoopbackMockAiProvider.executor
        createContext("/v1/models") { exchange -> handleModels(exchange) }
        createContext("/v1/chat/completions") { exchange -> handleChatCompletions(exchange) }
        start()
    }

    /** 供客户端填写的回环 Base URL。 */
    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}/v1"

    private fun handleModels(exchange: HttpExchange) {
        if (!checkBearer(exchange)) return
        val body = """{"data":[{"id":"deepseek-chat"},{"id":"gpt-4.1"}]}""".encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun handleChatCompletions(exchange: HttpExchange) {
        if (!checkBearer(exchange)) return
        val reqText = exchange.requestBody.readBytes().decodeToString()
        if (reqText.contains("tagloom_capability_ping")) {
            probeRequestCount++
        }
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_probe_1\",\"type\":\"function\",\"function\":{\"name\":\"tagloom_capability_ping\",\"arguments\":\"{\\\"echo\\\":\\\"ok\\\"}\"}}]},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":24,\"completion_tokens\":8,\"prompt_tokens_details\":{\"cached_tokens\":0}}}\n\n")
            append("data: [DONE]\n\n")
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, sse.size.toLong())
        exchange.responseBody.use { it.write(sse) }
    }

    private fun checkBearer(exchange: HttpExchange): Boolean {
        val auth = exchange.requestHeaders.getFirst("Authorization")
        if (auth != "Bearer $expectedBearerToken") {
            val err = """{"error":{"message":"unauthorized"}}""".encodeToByteArray()
            exchange.sendResponseHeaders(401, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
            return false
        }
        return true
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
