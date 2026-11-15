package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentContextRefKind
import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiThinkingEffort
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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 验证阶段四双设备加密盲同步闭环：
 * 1. `WORKSPACE_CONFIG` 中模型 `contextWindowTokens` 与 `autoCompactPercent = 80` / `retainPercent = 16` 双端同步；
 * 2. 桌面端 `80%` 自动压缩与移动端离线手动压缩（`compactThread`）经盲服务器三方合并收敛；
 * 3. 压缩节点历史集合并集、活跃压缩游标因果推进、累计计费账单守恒、主密钥轮换与磁盘零明文。
 */
class Stage4CompactionReplicaSyncTest {
    /** 验证双端自动/手动上下文压缩、模型窗口配置同步、离线并发三方合并与密文零明文保护。 */
    @Test
    fun syncsModelWindowAutoCompactionAndMergesConcurrentManualCompactionAcrossDevices() =
        Stage4SyncMockServer().use { mockAi ->
            ServerRecoveryTestEnvironment().use { env ->
                runBlocking {
                    val peerA = env.device("stage4-peer-a")
                    val initialRecoveryKey = requireNotNull(peerA.gateway.prepareCreation(null)).recoveryKeyText
                    peerA.gateway.confirmCreation()

                    // 在设备 A 配置 16,000 Token 窗口模型与 80% 自动压缩偏好
                    peerA.gateway.agentConfig.saveConnection(
                        WorkspaceConnectionSaveDraft(
                            connectionId = "conn-stage4",
                            name = "Stage4 Sync Mock",
                            baseUrl = mockAi.baseUrl,
                            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                            authMode = AiAuthMode.KEYSTORE,
                            syncKeyInEnvelope = true,
                            newApiKeyInput = "sk-stage4-sync-secret",
                        ),
                    )
                    peerA.gateway.agentConfig.saveModel(
                        AiModelConfig(
                            connectionId = "conn-stage4",
                            modelId = "deepseek-chat",
                            displayName = "DeepSeek 16K Window",
                            contextWindowTokens = 16_000,
                            toolsState = AiModelToolsState.VERIFIED,
                            supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM),
                        ),
                    )
                    peerA.gateway.agentConfig.savePreferences(
                        AiAgentPreferences(
                            autoCompactEnabled = true,
                            autoCompactPercent = 80,
                            retainPercent = 16,
                        ),
                    )

                    val note = peerA.gateway.saveNote(
                        null,
                        Note(
                            title = "跨端压缩一致性规范",
                            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                            blocks = listOf(
                                Block(
                                    id = BlockId("blk-spec-1"),
                                    type = BlockTypes.PARAGRAPH,
                                    orderKey = "0000000000",
                                    content = RichTextBlockContent(
                                        RichText(listOf(InlineRun("压缩游标基于稳定消息 ID 推进，底层加密消息数组严禁破坏性删除。"))),
                                    ),
                                ),
                            ),
                        ),
                    )

                    val harnessA = requireNotNull(peerA.gateway.agentHarness)
                    val created = harnessA.createThread(
                        incognito = false,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                        initialTitle = "双端上下文压缩验证",
                    )
                    val blockRef = AgentContextRef(
                        id = "ref-spec-blk",
                        kind = AgentContextRefKind.BLOCKS,
                        noteId = note.id,
                        blockIds = listOf(BlockId("blk-spec-1")),
                        label = "跨端压缩一致性规范 · 1 段",
                    )

                    // 第 1 轮处于安全水位（prompt_tokens = 5,200 < 12,800）
                    mockAi.nextPromptTokens = 5_200
                    val turn1 = harnessA.runTurn(
                        threadId = created.id,
                        userPrompt = "第一轮核对段落契约锚点",
                        contextRefs = listOf(blockRef),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    assertEquals(0, turn1.entry.thread.compactions.size)

                    // 第 2 轮越过 80% 阈值（prompt_tokens = 13,600 >= 12,800），自动触发两阶段上下文压缩
                    mockAi.nextPromptTokens = 13_600
                    val turn2 = harnessA.runTurn(
                        threadId = created.id,
                        userPrompt = "第二轮补充多副本收敛验证细节",
                        contextRefs = listOf(blockRef),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    assertNotNull(turn2.output.triggeredCompaction, "越过 80% 水位后应自动写入 CompactionItem")
                    assertEquals(1, turn2.entry.thread.compactions.size)
                    assertEquals(80, turn2.entry.thread.compactions.single().triggerPercent)
                    assertEquals(listOf(blockRef), turn2.entry.thread.activeContextRefs)

                    // 发布到盲同步服务器并在设备 B 恢复，验证模型窗口配置与自动压缩节点完整同步
                    peerA.gateway.serverRecovery.publish(env.address, env.bootstrap, initialRecoveryKey)
                    val peerB = env.device("stage4-peer-b")
                    checkNotNull(peerB.gateway.serverRecovery.restore(env.address, initialRecoveryKey, null))

                    val syncedConfigOnB = peerB.gateway.agentConfig.load().config
                    assertEquals(16_000, syncedConfigOnB.models.single().contextWindowTokens)
                    assertEquals(80, syncedConfigOnB.preferences.autoCompactPercent)
                    assertEquals(16, syncedConfigOnB.preferences.retainPercent)

                    val harnessB = requireNotNull(peerB.gateway.agentHarness)
                    val restoredThreadB = harnessB.listThreads().single { it.id == created.id }.thread
                    assertEquals(1, restoredThreadB.compactions.size)
                    assertEquals(4, restoredThreadB.messages.size)

                    // 制造离线双端并发：设备 A 继续正常追问 1 轮，设备 B 追问 1 轮后点击手动「立即压缩」（triggerPercent = 0）
                    mockAi.nextPromptTokens = 2_400
                    harnessA.runTurn(
                        threadId = created.id,
                        userPrompt = "桌面端离线第三轮追问",
                        contextRefs = listOf(blockRef),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    peerA.gateway.serverRecovery.publish(env.address, "", "")

                    harnessB.runTurn(
                        threadId = created.id,
                        userPrompt = "移动端离线独立追问并手动整理上下文",
                        contextRefs = listOf(blockRef),
                        selectedModelId = null,
                        thinkingEffort = AiThinkingEffort.OFF,
                        retrievalScope = AiRetrievalScope.CITED_ONLY,
                    )
                    val manuallyCompactedB = harnessB.compactThread(
                        threadId = created.id,
                        activeContextRefs = listOf(blockRef),
                    )
                    assertEquals(2, manuallyCompactedB.thread.compactions.size)
                    assertEquals(0, manuallyCompactedB.thread.compactions.last().triggerPercent)

                    // 双向同步三方合并收敛
                    peerB.gateway.serverRecovery.publish(env.address, "", "")
                    peerA.gateway.serverRecovery.updateIfChanged()

                    val finalThreadA = harnessA.listThreads().single { it.id == created.id }.thread
                    val finalThreadB = harnessB.listThreads().single { it.id == created.id }.thread
                    assertEquals(8, finalThreadA.messages.size, "双端离线并发全部 8 条加密消息必须无损合并保留")
                    assertEquals(2, finalThreadA.compactions.size, "自动压缩节点与手动压缩节点必须并集收敛")
                    assertEquals(finalThreadB.compactions.map { it.id }, finalThreadA.compactions.map { it.id })
                    assertEquals(finalThreadB.activeCompactionId, finalThreadA.activeCompactionId)
                    assertEquals(finalThreadA.recomputeCumulativeUsage(), finalThreadA.cumulativeUsage)

                    // 主密钥轮换与磁盘零明文校验
                    peerA.gateway.rotateMasterKey(null)
                    val afterRotate = requireNotNull(peerA.gateway.agentHarness).listThreads().single().thread
                    assertEquals(2, afterRotate.compactions.size)
                    assertNoPlaintextOnDisk(
                        peerA.root.resolve("workspace"),
                        listOf("双端上下文压缩验证", "跨端压缩一致性规范", "sk-stage4-sync-secret", "已浓缩早期轮次"),
                    )
                }
            }
        }

    /** 断言工作区加密目录内不残留任何敏感明文字符串。 */
    private fun assertNoPlaintextOnDisk(workspaceDir: Path, forbidden: List<String>) {
        Files.walk(workspaceDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".lock") }.forEach { file ->
                val raw = Files.readAllBytes(file).decodeToString()
                forbidden.forEach { token ->
                    assertFalse(raw.contains(token), "密文文件 ${file.fileName} 严禁包含明文片段: $token")
                }
            }
        }
    }
}

