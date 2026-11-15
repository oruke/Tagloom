package com.oruke.tagloom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.oruke.tagloom.App
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableTextCell
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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 从正式 `App` 根节点驱动阶段四完整交互闭环与三档视口（`1360x860` / `960x800` / `430x840`）Skia 真实渲染截图验收：
 * 1. 正文选区浮动栏 `✦ 询问 / 润色`（`btn-selection-ask-ai`）与块手柄菜单 `✦ 引用此块到 Agent`（含结构化 `BaseTable` 块）；
 * 2. `LOCKED` 禁读笔记快捷挂载拦截与提示胶囊（`editor-agent-toast`），以及右侧伴随栏自动展开并聚焦输入框；
 * 3. `.composer-dock` 左侧 `StatsPills` 累计计费账单浮层与右侧 `14.dp` `ContextMeter` 环形表、三色分段条、`80%` 刻度线及模型窗口即时重算；
 * 4. `80%` 自动上下文压缩与手动 `[立即压缩]` 两阶段浓缩卡片展开、活跃 `@` 引用与未决 `PENDING` 提案无损保留及累计计费绝不清零。
 */
@OptIn(ExperimentalTestApi::class)
class Stage4SelectionMeterAndCompactionUiTest {
    /** 端到端验证选区/块手柄挂载、门禁拦截、双触发器浮层、模型窗口重算、自动/手动上下文压缩及三档视口布局。 */
    @Test(timeout = 120_000)
    fun verifiesQuickAttachDockPopoversAutoManualCompactionAndMultiViewportScreenshots() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        UiStage4LoopbackProvider().use { provider ->
            try {
                ServerRecoveryTestEnvironment().use { env ->
                    val device = env.device("stage4-ui-device")
                    var targetNoteId = ObjectId("placeholder")
                    var lockedNoteId = ObjectId("locked-placeholder")
                    val model16kId = "conn-stage4::deepseek-r1-16k"

                    runBlocking {
                        checkNotNull(device.gateway.prepareCreation(null))
                        device.gateway.confirmCreation()
                        val seeded = seedStage4Workspace(device, provider.baseUrl)
                        targetNoteId = seeded.first
                        lockedNoteId = seeded.second
                        provider.targetNoteId = targetNoteId.value
                        provider.targetRevision = seeded.third
                        device.gateway.lock()
                    }

                    // 1. 宽屏 1360x860：LOCKED 门禁拦截 -> 选区浮动栏「询问/润色」 -> BaseTable 块手柄挂载 -> 双浮层与窗口重算 -> 80% 自动压缩展开
                    runDesktopComposeUiTest(width = 1360, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")

                        // 1a. 先在 LOCKED 禁读笔记内框选文字点击「询问 / 润色」，验证拒绝挂载并弹出禁读提示
                        onNodeWithTag("sidebar-note-${lockedNoteId.value}").performClick()
                        waitForTag("text-input-blk-locked-1")
                        onNodeWithTag("text-input-blk-locked-1").performClick()
                        onNodeWithTag("text-input-blk-locked-1").performTextInputSelection(TextRange(0, 8))
                        waitForTag("btn-selection-ask-ai")
                        onNodeWithTag("btn-selection-ask-ai").performClick()
                        waitForTag("editor-agent-toast")
                        onNode(hasText("当前笔记已设为禁读策略，无法挂载到 Agent", substring = true)).assertIsDisplayed()
                        assertTrue(
                            onAllNodesWithTag("agent-companion-pane").fetchSemanticsNodes().isEmpty(),
                            "LOCKED 禁读笔记严禁通过选区快捷入口挂载或打开伴随栏",
                        )

                        // 1b. 切回可读笔记《同步压缩压测报告》，选中首段文字，展示浮动栏「✦ 询问 / 润色」并截图
                        onNodeWithTag("sidebar-note-${targetNoteId.value}").performClick()
                        waitForTag("text-input-blk-stage4-1")
                        onNodeWithTag("text-input-blk-stage4-1").performClick()
                        onNodeWithTag("text-input-blk-stage4-1").performTextInputSelection(TextRange(0, 14))
                        waitForTag("btn-selection-ask-ai")
                        saveScreenshot("stage4-wide-selection-toolbar.png")

                        // 点击「询问 / 润色」-> 自动展开右侧伴随栏、挂载单段 BLOCKS 引用并聚焦作曲框输入区
                        onNodeWithTag("btn-selection-ask-ai").performClick()
                        waitForTag("agent-companion-pane")
                        onNodeWithTag("agent-composer-input").assertIsFocused()
                        onNode(hasText("同步压缩压测报告 · 1 段", substring = true)).assertIsDisplayed()

                        // 1c. 点击结构化 BaseTable 块手柄菜单「✦ 引用此块到 Agent」，合并挂载第 2 段并保持聚焦
                        onNodeWithTag("block-handle-blk-stage4-table").performClick()
                        waitForTag("block-menu-attach-agent-blk-stage4-table")
                        onNodeWithTag("block-menu-attach-agent-blk-stage4-table").performClick()
                        waitForIdle()
                        onNode(hasText("同步压缩压测报告 · 2 段", substring = true)).assertIsDisplayed()
                        onNodeWithTag("agent-composer-input").assertIsFocused()

                        // 更新最新真实 revision 给回环模拟服务，发送第 1 轮问答（返回 1 份 PENDING 提案 + 3,600 输入 / 75% 缓存命中）
                        val liveRev = runBlocking {
                            checkNotNull(checkNotNull(device.gateway.reload()).notes.single { it.id == targetNoteId }.revision)
                        }
                        provider.targetRevision = "${liveRev.actorId.value}:${liveRev.sequence}"

                        onNodeWithTag("agent-composer-input").performTextReplacement("请核对这两段压测指标并生成一条结论替换提案供我稍后审阅")
                        onNodeWithTag("agent-composer-send-button").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            findFirstTagWithPrefix("agent-proposal-review-left-") != null &&
                                onAllNodesWithTag("agent-composer-stop-button").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()

                        // 1d. 验证左侧 StatsPills 累计计费触发器与明细浮层（含前缀缓存命中率与思考 Token）
                        onNodeWithTag("agent-token-usage-trigger").assertIsDisplayed()
                        onNode(hasText("tok · 缓存命中 75%", substring = true)).assertIsDisplayed()
                        onNodeWithTag("agent-token-usage-trigger").performClick()
                        waitForTag("popover-token-usage")
                        onNode(hasText("会话累计 Token", substring = true)).assertIsDisplayed()
                        onNode(hasText("不会抹除历史累计账单", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage4-wide-token-usage-popover.png")
                        onAllNodes(isRoot())[0].performMouseInput { click(Offset(24f, 24f)) }
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("popover-token-usage").fetchSemanticsNodes().isEmpty()
                        }

                        // 1e. 验证切换模型窗口（从 32K 切到 16K）无需发消息即时重算 ContextMeter 分母与预警比例，并打开右侧三色分段浮层
                        onNode(hasText("/ 32K", substring = true)).assertIsDisplayed()
                        onNodeWithTag("agent-model-thinking-button").performClick()
                        waitForTag("agent-model-option-$model16kId")
                        onNodeWithTag("agent-model-option-$model16kId").performClick()
                        onNodeWithTag("agent-thinking-medium").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-model-option-$model16kId").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        onNode(hasText("/ 16K", substring = true)).assertIsDisplayed()

                        onNodeWithTag("agent-context-meter-trigger").performClick()
                        waitForTag("popover-context-meter")
                        onNodeWithTag("context-meter-segmented-bar").assertIsDisplayed()
                        onNodeWithTag("context-meter-threshold-tick").assertIsDisplayed()
                        onNodeWithTag("btn-manual-compact").assertIsDisplayed()
                        onNode(hasText("自动压缩线（窗口 80%）", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage4-wide-context-meter-popover.png")
                        onAllNodes(isRoot())[0].performMouseInput { click(Offset(24f, 24f)) }
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("popover-context-meter").fetchSemanticsNodes().isEmpty()
                        }

                        // 1f. 发送第 2 轮问答（返回 14,296 用量 >= 16K 的 80%=12,800），自动触发两阶段压缩并生成可展开卡片
                        val totalTokensBeforeTurn2 = runBlocking {
                            checkNotNull(device.gateway.agentHarness).listThreads().single().thread.cumulativeUsage.totalTokens
                        }
                        onNodeWithTag("agent-composer-input").performTextReplacement("继续补充离线多副本三方合并的吞吐边界结论")
                        onNodeWithTag("agent-composer-send-button").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            findFirstTagWithPrefix("agent-compaction-card-") != null &&
                                onAllNodesWithTag("agent-composer-stop-button").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()

                        val autoCardTag = checkNotNull(findFirstTagWithPrefix("agent-compaction-card-"))
                        val autoCompactionId = autoCardTag.removePrefix("agent-compaction-card-")
                        onNode(hasText("上下文已自动压缩", substring = true)).assertIsDisplayed()

                        // 展开自动压缩卡片查看浓缩摘要，验证活跃 @ 引用胶囊、未决 Diff 提案及累计计费账单完整保留
                        onNodeWithTag("agent-compaction-toggle-$autoCompactionId").performClick()
                        waitForTag("agent-compaction-body-$autoCompactionId")
                        val activeComposerRefTag = checkNotNull(findFirstTagWithPrefix("agent-composer-ref-blocks:"))
                        onNodeWithTag(activeComposerRefTag).assertIsDisplayed()
                        onNode(hasText("近期 1 轮对话原文、活跃 @ 引用", substring = true)).assertIsDisplayed()
                        assertTrue(
                            findFirstTagWithPrefix("agent-proposal-review-left-") != null,
                            "自动压缩后未决 PENDING 修改提案卡片必须无损保留",
                        )
                        val persistedAfterAuto = runBlocking {
                            checkNotNull(device.gateway.agentHarness).listThreads().single().thread
                        }
                        assertEquals(1, persistedAfterAuto.compactions.size)
                        assertEquals(AgentProposalStatus.PENDING, persistedAfterAuto.proposals.single().status)
                        assertTrue(
                            persistedAfterAuto.cumulativeUsage.totalTokens > totalTokensBeforeTurn2,
                            "自动上下文压缩绝不能清零会话累计计费 Token 账单",
                        )
                        saveScreenshot("stage4-wide-auto-compaction-expanded.png")
                    }

                    runBlocking { device.gateway.lock() }

                    // 2. 中屏 960x800：验证伴随栏展开时左栏自动收起，并通过 ContextMeter 浮层点击「立即压缩」生成手动压缩节点
                    runDesktopComposeUiTest(width = 960, height = 800) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")
                        onNodeWithTag("editor-companion-toggle").performClick()
                        waitForTag("agent-companion-pane")
                        assertTrue(
                            onAllNodesWithTag("workspace-sidebar").fetchSemanticsNodes().isEmpty(),
                            "中屏打开右侧伴随栏时左侧导航栏应自动收起",
                        )

                        onNodeWithTag("agent-context-meter-trigger").performClick()
                        waitForTag("btn-manual-compact")
                        onNodeWithTag("btn-manual-compact").performClick()
                        waitUntil(timeoutMillis = 15_000) {
                            collectAllTagsWithPrefix("agent-compaction-card-").size >= 2
                        }
                        waitForIdle()
                        onNode(hasText("已手动压缩上下文", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage4-medium-companion-compacted.png")
                    }

                    runBlocking { device.gateway.lock() }

                    // 3. 窄屏移动端 430x840：验证底部伴随抽屉内双触发器 Dock 与压缩时间线卡片无溢出正常渲染
                    runDesktopComposeUiTest(width = 430, height = 840) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("editor-companion-toggle")
                        onNodeWithTag("editor-companion-toggle").performClick()
                        waitForTag("agent-companion-pane")
                        onNodeWithTag("agent-composer-dock").assertIsDisplayed()
                        onNodeWithTag("agent-token-usage-trigger").assertIsDisplayed()
                        onNodeWithTag("agent-context-meter-trigger").assertIsDisplayed()
                        saveScreenshot("stage4-compact-mobile-sheet.png")
                    }
                }
            } finally {
                Locale.setDefault(previousLocale)
            }
        }
    }

    /** 预置可读压测笔记（含段落与 `BaseTable` 块）、`LOCKED` 禁读笔记及 `32K`/`16K` 双模型窗口配置。 */
    private suspend fun seedStage4Workspace(
        device: ServerRecoveryTestDevice,
        baseUrl: String,
    ): Triple<ObjectId, ObjectId, String> {
        device.gateway.agentConfig.saveConnection(
            WorkspaceConnectionSaveDraft(
                connectionId = "conn-stage4",
                name = "DeepSeek Stage4 Mock",
                baseUrl = baseUrl,
                protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                authMode = AiAuthMode.KEYSTORE,
                syncKeyInEnvelope = true,
                newApiKeyInput = "sk-stage4-ui-key",
            ),
        )
        device.gateway.agentConfig.saveModel(
            AiModelConfig(
                id = "conn-stage4::deepseek-v3-32k",
                connectionId = "conn-stage4",
                modelId = "deepseek-v3-32k",
                displayName = "DeepSeek 32K",
                contextWindowTokens = 32_000,
                toolsState = AiModelToolsState.VERIFIED,
                supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
            ),
        )
        device.gateway.agentConfig.saveModel(
            AiModelConfig(
                id = "conn-stage4::deepseek-r1-16k",
                connectionId = "conn-stage4",
                modelId = "deepseek-r1-16k",
                displayName = "DeepSeek 16K",
                contextWindowTokens = 16_000,
                toolsState = AiModelToolsState.VERIFIED,
                supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
            ),
        )
        device.gateway.agentConfig.savePreferences(
            AiAgentPreferences(autoCompactEnabled = true, autoCompactPercent = 80, retainPercent = 16),
        )

        val lockedNote = device.gateway.saveNote(
            null,
            Note(
                title = "私密恢复密码本",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                aiAccessPolicy = AiAccessPolicy.LOCKED,
                blocks = listOf(
                    Block(
                        id = BlockId("blk-locked-1"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "0000000000",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("主密钥助记词严禁进入任何上下文挂载缓冲区。")))),
                    ),
                ),
            ),
        )
        val readableNote = device.gateway.saveNote(
            null,
            Note(
                title = "同步压缩压测报告",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                blocks = listOf(
                    Block(
                        id = BlockId("blk-stage4-1"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "0000000000",
                        content = RichTextBlockContent(
                            RichText(listOf(InlineRun("两阶段上下文压缩在单轮结束越过 80% 预算时自动修剪早期冗余工具输出并保留近期原文。"))),
                        ),
                    ),
                    Block(
                        id = BlockId("blk-stage4-2"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "0000000001",
                        content = RichTextBlockContent(
                            RichText(listOf(InlineRun("累计计费 Token 账单统计前缀缓存读取与思考消耗，不受上下文窗口压缩重置影响。"))),
                        ),
                    ),
                    Block(
                        id = BlockId("blk-stage4-table"),
                        type = BlockTypes.BASE_TABLE,
                        orderKey = "0000000002",
                        content = BaseTableBlockContent(
                            columns = listOf(
                                BaseTableColumn(
                                    columnId = BaseTableColumnId("col-metric"),
                                    orderKey = "0000000000",
                                    name = RichText(listOf(InlineRun("指标"))),
                                    type = BaseTableColumnTypes.TEXT,
                                ),
                                BaseTableColumn(
                                    columnId = BaseTableColumnId("col-val"),
                                    orderKey = "0000000001",
                                    name = RichText(listOf(InlineRun("实测值"))),
                                    type = BaseTableColumnTypes.TEXT,
                                ),
                            ),
                            rows = listOf(
                                BaseTableRow(
                                    rowId = BaseTableRowId("row-1"),
                                    orderKey = "0000000000",
                                    cells = mapOf(
                                        BaseTableColumnId("col-metric") to BaseTableTextCell(RichText(listOf(InlineRun("缓存命中率")))),
                                        BaseTableColumnId("col-val") to BaseTableTextCell(RichText(listOf(InlineRun("76%")))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val rev = checkNotNull(readableNote.revision)
        return Triple(readableNote.id, lockedNote.id, "${rev.actorId.value}:${rev.sequence}")
    }

    /** 查找首个匹配给定前缀的 `testTag`。 */
    private fun ComposeUiTest.findFirstTagWithPrefix(prefix: String): String? =
        collectAllTagsWithPrefix(prefix).firstOrNull()

    /** 按垂直坐标排序收集全部匹配前缀的 `testTag`。 */
    private fun ComposeUiTest.collectAllTagsWithPrefix(prefix: String): List<String> =
        onAllNodes(isRoot())[0].fetchSemanticsNode().let { root ->
            collectTaggedNodes(root, prefix).sortedBy { it.second }.map { it.first }
        }

    /** 递归扫描语义树收集前缀匹配的标签与纵坐标。 */
    private fun collectTaggedNodes(node: SemanticsNode, prefix: String): List<Pair<String, Float>> {
        val out = mutableListOf<Pair<String, Float>>()
        val tag = node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
        if (tag != null && tag.startsWith(prefix)) {
            out += tag to node.boundsInRoot.top
        }
        node.children.forEach { child -> out += collectTaggedNodes(child, prefix) }
        return out
    }

    /** 等待目标 `testTag` 出现，超时则保存失败现场截图。 */
    private fun ComposeUiTest.waitForTag(tag: String) {
        try {
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val roots = runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(0)
            if (roots == 1) {
                runCatching { saveScreenshot("stage4-failure-$tag.png") }
            }
            throw AssertionError("Timeout waiting for tag '$tag' (roots=$roots)", error)
        }
    }

    /** 保存当前窗口真实 Skia 渲染帧至 `build/reports/stage4-agent-ui/` 供视觉验收。 */
    private fun ComposeUiTest.saveScreenshot(filename: String) {
        val dir = Path.of("build", "reports", "stage4-agent-ui")
        Files.createDirectories(dir)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                Files.write(dir.resolve(filename), encoded.bytes)
            }
        }
    }
}

/** 为阶段四 UI 测试提供提案生成、真实缓存/思考计费指标与两阶段上下文浓缩摘要的本机 SSE 回环服务。 */
private class UiStage4LoopbackProvider : AutoCloseable {
    /** 当前可读测试笔记 ID。 */
    @Volatile var targetNoteId: String = ""
    /** 当前可读测试笔记最新乐观锁修订号。 */
    @Volatile var targetRevision: String = ""
    /** 业务提问轮次序号。 */
    private val userTurnSeq = AtomicInteger(0)
    /** HTTP 线程池。 */
    private val executor = Executors.newCachedThreadPool()
    /** 随机端口本机回环 HTTP 服务。 */
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@UiStage4LoopbackProvider.executor
        createContext("/v1/chat/completions") { handleChat(it) }
        start()
    }

    /** 供连接配置绑定的 `/v1` 地址。 */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** 返回提案工具调用、带缓存/思考明细的流式回复或两阶段上下文浓缩纪要。 */
    private fun handleChat(exchange: HttpExchange) {
        val req = exchange.requestBody.readBytes().decodeToString()
        val isCompactor = req.contains("Tagloom 会话上下文压缩器")
        val endsWithToolResult = req.lastIndexOf("\"role\":\"tool\"") > req.lastIndexOf("\"role\":\"user\"")
        val sse = when {
            isCompactor -> {
                val summary = "已浓缩前序压测核验轮次：确认两阶段压缩修剪早期冗余工具载荷、缓存命中率 76%，待审补丁提案与已挂载段落继续保留。"
                "data: {\"choices\":[{\"delta\":{\"content\":\"$summary\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":320,\"completion_tokens\":48,\"prompt_tokens_details\":{\"cached_tokens\":240}}}\n\ndata: [DONE]\n\n"
            }
            !endsWithToolResult && userTurnSeq.incrementAndGet() == 1 -> {
                val args = """{\"noteId\":\"$targetNoteId\",\"expectedRevision\":\"$targetRevision\",\"operations\":[{\"op\":\"replace_block\",\"blockId\":\"blk-stage4-2\",\"markdown\":\"累计计费 Token 账单精确记录前缀缓存命中与深度思考开销，自动或手动压缩均不清零。\"}]}"""
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_stage4_patch\",\"type\":\"function\",\"function\":{\"name\":\"patch_note_blocks\",\"arguments\":\"$args\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":1400,\"completion_tokens\":50,\"prompt_tokens_details\":{\"cached_tokens\":1050}}}\n\ndata: [DONE]\n\n"
            }
            endsWithToolResult -> {
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"已核对表格缓存命中率并生成待审补丁\",\"content\":\"已结合《同步压缩压测报告》 [1] 核对前两段与表格指标，并附上待确认补丁提案。\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11520,\"completion_tokens\":120,\"prompt_tokens_details\":{\"cached_tokens\":8640},\"completion_tokens_details\":{\"reasoning_tokens\":40}}}\n\ndata: [DONE]\n\n"
            }
            else -> {
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"评估离线多副本收敛与窗口预算\",\"content\":\"第二轮补充结论：双端离线并发压缩节点按稳定 ID 并集收敛 [1]，活跃窗口已自动压缩回安全区间。\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":14200,\"completion_tokens\":96,\"prompt_tokens_details\":{\"cached_tokens\":11360},\"completion_tokens_details\":{\"reasoning_tokens\":30}}}\n\ndata: [DONE]\n\n"
            }
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, sse.size.toLong())
        exchange.responseBody.use { it.write(sse) }
    }

    /** 停止回环服务。 */
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
