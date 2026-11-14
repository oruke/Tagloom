package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import com.oruke.tagloom.workspace.ServerRecoveryTestDevice
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
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
import kotlin.test.assertTrue

/**
 * 从正式 `App` 根节点驱动阶段二完整交互与 Skia 多视口真实渲染截图验收：
 * 1. 单左栏无冗余菜单、`最近会话 Top 5` 与 `最近笔记 Top 5` 纯浏览点击零跳序，发送新消息后才提升首位；
 * 2. 系统默认「全部笔记」Database 多视图（表格/看板/画廊）与三态 `AiAccessPolicy` 徽章；
 * 3. 无框作曲框纯图标按钮、合一「模型 · 思考 ▾」、只读工具循环、`[1]` 角标核验跳回段落高亮、纯图标消息动作条；
 * 4. 宽屏（`1280`）、中屏（`900` 左栏自动收起）与窄屏（`430` 底部抽屉 + `← 返回 Agent 对话` 胶囊）三档自适应。
 */
@OptIn(ExperimentalTestApi::class)
class Stage2AgentHarnessUiTest {
    /** 验证单左栏零跳序、全部笔记系统页、双形态问答与 `[1]` 角标原位高亮及三档响应式切换。 */
    @Test
    fun verifiesSingleSidebarZeroJumpAllNotesDualFormCitationAndResponsiveLayouts() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        UiStage2LoopbackProvider().use { provider ->
            try {
                ServerRecoveryTestEnvironment().use { env ->
                    val device = env.device("stage2-ui-device")
                    var citedNoteId = ObjectId("placeholder")
                    var threadOldId = ObjectId("th-old")
                    var threadNewId = ObjectId("th-new")
                    val noteIds = mutableListOf<ObjectId>()

                    runBlocking {
                        checkNotNull(device.gateway.prepareCreation(null))
                        device.gateway.confirmCreation()
                        seedWorkspaceForUiTest(device, provider.baseUrl).also { seeded ->
                            citedNoteId = seeded.first
                            noteIds.addAll(seeded.second)
                            threadOldId = seeded.third.first
                            threadNewId = seeded.third.second
                        }
                        device.gateway.lock()
                    }

                    // 1. 宽屏 1280x860：验证单左栏、零跳序、「全部笔记」Database、全屏 Agent 对话与 [1] 角标跳转右侧伴随分栏
                    runDesktopComposeUiTest(width = 1280, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")

                        // 严禁存在冗余「Agent 工作台」菜单
                        assertTrue(onAllNodesWithText("Agent 工作台").fetchSemanticsNodes().isEmpty())
                        onNodeWithTag("sidebar-new-note").assertIsDisplayed()
                        onNodeWithTag("sidebar-new-thread").assertIsDisplayed()
                        onNodeWithTag("all-notes-navigation").assertIsDisplayed()

                        // 验证最近笔记 Top 5：6 篇用户笔记默认仅显示前 5 篇，且在最近笔记内点击第 3 篇绝不跳序
                        waitUntil(timeoutMillis = 15_000) {
                            noteIds.count { id -> onAllNodesWithTag("sidebar-note-${id.value}").fetchSemanticsNodes().isNotEmpty() } == 5
                        }
                        val beforeNoteTopOrder = currentSidebarTagOrder("sidebar-note-")
                        assertEquals(5, beforeNoteTopOrder.size)
                        val thirdNoteTag = beforeNoteTopOrder[2]
                        onNodeWithTag(thirdNoteTag).performClick()
                        waitForIdle()
                        assertEquals(beforeNoteTopOrder, currentSidebarTagOrder("sidebar-note-"), "点击最近笔记严禁跳序")

                        // 验证最近会话：点击较早会话 threadOld 仅打开查看，顺序保持 [threadNew, threadOld] 绝不跳动
                        waitForTag("sidebar-thread-${threadNewId.value}")
                        waitForTag("sidebar-thread-${threadOldId.value}")
                        val initialThreadOrder = currentSidebarTagOrder("sidebar-thread-")
                        assertEquals(
                            listOf("sidebar-thread-${threadNewId.value}", "sidebar-thread-${threadOldId.value}"),
                            initialThreadOrder,
                        )
                        onNodeWithTag("sidebar-thread-${threadOldId.value}").performClick()
                        waitForTag("agent-workspace-screen")
                        assertEquals(initialThreadOrder, currentSidebarTagOrder("sidebar-thread-"), "纯浏览点击最近会话严禁跳序")

                        // 验证合一无框「模型 · 思考 ▾」弹层切换思考为「中」，开启 🌐 全库检索，挂载 @ 引用并发送新消息
                        onNodeWithTag("agent-model-thinking-button").performClick()
                        waitForTag("agent-thinking-medium")
                        onNodeWithTag("agent-thinking-medium").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-thinking-medium").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        onNodeWithTag("agent-composer-scope-button").performClick()
                        waitForIdle()

                        onNodeWithTag("agent-composer-attach-button").performClick()
                        waitForTag("agent-context-picker-popup")
                        onNodeWithTag("agent-picker-note-${citedNoteId.value}").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-context-picker-popup").fetchSemanticsNodes().isEmpty()
                        }

                        onNodeWithTag("agent-composer-input").performTextReplacement("核对双向同步设计里的加密合并约束")
                        onNodeWithTag("agent-composer-send-button").performClick()

                        // 等待助手流式回复与单次快照落盘完成（停止按钮消失且角标已渲染）：验证已发送新消息的 threadOld 此时排到最近会话第 1 位！
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithTag("agent-citation-badge-1").fetchSemanticsNodes().isNotEmpty() &&
                                onAllNodesWithTag("agent-composer-stop-button").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        assertEquals(
                            listOf("sidebar-thread-${threadOldId.value}", "sidebar-thread-${threadNewId.value}"),
                            currentSidebarTagOrder("sidebar-thread-"),
                            "实际发送新消息后会话应提升至最近会话首位",
                        )
                        // 未登记角标 [99] 严禁生成角标组件
                        assertTrue(onAllNodesWithTag("agent-citation-badge-99").fetchSemanticsNodes().isEmpty())
                        saveScreenshot("stage2-wide-fullscreen-agent.png")

                        // 点击 [1] 角标预览并定位到原文 -> 自动切为左编辑 + 右 AgentCompanionPane 并高亮目标段落
                        onNodeWithTag("agent-citation-badge-1").performClick()
                        waitForTag("agent-citation-jump-1")
                        onNodeWithTag("agent-citation-jump-1").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-citation-jump-1").fetchSemanticsNodes().isEmpty()
                        }

                        waitForTag("agent-companion-pane")
                        waitForTag("cited-highlighted-block-blk-sync-target")
                        saveScreenshot("stage2-wide-companion-split.png")

                        // 打开系统默认「全部笔记」Database 页面，切换看板/画廊视图并校验 6 篇笔记与策略徽章
                        onNodeWithTag("all-notes-navigation").performClick()
                        waitForTag("all-notes-database-screen")
                        waitForTag("all-notes-view-kanban")
                        onNodeWithTag("all-notes-view-kanban").performClick()
                        waitForIdle()
                        onNodeWithTag("all-notes-view-table").performClick()
                        waitForIdle()
                        saveScreenshot("stage2-all-notes-database.png")
                    }

                    runBlocking { device.gateway.lock() }

                    // 2. 中屏 900x760：验证打开右侧伴随栏时左侧导航栏自动收起，并露出 [☰] 临时展开按钮
                    runDesktopComposeUiTest(width = 900, height = 760) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")
                        waitForTag("editor-companion-toggle")
                        onNodeWithTag("editor-companion-toggle").performClick()
                        waitForTag("agent-companion-pane")
                        waitForTag("medium-expand-sidebar-button")
                        assertTrue(
                            onAllNodesWithTag("workspace-sidebar").fetchSemanticsNodes().isEmpty(),
                            "中屏展开右侧伴随分栏时应自动收起左侧导航栏",
                        )
                        saveScreenshot("stage2-medium-auto-collapse.png")
                    }

