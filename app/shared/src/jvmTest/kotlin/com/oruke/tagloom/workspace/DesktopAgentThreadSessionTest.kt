package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证 `AGENT_THREAD` 双层加密快照持久化、低频提交纪律、主密钥轮换重包裹、无痕模式零落盘、
 * 工作区锁定即时流取消以及双端双向加密三方合并。
 */
class DesktopAgentThreadSessionTest {
    /** 验证流式生成中间增量零磁盘写入、单轮完成单次加密落盘、主密钥轮换与双端并发对话合并。 */
    @Test
    fun persistsEncryptedThreadLowFrequencyRotatesKeysAndMergesAcrossDevices() =
        Stage2MockSseServer().use { mockAi ->
            ServerRecoveryTestEnvironment().use { env ->
                runBlocking {
                    val peerA = env.device("thread-peer-a")
                    val initialRecoveryKey = requireNotNull(peerA.gateway.prepareCreation(null)).recoveryKeyText
                    peerA.gateway.confirmCreation()

                    configureSyncedModel(peerA, mockAi.baseUrl)
                    val harnessA = requireNotNull(peerA.gateway.agentHarness)

                    // 保存可读笔记与禁读笔记，验证进程内只读工具仅暴露 ALLOW
                    val readable = peerA.gateway.saveNote(
                        null,
                        Note(
                            title = "架构加密说明",
                            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                            blocks = listOf(
                                Block(
                                    id = BlockId("blk-arch-1"),
                                    type = BlockTypes.PARAGRAPH,
                                    orderKey = "a",
                                    content = RichTextBlockContent(RichText(listOf(InlineRun("会话快照采用双层信封保护")))),
                                ),
                            ),
                        ),
                    )
                    peerA.gateway.saveNote(
                        null,
                        Note(
                            title = "私密恢复助记",
                            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                            aiAccessPolicy = AiAccessPolicy.LOCKED,
                            blocks = listOf(
                                Block(
                                    id = BlockId("blk-secret-1"),
                                    type = BlockTypes.PARAGRAPH,
                                    orderKey = "a",
                                    content = RichTextBlockContent(RichText(listOf(InlineRun("绝不能出现在工具搜索结果中")))),
                                ),
                            ),
                        ),
                    )

                    val created = harnessA.createThread(
                        incognito = false,
                        retrievalScope = AiRetrievalScope.ALL_NOTES,
                    )
                    val threadFile = peerA.root.resolve("workspace/threads/${created.id.value}.thread.enc")
                    assertTrue(Files.exists(threadFile), "持久化会话创建后应生成加密快照文件")
                    val mtimeBeforeTurn = Files.getLastModifiedTime(threadFile).toMillis()

                    mockAi.pauseMidStream = true
                    val turnJob = async(Dispatchers.Default) {
                        harnessA.runTurn(
                            threadId = created.id,
                            userPrompt = "请总结架构加密说明",
                            contextRefs = emptyList(),
                            selectedModelId = null,
                            thinkingEffort = AiThinkingEffort.MEDIUM,
                            retrievalScope = AiRetrievalScope.ALL_NOTES,
                        )
                    }
                    assertTrue(mockAi.enteredMidStreamLatch.await(10, TimeUnit.SECONDS))
                    // 流式增量到达期间严禁写盘更新快照文件
                    assertEquals(mtimeBeforeTurn, Files.getLastModifiedTime(threadFile).toMillis())
                    mockAi.releaseMidStreamLatch.countDown()

                    val completed = turnJob.await()
                    assertEquals(2, completed.entry.thread.messages.size)
                    assertEquals(1, completed.output.assistantMessage.sources.size)
                    assertEquals(readable.id, completed.output.assistantMessage.sources.single().noteId)
                    assertFalse(mockAi.lastToolResultPayload.contains("私密恢复助记"))

                    // 磁盘密文零明文验证
                    assertNoPlaintextInWorkspace(
                        peerA.root.resolve("workspace"),
                        listOf("请总结架构加密说明", "双层信封保护", "根据检索到的笔记"),
                    )

                    // 主密钥轮换重包裹后完整可读
                    val rotated = peerA.gateway.rotateMasterKey(null)
                    val afterRotate = requireNotNull(peerA.gateway.agentHarness).listThreads().single()
                    assertEquals(2, afterRotate.thread.messages.size)
                    assertTrue(afterRotate.thread.cumulativeUsage.totalTokens > 0)

                    // 发布到服务器并在第二台设备恢复，执行双端并发追问并合并
                    peerA.gateway.serverRecovery.publish(env.address, env.bootstrap, rotated.recoveryKeyText)
                    val peerB = env.device("thread-peer-b")
                    checkNotNull(peerB.gateway.serverRecovery.restore(env.address, rotated.recoveryKeyText, null))
                    val harnessB = requireNotNull(peerB.gateway.agentHarness)
                    assertEquals(1, harnessB.listThreads().size)

                    mockAi.pauseMidStream = false
                    harnessA.runTurn(
                        threadId = created.id,
                        userPrompt = "桌面端补充追问",
                        contextRefs = emptyList(),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    peerA.gateway.serverRecovery.publish(env.address, "", "")

                    harnessB.runTurn(
                        threadId = created.id,
                        userPrompt = "移动端独立追问",
                        contextRefs = emptyList(),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    peerB.gateway.serverRecovery.publish(env.address, "", "")
                    peerA.gateway.serverRecovery.updateIfChanged()

                    val mergedOnB = harnessB.listThreads().single { it.id == created.id }.thread
                    assertEquals(6, mergedOnB.messages.size, "双端并发各发一轮问答（各2条）合并后应无损保留全部 6 条消息")
                    assertEquals(mergedOnB.recomputeCumulativeUsage(), mergedOnB.cumulativeUsage)
                    assertTrue(initialRecoveryKey.isNotBlank())
                }
            }
        }

    /** 验证无痕模式零磁盘落盘以及工作区锁定时立即中断在途 SSE 流并销毁无痕内存。 */
    @Test
    fun keepsIncognitoStrictlyInMemoryAndCancelsActiveStreamOnWorkspaceLock() =
        Stage2MockSseServer().use { mockAi ->
            ServerRecoveryTestEnvironment().use { env ->
                runBlocking {
                    val device = env.device("incognito-lock")
                    checkNotNull(device.gateway.prepareCreation(null))
                    device.gateway.confirmCreation()
                    configureSyncedModel(device, mockAi.baseUrl)

                    val harness = requireNotNull(device.gateway.agentHarness)
                    val incognito = harness.createThread(incognito = true)
                    assertTrue(incognito.incognito)

                    mockAi.pauseMidStream = false
                    val incTurn = harness.runTurn(
                        threadId = incognito.id,
                        userPrompt = "无痕敏感提问内容",
                        contextRefs = emptyList(),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    assertEquals(2, incTurn.entry.thread.messages.size)
                    val incognitoPath = device.root.resolve("workspace/threads/${incognito.id.value}.thread.enc")
                    assertFalse(Files.exists(incognitoPath), "无痕会话严禁写入磁盘文件")

                    // 启动一轮挂起中的流式请求，验证锁定工作区立即取消连接且销毁内存中的无痕会话
                    val persistent = harness.createThread(incognito = false)
                    mockAi.resetLatches()
                    mockAi.pauseMidStream = true
                    val blockedTurn = async(Dispatchers.Default) {
                        harness.runTurn(
                            threadId = persistent.id,
                            userPrompt = "锁定时正在生成的提问",
                            contextRefs = emptyList(),
                            selectedModelId = null,
                            thinkingEffort = AiThinkingEffort.OFF,
                            retrievalScope = AiRetrievalScope.CITED_ONLY,
                        )
                    }
                    assertTrue(mockAi.enteredMidStreamLatch.await(10, TimeUnit.SECONDS))

                    delay(50)
                    device.gateway.lock()
                    assertFailsWith<CancellationException> { blockedTurn.await() }
                    mockAi.releaseMidStreamLatch.countDown()

                    device.gateway.openRecentWorkspace()
                    val reopenedThreads = requireNotNull(device.gateway.agentHarness).listThreads()
                    assertFalse(reopenedThreads.any { it.id == incognito.id }, "无痕会话在锁定重开后必须已彻底销毁")
                    val reopenedPersistent = reopenedThreads.firstOrNull { it.id == persistent.id }
                    assertNotNull(reopenedPersistent)
                    assertTrue(reopenedPersistent.thread.messages.isEmpty(), "因锁定中断的未完成轮次不应落盘残缺记录")
                }
            }
        }

    /** 为测试设备写入一条可同步的模拟模型连接。 */
    private suspend fun configureSyncedModel(device: ServerRecoveryTestDevice, baseUrl: String) {
        device.gateway.agentConfig.saveConnection(
            WorkspaceConnectionSaveDraft(
                connectionId = "conn-mock",
                name = "Mock AI",
                baseUrl = baseUrl,
                protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                authMode = AiAuthMode.KEYSTORE,
                syncKeyInEnvelope = true,
                newApiKeyInput = "sk-stage2-session-key",
            ),
        )
        device.gateway.agentConfig.saveModel(
            AiModelConfig(
                connectionId = "conn-mock",
                modelId = "deepseek-chat",
                displayName = "DeepSeek V3.2",
                contextWindowTokens = 128_000,
                toolsState = AiModelToolsState.VERIFIED,
                supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
            ),
        )
    }

    /** 断言工作区密文目录中不包含任何给定的明文敏感短语。 */
    private fun assertNoPlaintextInWorkspace(workspaceDir: Path, forbiddenPhrases: List<String>) {
        Files.walk(workspaceDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".lock") }.forEach { file ->
                val text = Files.readAllBytes(file).decodeToString()
                forbiddenPhrases.forEach { phrase ->
                    assertFalse(text.contains(phrase), "文件 ${file.fileName} 不应包含明文片段")
                }
            }
        }
    }
}

/**
 * 支持工具调用、流中挂起信号量与来源角标输出的本地模拟 SSE 服务端。
 */
private class Stage2MockSseServer : AutoCloseable {
    @Volatile
    var pauseMidStream: Boolean = false

