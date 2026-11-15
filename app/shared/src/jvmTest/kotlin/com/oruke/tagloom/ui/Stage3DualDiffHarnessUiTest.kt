package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import com.oruke.tagloom.workspace.ServerRecoveryTestDevice
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 从正式 `App` 根节点驱动阶段三双方位共享原位 Diff 审阅、穿透切换、单事务原子提交、乐观锁冲突重算及三档视口截图验收：
 * 1. 内置 `AgentHarness` 暴露 14 只读 + 3 写提案工具（绝不暴露 `delete_note` / `export_note`），未确认前零笔记落盘；
 * 2. 全屏 Agent 工作台左会话 + 右侧滑出 Diff 面板（`460.dp` 宽屏 / `400.dp` 中屏左栏自动收起 / 窄屏全屏浮层）；
 * 3. 逐项「采纳 / 跳过」独立勾选，点击 `[↗ 在笔记页打开]` 无损穿透至左侧编辑页原位 Diff + 右侧伴随栏并 100% 保留勾选态；
 * 4. 点击 `[应用所选变更]` 经单次 `TransactionCoordinator` 原子事务同时确认更新后的 `Note` 与 `AgentThread`（`APPLIED`）；
 * 5. 目标笔记并发修改后 `expectedRevision` 冲突自动拦截直接应用，支持点击 `[基于最新内容重算提案]` 刷新基准后安全提交。
 */