                    runBlocking { device.gateway.lock() }

                    // 3. 窄屏 430x840：验证底部对话抽屉与点击 [1] 跳转原文后的「← 返回 Agent 对话」悬浮胶囊
                    runDesktopComposeUiTest(width = 430, height = 840) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("compact-companion-toggle")
                        onNodeWithTag("compact-companion-toggle").performClick()
                        waitForTag("agent-compact-sheet")
                        waitForTag("agent-citation-badge-1")
                        saveScreenshot("stage2-compact-sheet.png")

                        onNodeWithTag("agent-citation-badge-1").performClick()
                        waitForTag("agent-citation-jump-1")
                        onNodeWithTag("agent-citation-jump-1").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-citation-jump-1").fetchSemanticsNodes().isEmpty()
                        }

                        waitForTag("agent-return-pill")
                        onNode(hasText("返回 Agent 对话", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage2-compact-return-pill.png")
                    }
                }
            } finally {
                Locale.setDefault(previousLocale)
            }
        }
    }

    /** 预置 6 篇带不同 `AiAccessPolicy` 的笔记、启用模型及 2 条不同时间戳的持久化会话。 */
    private suspend fun seedWorkspaceForUiTest(
        device: ServerRecoveryTestDevice,
        baseUrl: String,
    ): Triple<ObjectId, List<ObjectId>, Pair<ObjectId, ObjectId>> {
        device.gateway.agentConfig.saveConnection(
            WorkspaceConnectionSaveDraft(
                connectionId = "conn-ui",
                name = "DeepSeek UI Mock",
                baseUrl = baseUrl,
                protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                authMode = AiAuthMode.KEYSTORE,
                syncKeyInEnvelope = true,
                newApiKeyInput = "sk-ui-stage2",
            ),
        )
        device.gateway.agentConfig.saveModel(
            AiModelConfig(
                connectionId = "conn-ui",
                modelId = "deepseek-chat",
                displayName = "DeepSeek V3.2",
                contextWindowTokens = 128_000,
                toolsState = AiModelToolsState.VERIFIED,
                supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
            ),
        )

        val cited = device.gateway.saveNote(
            null,
            Note(
                title = "工作区双向同步设计",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                blocks = listOf(
                    Block(
                        id = BlockId("blk-sync-target"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "a",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("AGENT_THREAD 快照按消息稳定 ID 保序并集合并。")))),
                    ),
                ),
            ),
        )
        val refOnly = device.gateway.saveNote(
            null,
            Note(
                title = "11月随想草稿",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
                blocks = listOf(
                    Block(
                        id = BlockId("blk-ref"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "a",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("双向同步随想内部记录")))),
                    ),
                ),
            ),
        )
        val locked = device.gateway.saveNote(
            null,
            Note(
                title = "离线恢复根密钥清单",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                aiAccessPolicy = AiAccessPolicy.LOCKED,
                blocks = listOf(
                    Block(
                        id = BlockId("blk-locked"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "a",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("双向同步机密口令")))),
                    ),
                ),
            ),
        )
        val extraIds = (1..3).map { idx ->
            device.gateway.saveNote(
                null,
                Note(
                    title = "阶段研发周报 $idx",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    blocks = listOf(
                        Block(
                            id = BlockId("blk-w-$idx"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("常规研发进展记录 $idx")))),
                        ),
                    ),
                ),
            ).id
        }

        val harness = requireNotNull(device.gateway.agentHarness)
        val oldThread = harness.createThread(incognito = false, initialTitle = "早期索引边界讨论")
        delay(25)
        val newThread = harness.createThread(incognito = false, initialTitle = "近期架构评审会话")
        return Triple(cited.id, listOf(cited.id, refOnly.id, locked.id) + extraIds, oldThread.id to newThread.id)
    }

    /** 按屏幕垂直中心坐标提取侧栏指定前缀 `testTag` 的真实从上到下排列顺序。 */
    private fun ComposeUiTest.currentSidebarTagOrder(prefix: String): List<String> =
        onAllNodes(isRoot())[0].fetchSemanticsNode().let { root ->
            collectTaggedNodes(root, prefix)
                .sortedBy { it.second }
                .map { it.first }
        }

    /** 递归遍历语义树收集匹配前缀的节点 Tag 与顶部 Y 坐标。 */
    private fun collectTaggedNodes(
        node: androidx.compose.ui.semantics.SemanticsNode,
        prefix: String,
    ): List<Pair<String, Float>> {
        val result = mutableListOf<Pair<String, Float>>()
        val tag = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
        if (tag != null && tag.startsWith(prefix)) {
            result += tag to node.boundsInRoot.top
        }
        node.children.forEach { child -> result += collectTaggedNodes(child, prefix) }
        return result
    }

    /** 等待指定 `testTag` 出现在语义树中。 */
    private fun ComposeUiTest.waitForTag(tag: String) {
        try {
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val rootsCount = runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(0)
            if (rootsCount == 1) {
                runCatching { saveScreenshot("stage2-failure-$tag.png") }
            }
            throw AssertionError("Timeout waiting for tag '$tag' (roots=$rootsCount)", error)
        }
    }

    /** 保存真实 Skia 渲染帧截图供视觉审查。 */
    private fun ComposeUiTest.saveScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "stage2-agent-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                Files.write(directory.resolve(filename), encoded.bytes)
            }
        }
    }
}