    @Volatile
    var lastToolResultPayload: String = ""

    @Volatile
    var enteredMidStreamLatch = CountDownLatch(1)

    @Volatile
    var releaseMidStreamLatch = CountDownLatch(1)

    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@Stage2MockSseServer.executor
        createContext("/v1/chat/completions") { handleChat(it) }
        start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}/v1"

    /** 重置流中挂起同步门闩。 */
    fun resetLatches() {
        enteredMidStreamLatch = CountDownLatch(1)
        releaseMidStreamLatch = CountDownLatch(1)
    }

    private fun handleChat(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        val hasToolResult = body.contains("\"role\":\"tool\"")
        val wantsTools = body.contains("\"tools\":[")
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        runCatching {
            exchange.responseBody.use { out ->
                if (wantsTools && !hasToolResult) {
                    val toolChunk = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_s1\",\"type\":\"function\",\"function\":{\"name\":\"search_notes\",\"arguments\":\"{\\\"query\\\":\\\"架构\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                    val doneChunk = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":20}}\n\ndata: [DONE]\n\n"
                    out.write(toolChunk.encodeToByteArray())
                    out.write(doneChunk.encodeToByteArray())
                    out.flush()
                } else {
                    if (hasToolResult) lastToolResultPayload = body
                    val firstDelta = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"正在综合分析\",\"content\":\"根据检索到的笔记 [1]\"}}]}\n\n"
                    out.write(firstDelta.encodeToByteArray())
                    out.flush()
                    if (pauseMidStream) {
                        enteredMidStreamLatch.countDown()
                        releaseMidStreamLatch.await(15, TimeUnit.SECONDS)
                    }
                    val rest = "data: {\"choices\":[{\"delta\":{\"content\":\"，未登记引用 [99] 保持纯文本。\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":180,\"completion_tokens\":35,\"prompt_tokens_details\":{\"cached_tokens\":60}}}\n\ndata: [DONE]\n\n"
                    out.write(rest.encodeToByteArray())
                    out.flush()
                }
            }
        }
    }

    override fun close() {
        releaseMidStreamLatch.countDown()
        server.stop(0)
        executor.shutdownNow()
    }
}