@OptIn(ExperimentalTestApi::class)
class Stage3DualDiffHarnessUiTest {
    /** 端到端验证双方位共享 Diff、穿透无损保留跳过项、单事务原子确认、多视口自适应与乐观锁重算恢复。 */
    @Test
    fun verifiesDualOrientationSharedDiffPenetrationAtomicCommitAndConflictRecalculation() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        UiStage3LoopbackProvider().use { provider ->
            try {
                ServerRecoveryTestEnvironment().use { env ->
                    val device = env.device("stage3-ui-device")
                    var targetNoteId = ObjectId("placeholder")
                    var initialRevision = ""
                    var threadId = ObjectId("th-stage3")

                    runBlocking {
                        checkNotNull(device.gateway.prepareCreation(null))
                        device.gateway.confirmCreation()
                        val seeded = seedStage3Workspace(device, provider.baseUrl)
                        targetNoteId = seeded.first
                        initialRevision = seeded.second
                        threadId = seeded.third
                        provider.targetNoteId = targetNoteId.value
                        provider.latestRevision = initialRevision
                        device.gateway.lock()
                    }

                    var secondProposalId = ""

                    // 1. 宽屏 1360x860：全屏工作台生成复合提案 -> 右侧 Diff 审阅 -> 跳过第 3 项 -> 穿透至笔记页左侧 Diff -> 单事务原子落盘 -> 制造修订冲突并重算
                    runDesktopComposeUiTest(width = 1360, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")

                        onNodeWithTag("sidebar-thread-${threadId.value}").performClick()
                        waitForTag("agent-workspace-screen")

                        // 挂载 @工作区双向同步设计 并发送修改指令（仅引用模式下模型仍可调用写提案工具）
                        onNodeWithTag("agent-composer-attach-button").performClick()
                        waitForTag("agent-context-picker-popup")
                        onNodeWithTag("agent-picker-note-${targetNoteId.value}").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-context-picker-popup").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        val baselineRevObj = runBlocking {
                            checkNotNull(checkNotNull(device.gateway.reload()).notes.single { it.id == targetNoteId }.revision)
                        }
                        initialRevision = "${baselineRevObj.actorId.value}:${baselineRevObj.sequence}"
                        provider.latestRevision = initialRevision

                        onNodeWithTag("agent-composer-input").performTextReplacement("请把状态更新为双端已验收，并补充同步合并结论")
                        onNodeWithTag("agent-composer-send-button").performClick()

                        // 等待第一轮流式提案生成完成
                        waitUntil(timeoutMillis = 20_000) {
                            findFirstTagWithPrefix("agent-proposal-review-right-") != null &&
                                onAllNodesWithTag("agent-composer-stop-button").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()

                        // 校验内置 Harness 仅向模型暴露 14 只读 + 3 写提案工具，严禁暴露 delete_note 与 export_note
                        val lastRequest = provider.capturedRequests.first()
                        assertTrue(lastRequest.contains("\"patch_note_blocks\""))
                        assertTrue(lastRequest.contains("\"update_note_metadata\""))
                        assertTrue(lastRequest.contains("\"create_note\""))
                        assertFalse(lastRequest.contains("\"delete_note\""), "内置 Harness 严禁暴露 delete_note")
                        assertFalse(lastRequest.contains("\"export_note\""), "内置 Harness 严禁暴露 export_note")

                        // 未确认前磁盘上的笔记修订号绝不能变化
                        val beforeConfirmReload = runBlocking { checkNotNull(device.gateway.reload()) }
                        val untouchedNoteOnDisk = beforeConfirmReload.notes.single { it.id == targetNoteId }
                        val untouchedRev = checkNotNull(untouchedNoteOnDisk.revision)
                        assertEquals(initialRevision, "${untouchedRev.actorId.value}:${untouchedRev.sequence}")

                        val reviewRightTag = checkNotNull(findFirstTagWithPrefix("agent-proposal-review-right-"))
                        val firstProposalId = reviewRightTag.removePrefix("agent-proposal-review-right-")

                        // 点击「在右侧对照审阅 Diff」-> 右侧滑出 AgentHubRightDiffPane，左侧对话保持不变
                        onNodeWithTag(reviewRightTag).performClick()
                        waitForTag("agent-hub-right-diff-pane")
                        waitForTag("agent-shared-diff-canvas")
                        onNodeWithTag("agent-diff-untouched-blk-sync-1").assertIsDisplayed()
                        onNode(hasText("原位对照审阅 · 已选采纳 3 / 3 处变更", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage3-wide-hub-right-diff.png")

                        // 将第 3 个变更项（insert_after_block）切换为「跳过」
                        val skipButtons = collectAllTagsWithPrefix("agent-diff-skip-")
                        assertEquals(3, skipButtons.size)
                        onNodeWithTag(skipButtons[2]).performClick()
                        waitForIdle()
                        onNode(hasText("原位对照审阅 · 已选采纳 2 / 3 处变更", substring = true)).assertIsDisplayed()
                        onNode(hasText("应用所选变更 (2)", substring = true)).assertIsDisplayed()

                        // 点击右上角「在笔记页打开」-> 无损穿透到左侧编辑页原位 Diff + 右侧 AgentCompanionPane，且已跳过项 100% 保留！
                        onNodeWithTag("agent-diff-open-in-note-button").performClick()
                        waitForTag("editor-inplace-diff-container")
                        waitForTag("agent-companion-pane")
                        onNode(hasText("原位对照审阅 · 已选采纳 2 / 3 处变更", substring = true)).assertIsDisplayed()
                        onNode(hasText("应用所选变更 (2)", substring = true)).assertIsDisplayed()
                        saveScreenshot("stage3-wide-editor-left-diff.png")

                        // 记录确认前的权威 manifest 修订号，点击「应用所选变更 (2)」，验证单次 TransactionCoordinator 原子提交 Note + AgentThread
                        val manifestRevBeforeCommit = checkNotNull(device.manager.sessionOrNull()).unlocked.payload.revision

                        onNodeWithTag("agent-diff-apply-button").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithTag("editor-inplace-diff-container").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()

                        val manifestRevAfterCommit = checkNotNull(device.manager.sessionOrNull()).unlocked.payload.revision
                        assertEquals(
                            manifestRevBeforeCommit + 1L,
                            manifestRevAfterCommit,
                            "更新 Note 快照与标记 AgentThread 提案为 APPLIED 必须在单次原子事务确认点中完成",
                        )

                        val afterCommit = runBlocking {
                            checkNotNull(device.gateway.reload()) to checkNotNull(device.gateway.agentHarness).listThreads()
                        }
                        val committedNote = afterCommit.first.notes.single { it.id == targetNoteId }
                        val committedRev = checkNotNull(committedNote.revision)
                        val newRevAfterFirstApply = "${committedRev.actorId.value}:${committedRev.sequence}"
                        assertEquals("opt-done", committedNote.note.propertyValues[PropertyId("prop-status")]?.value?.jsonPrimitive?.content)
                        val activeBlockIds = committedNote.note.blocks.filterNot { it.deleted }.sortedBy { it.orderKey }.map { it.id.value }
                        assertEquals(
                            listOf("blk-sync-1", "blk-sync-2", "blk-sync-code"),
                            activeBlockIds,
                            "未触碰块与原位替换块必须保留稳定 BlockId，且被跳过的后插块严禁写入",
                        )
                        val persistedProposal = afterCommit.second.single { it.id == threadId }.thread.proposals.single { it.id == firstProposalId }
                        assertEquals(AgentProposalStatus.APPLIED, persistedProposal.status)
                        assertEquals(newRevAfterFirstApply, persistedProposal.appliedRevision)
                        saveScreenshot("stage3-wide-after-atomic-commit.png")

                        // 发起第二轮提问生成第 2 条提案，用于验证多视口自适应与乐观锁冲突重算
                        provider.latestRevision = newRevAfterFirstApply
                        onNodeWithTag("agent-composer-attach-button").performClick()
                        waitForTag("agent-context-picker-popup")
                        onNodeWithTag("agent-picker-note-${targetNoteId.value}").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-context-picker-popup").fetchSemanticsNodes().isEmpty()
                        }
                        onNodeWithTag("agent-composer-input").performTextReplacement("再追加一段性能压测结论")
                        onNodeWithTag("agent-composer-send-button").performClick()

                        waitUntil(timeoutMillis = 20_000) {
                            findFirstTagWithPrefix("agent-proposal-review-left-") != null &&
                                onAllNodesWithTag("agent-composer-stop-button").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        secondProposalId = checkNotNull(findFirstTagWithPrefix("agent-proposal-review-left-"))
                            .removePrefix("agent-proposal-review-left-")
                    }

                    runBlocking { device.gateway.lock() }

                    // 2. 中屏 960x800：验证全屏工作台打开右侧 Diff 审阅面板时，左侧工作区导航栏自动收起并露出 [☰] 切换按钮
                    runDesktopComposeUiTest(width = 960, height = 800) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")
                        onNodeWithTag("sidebar-thread-${threadId.value}").performClick()
                        waitForTag("agent-workspace-screen")
                        waitForTag("agent-proposal-review-right-$secondProposalId")

                        onNodeWithTag("agent-proposal-review-right-$secondProposalId").performClick()
                        waitForTag("agent-hub-right-diff-pane")
                        waitForTag("agent-fullscreen-menu-button")
                        assertTrue(
                            onAllNodesWithTag("workspace-sidebar").fetchSemanticsNodes().isEmpty(),
                            "中屏打开全屏工作台右侧 Diff 审阅面板时应自动收起左侧导航栏",
                        )
                        saveScreenshot("stage3-medium-hub-right-diff.png")
                    }

                    runBlocking { device.gateway.lock() }

                    // 3. 窄屏 430x840：验证窄屏在全屏工作台点击审阅 Diff 时打开全屏审阅浮层并支持「← 返回会话」
                    runDesktopComposeUiTest(width = 430, height = 840) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("compact-open-sidebar-button")
                        onNodeWithTag("compact-open-sidebar-button").performClick()
                        waitForTag("sidebar-thread-${threadId.value}")
                        onNodeWithTag("sidebar-thread-${threadId.value}").performClick()
                        waitForTag("agent-workspace-screen")
                        waitForTag("agent-proposal-review-right-$secondProposalId")

                        onNodeWithTag("agent-proposal-review-right-$secondProposalId").performClick()
                        waitForTag("agent-hub-compact-diff-overlay")
                        onNodeWithTag("agent-diff-compact-back-button").assertIsDisplayed()
                        saveScreenshot("stage3-compact-diff-overlay.png")
                        onNodeWithTag("agent-diff-compact-back-button").performClick()
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("agent-hub-compact-diff-overlay").fetchSemanticsNodes().isEmpty()
                        }
                    }

                    runBlocking {
                        // 在重新锁定前推进一次目标笔记的权威 revision（模拟外部或早先手动改稿落盘）
                        val current = checkNotNull(device.gateway.reload()).notes.single { it.id == targetNoteId }
                        device.gateway.saveNote(
                            objectId = current.id,
                            note = current.note.copy(
                                blocks = current.note.blocks.map { blk ->
                                    if (blk.id == BlockId("blk-sync-1")) {
                                        blk.copy(content = RichTextBlockContent(RichText(listOf(InlineRun("首段加密协议不变（用户已手动微调）")))))
                                    } else blk
                                },
                            ),
                            expectedRevision = current.revision,
                        )
                        device.gateway.lock()
                    }

                    // 4. 乐观锁冲突拦截与「基于最新内容重算提案」恢复验收：打开审阅验证冲突拦截横幅并重算提交
                    runDesktopComposeUiTest(width = 1360, height = 860) {
                        setContent { App(capabilities = device.capabilities) }
                        waitForTag("workspace-sidebar")

                        onNodeWithTag("editor-companion-toggle").performClick()
                        waitForTag("agent-companion-pane")
                        waitForTag("agent-proposal-review-left-$secondProposalId")
                        onNodeWithTag("agent-proposal-review-left-$secondProposalId").performClick()
                        waitForTag("editor-inplace-diff-container")
                        waitForTag("agent-diff-banner-conflict")
                        onNodeWithTag("agent-diff-apply-button").assertIsNotEnabled()
                        onNodeWithTag("agent-diff-recalc-button").assertIsDisplayed()
                        onNodeWithTag("agent-diff-copy-button").assertIsDisplayed()
                        saveScreenshot("stage3-wide-revision-conflict.png")

                        // 点击「基于最新内容重算提案」-> 冲突解除，随后点击「应用所选变更」成功原子提交！
                        onNodeWithTag("agent-diff-recalc-button").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithTag("agent-diff-banner-conflict").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                        onNodeWithTag("agent-diff-apply-button").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithTag("editor-inplace-diff-container").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()

                        val finalNote = runBlocking { checkNotNull(device.gateway.reload()).notes.single { it.id == targetNoteId } }
                        assertEquals(4, finalNote.note.blocks.count { !it.deleted })
                    }
                }
            } finally {
                Locale.setDefault(previousLocale)
            }
        }
    }

    /** 预置笔记类型、状态单选属性、带代码块的目标笔记与启用工具能力的模型及初始会话。 */
    private suspend fun seedStage3Workspace(
        device: ServerRecoveryTestDevice,
        baseUrl: String,
    ): Triple<ObjectId, String, ObjectId> {
        device.gateway.agentConfig.saveConnection(
            WorkspaceConnectionSaveDraft(
                connectionId = "conn-stage3",
                name = "DeepSeek Stage3 Mock",
                baseUrl = baseUrl,
                protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                authMode = AiAuthMode.KEYSTORE,
                syncKeyInEnvelope = true,
                newApiKeyInput = "sk-stage3-test",
            ),
        )
        device.gateway.agentConfig.saveModel(
            AiModelConfig(
                connectionId = "conn-stage3",
                modelId = "deepseek-chat",
                displayName = "DeepSeek V3.2",
                contextWindowTokens = 128_000,
                toolsState = AiModelToolsState.VERIFIED,
                supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
            ),
        )

        val statusPropId = PropertyId("prop-status")
        val specTagId = TagId("type-spec")
        device.gateway.savePropertyDefinition(
            PropertyDefinition(
                statusPropId, "状态", PropertyTypes.SELECT,
                options = listOf(PropertyOption("opt-review", "评审中"), PropertyOption("opt-done", "双端已验收")),
            ),
        )
        device.gateway.saveTagDefinition(
            TagDefinition(
                specTagId, "技术方案",
                propertyBindings = listOf(TagPropertyBinding(statusPropId, defaultValue = JsonPrimitive("opt-review"), defaultPolicy = DefaultValuePolicy.SNAPSHOT)),
            ),
        )
        val note = device.gateway.saveNote(
            null,
            Note(
                title = "工作区双向同步设计",
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, specTagId),
                propertyValues = mapOf(
                    statusPropId to NotePropertyValue(JsonPrimitive("opt-review"), PropertyValueSource.USER, overridden = true, updatedAt = "2026-11-14T00:00:00Z"),
                ),
                blocks = listOf(
                    Block(
                        id = BlockId("blk-sync-1"), type = BlockTypes.PARAGRAPH, orderKey = "0000000000",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("客户端服务器模式在已解锁会话中自动读取远端确认点，以对象 Change DAG 判断因果关系。")))),
                    ),
                    Block(
                        id = BlockId("blk-sync-2"), type = BlockTypes.PARAGRAPH, orderKey = "0000000001",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("Note 使用真实共同祖先执行三方合并；无法自动处理的重叠块保留双版本证据。")))),
                    ),
                    Block(
                        id = BlockId("blk-sync-code"), type = BlockTypes.CODE, orderKey = "0000000002",
                        content = CodeBlockContent(language = "kotlin", text = "fun mergeThreeWay(base: Note, ours: Note, theirs: Note): Note"),
                    ),
                ),
            ),
        )
        val seededRev = checkNotNull(note.revision)
        val rev = "${seededRev.actorId.value}:${seededRev.sequence}"
        val thread = requireNotNull(device.gateway.agentHarness).createThread(incognito = false, initialTitle = "同步验收获批与快照对齐")
        return Triple(note.id, rev, thread.id)
    }

    /** 查找语义树中首个匹配前缀的 `testTag`。 */
    private fun ComposeUiTest.findFirstTagWithPrefix(prefix: String): String? =
        collectAllTagsWithPrefix(prefix).firstOrNull()

    /** 按屏幕从上到下顺序提取匹配前缀的全部 `testTag`。 */
    private fun ComposeUiTest.collectAllTagsWithPrefix(prefix: String): List<String> =
        onAllNodes(isRoot())[0].fetchSemanticsNode().let { root ->
            collectTaggedNodes(root, prefix).sortedBy { it.second }.map { it.first }
        }

    /** 递归遍历语义树节点收集前缀匹配的 `testTag` 与垂直坐标。 */
    private fun collectTaggedNodes(node: SemanticsNode, prefix: String): List<Pair<String, Float>> {
        val result = mutableListOf<Pair<String, Float>>()
        val tag = node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
        if (tag != null && tag.startsWith(prefix)) {
            result += tag to node.boundsInRoot.top
        }
        node.children.forEach { child -> result += collectTaggedNodes(child, prefix) }
        return result
    }

    /** 等待目标 `testTag` 出现，超时则保存现场截图后抛出断言。 */
    private fun ComposeUiTest.waitForTag(tag: String) {
        try {
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val rootsCount = runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(0)
            if (rootsCount == 1) {
                runCatching { saveScreenshot("stage3-failure-$tag.png") }
            }
            throw AssertionError("Timeout waiting for tag '$tag' (roots=$rootsCount)", error)
        }
    }

    /** 将当前真实 Skia 渲染帧保存为 PNG 截图供视觉验收审查。 */
    private fun ComposeUiTest.saveScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "stage3-agent-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                Files.write(directory.resolve(filename), encoded.bytes)
            }
        }
    }
}