/**
 * 为 UI 端到端测试提供先发起一次 `search_notes` 工具调用、再返回含真实角标 `[1]` 与伪造 `[99]` 流式回答的回环服务。
 */
private class UiStage2LoopbackProvider : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@UiStage2LoopbackProvider.executor
        createContext("/v1/chat/completions") { handleChat(it) }
        start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}/v1"

    private fun handleChat(exchange: HttpExchange) {
        val req = exchange.requestBody.readBytes().decodeToString()
        val hasToolMsg = req.contains("\"role\":\"tool\"")
        val sse = if (!hasToolMsg && req.contains("\"tools\":[")) {
            buildString {
                append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_ui_1\",\"type\":\"function\",\"function\":{\"name\":\"search_notes\",\"arguments\":\"{\\\"query\\\":\\\"双向同步\\\"}\"}}]},\"finish_reason\":null}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":140,\"completion_tokens\":22}}\n\n")
                append("data: [DONE]\n\n")
            }
        } else {
            buildString {
                append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"已核对检索返回范围\",\"content\":\"核对了《工作区双向同步设计》关键段落 [1]，会话消息按稳定 ID 保序合并；未登记编号 [99] 自动降级为普通文本。\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":210,\"completion_tokens\":48,\"prompt_tokens_details\":{\"cached_tokens\":100}}}\n\n")
                append("data: [DONE]\n\n")
            }
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, sse.size.toLong())
        exchange.responseBody.use { it.write(sse) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