/** 为阶段四双端压缩同步测试提供可控输入 Token 水位与轻量浓缩摘要的回环 SSE 服务。 */
private class Stage4SyncMockServer : AutoCloseable {
    /** 下一轮普通对话返回的 `prompt_tokens` 计费值。 */
    @Volatile var nextPromptTokens: Int = 2_000
    /** 请求序号计数器。 */
    private val seq = AtomicInteger(0)
    /** 后台 HTTP 线程池。 */
    private val executor = Executors.newCachedThreadPool()
    /** 随机端口本机 HTTP 服务。 */
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@Stage4SyncMockServer.executor
        createContext("/v1/chat/completions") { handle(it) }
        start()
    }

    /** 供 `AiProviderConnection` 绑定的 `/v1` 地址。 */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** 按是否为压缩器系统提示词区分返回业务回答或轻量压缩纪要。 */
    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        val isCompactionCall = body.contains("Tagloom 会话上下文压缩器")
        val index = seq.incrementAndGet()
        val sse = if (isCompactionCall) {
            val summary = "阶段浓缩纪要 #$index：已归纳段落契约与同步确认点，修剪早期冗余上下文。"
            "data: {\"choices\":[{\"delta\":{\"content\":\"$summary\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":360,\"completion_tokens\":42,\"prompt_tokens_details\":{\"cached_tokens\":240}}}\n\ndata: [DONE]\n\n"
        } else {
            val promptTok = nextPromptTokens
            val cachedTok = (promptTok * 3) / 4
            val answer = "第 $index 次回复：已核验《跨端压缩一致性规范》 [1] 的加密边界。"
            "data: {\"choices\":[{\"delta\":{\"content\":\"$answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":$promptTok,\"completion_tokens\":58,\"prompt_tokens_details\":{\"cached_tokens\":$cachedTok}}}\n\ndata: [DONE]\n\n"
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, sse.size.toLong())
        exchange.responseBody.use { it.write(sse) }
    }

    /** 释放回环服务资源。 */
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