/** 为阶段三 UI 测试提供两轮真实工具调用流式响应的本机 SSE 回环模拟服务。 */
private class UiStage3LoopbackProvider : AutoCloseable {
    /** 记录已收到的原始 HTTP 请求体。 */
    val capturedRequests = CopyOnWriteArrayList<String>()
    /** 当前用户提问轮次计数。 */
    private val turnIndex = AtomicInteger(0)
    /** 当前测试目标笔记 ID。 */
    @Volatile var targetNoteId: String = ""
    /** 当前模拟服务返回的乐观锁期望修订号。 */
    @Volatile var latestRevision: String = ""
    /** HTTP 线程池。 */
    private val executor = Executors.newCachedThreadPool()
    /** 本机随机端口回环 HTTP 服务。 */
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@UiStage3LoopbackProvider.executor
        createContext("/v1/chat/completions") { handleChat(it) }
        start()
    }
    /** 供 `AiConnectionConfig` 绑定的 `/v1` 基础地址。 */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** 返回两轮工具调用流式提案与最终回答 SSE 帧。 */
    private fun handleChat(exchange: HttpExchange) {
        val req = exchange.requestBody.readBytes().decodeToString()
        capturedRequests += req
        val endsWithToolResult = req.lastIndexOf("\"role\":\"tool\"") > req.lastIndexOf("\"role\":\"user\"")
        val contextRev = Regex("""revision=([A-Za-z0-9_.:-]+)\)""").findAll(req).lastOrNull()?.groupValues?.get(1) ?: latestRevision
        val sse = if (!endsWithToolResult) {
            if (turnIndex.incrementAndGet() == 1) {
                val metaArgs = """{\"noteId\":\"$targetNoteId\",\"expectedRevision\":\"$contextRev\",\"propertyUpdates\":{\"prop-status\":\"opt-done\"}}"""
                val blockArgs = """{\"noteId\":\"$targetNoteId\",\"expectedRevision\":\"$contextRev\",\"operations\":[{\"op\":\"replace_block\",\"blockId\":\"blk-sync-2\",\"markdown\":\"Note 使用真实共同祖先执行三方合并；已完成 Windows 桌面与 Android 双端 19 阶段现场验收。\"},{\"op\":\"insert_after_block\",\"blockId\":\"blk-sync-2\",\"markdown\":\"可选补充：支持按段落恢复孤立加注。\"}]}"""
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_meta_1\",\"type\":\"function\",\"function\":{\"name\":\"update_note_metadata\",\"arguments\":\"$metaArgs\"}},{\"index\":1,\"id\":\"call_blk_1\",\"type\":\"function\",\"function\":{\"name\":\"patch_note_blocks\",\"arguments\":\"$blockArgs\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":320,\"completion_tokens\":65}}\n\ndata: [DONE]\n\n"
            } else {
                val appendArgs = """{\"noteId\":\"$targetNoteId\",\"expectedRevision\":\"$contextRev\",\"operations\":[{\"op\":\"append_blocks\",\"markdown\":\"新增性能压测结论：单事务落盘延迟低于 15ms。\"}]}"""
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_blk_2\",\"type\":\"function\",\"function\":{\"name\":\"patch_note_blocks\",\"arguments\":\"$appendArgs\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":380,\"completion_tokens\":40}}\n\ndata: [DONE]\n\n"
            }
        } else {
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"已生成细粒度提案供审阅\",\"content\":\"我已结合《工作区双向同步设计》 [1] 准备好细粒度修改提案（未触碰代码块 `blk-sync-code` 原样保留），请逐项对照采纳：\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":410,\"completion_tokens\":52}}\n\ndata: [DONE]\n\n"
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, sse.size.toLong())
        exchange.responseBody.use { it.write(sse) }
    }

    /** 关闭回环服务与线程池。 */
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
