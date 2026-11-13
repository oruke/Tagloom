package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceAgentCredentialStatus
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证 `WORKSPACE_CONFIG` 分层密钥隔离、主密钥轮换重包裹、双端无共同快照首次合并、
 * 冲突预览脱敏掩码以及工作区锁定即时中断在途流连接。
 */
class WorkspaceAgentConfigSecurityAndSyncTest {
    /**
     * 验证默认本机凭据隔离绝不进入工作区或备份密文，而显式开启信封同步的密钥在主密钥轮换后完整保留且落盘零明文。
     */
    @Test
    fun isolatesLocalKeyFromBackupsAndRewrapsSyncedEnvelopeKeyAcrossMasterKeyRotation() =
        ServerRecoveryTestEnvironment().use { env ->
            runBlocking {
                val device = env.device("key-isolation")
                val initialRecoveryKey = requireNotNull(device.gateway.prepareCreation(null)).recoveryKeyText
                device.gateway.confirmCreation()

                val localSecret = "sk-local-isolated-only-112233"
                val syncedSecret = "sk-envelope-synced-445566"

                val afterFirst = device.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-local",
                        name = "Local Isolated OpenAI",
                        baseUrl = "https://api.openai.com/v1",
                        protocol = AiProtocolKind.OPENAI_RESPONSES,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = false,
                        newApiKeyInput = localSecret,
                    ),
                )
                assertEquals(
                    WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE,
                    afterFirst.credentialStatuses["conn-local"],
                )

                val afterSecond = device.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-synced",
                        name = "Synced Team Endpoint",
                        baseUrl = "https://api.deepseek.com/v1",
                        protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = true,
                        newApiKeyInput = syncedSecret,
                    ),
                )
                assertEquals(
                    WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE,
                    afterSecond.credentialStatuses["conn-synced"],
                )
                afterSecond.config.connections.forEach { conn ->
                    assertNull(conn.syncedApiKey, "UI 快照中 syncedApiKey 必须始终为 null")
                }

                val rawBeforeRotation = requireNotNull(device.manager.sessionOrNull()?.loadWorkspaceConfig()).payload
                assertNull(rawBeforeRotation.connections.single { it.connectionId == "conn-local" }.syncedApiKey)
                assertEquals(
                    syncedSecret,
                    rawBeforeRotation.connections.single { it.connectionId == "conn-synced" }.syncedApiKey,
                )

                assertTrue(device.gateway.createBackup())
                val backupZip = device.root.resolve("backup.zip")
                assertNoPlaintextSecretInDirectory(device.root, listOf(localSecret, syncedSecret))
                assertNoPlaintextSecretInZip(backupZip, listOf(localSecret, syncedSecret))

                val rotated = device.gateway.rotateMasterKey(null)
                assertTrue(rotated.recoveryKeyText != initialRecoveryKey)

                val loadedAfterRotation = device.gateway.agentConfig.load()
                assertEquals(
                    WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE,
                    loadedAfterRotation.credentialStatuses["conn-local"],
                )
                assertEquals(
                    WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE,
                    loadedAfterRotation.credentialStatuses["conn-synced"],
                )

                device.gateway.serverRecovery.publish(env.address, env.bootstrap, rotated.recoveryKeyText)
                val secondDevice = env.device("restored-peer")
                checkNotNull(secondDevice.gateway.serverRecovery.restore(env.address, rotated.recoveryKeyText, null))
                val peerSnapshot = secondDevice.gateway.agentConfig.load()
                assertEquals(
                    WorkspaceAgentCredentialStatus.MISSING_LOCAL_KEY,
                    peerSnapshot.credentialStatuses["conn-local"],
                    "未勾选信封同步的本机密钥绝不能随服务器同步泄露到第二台设备",
                )
                assertEquals(
                    WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE,
                    peerSnapshot.credentialStatuses["conn-synced"],
                    "勾选信封同步且经主密钥轮换重包裹的连接在新设备解锁后直接可用",
                )
            }
        }

    /**
     * 验证两台设备在全新工作区上各自独立首次创建 `WORKSPACE_CONFIG` 时能按隐式空基线无冲突三方合并，
     * 且同一连接同步密钥若发生并发冲突时，冲突审阅预览严格掩码为 `••••••••`。
     */
    @Test
    fun mergesIndependentSingletonCreationAndRedactsConflictingSyncedApiKeys() =
        ServerRecoveryTestEnvironment().use { env ->
            runBlocking {
                val first = env.device("peer-a")
                val recoveryKey = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
                first.gateway.confirmCreation()
                first.gateway.serverRecovery.publish(env.address, env.bootstrap, recoveryKey)

                val second = env.device("peer-b")
                checkNotNull(second.gateway.serverRecovery.restore(env.address, recoveryKey, null))

                first.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-openai",
                        name = "OpenAI Cloud",
                        baseUrl = "https://api.openai.com/v1",
                        protocol = AiProtocolKind.OPENAI_RESPONSES,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = false,
                        newApiKeyInput = "sk-peer-a-local-999",
                    ),
                )
                first.gateway.agentConfig.saveModel(
                    AiModelConfig(
                        connectionId = "conn-openai",
                        modelId = "gpt-4.1",
                        displayName = "GPT-4.1",
                        contextWindowTokens = 1_000_000,
                    ),
                )
                first.gateway.serverRecovery.publish(env.address, "", "")

                val sharedInitialKey = "sk-shared-initial-000"
                second.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-team",
                        name = "Team Shared vLLM",
                        baseUrl = "https://llm.example.com/v1",
                        protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = true,
                        newApiKeyInput = sharedInitialKey,
                    ),
                )
                second.gateway.agentConfig.saveModel(
                    AiModelConfig(
                        connectionId = "conn-team",
                        modelId = "qwen3-235b",
                        displayName = "Qwen3 235B",
                        contextWindowTokens = 128_000,
                    ),
                )
                second.gateway.agentConfig.savePreferences(
                    AiAgentPreferences(
                        defaultModelId = "conn-team::qwen3-235b",
                        maxToolRounds = 9,
                        autoCompactPercent = 82,
                        retainPercent = 18,
                    ),
                )
                second.gateway.serverRecovery.publish(env.address, "", "")
                first.gateway.serverRecovery.updateIfChanged()

                assertTrue(second.gateway.serverRecovery.conflicts().none { !it.resolved })
                val mergedOnFirst = first.gateway.agentConfig.load()
                assertEquals(setOf("conn-openai", "conn-team"), mergedOnFirst.config.connections.map { it.connectionId }.toSet())
                assertEquals(
                    mapOf("conn-openai::gpt-4.1" to 1_000_000, "conn-team::qwen3-235b" to 128_000),
                    mergedOnFirst.config.models.associate { it.id to it.contextWindowTokens },
                )
                assertEquals(9, mergedOnFirst.config.preferences.maxToolRounds)
                assertEquals(82, mergedOnFirst.config.preferences.autoCompactPercent)

                val keyAlpha = "sk-conflict-alpha-9001"
                val keyBeta = "sk-conflict-beta-9002"
                first.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-team",
                        name = "Team Shared vLLM",
                        baseUrl = "https://llm.example.com/v1",
                        protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = true,
                        newApiKeyInput = keyAlpha,
                    ),
                )
                first.gateway.serverRecovery.publish(env.address, "", "")

                second.gateway.agentConfig.saveConnection(
                    WorkspaceConnectionSaveDraft(
                        connectionId = "conn-team",
                        name = "Team Shared vLLM",
                        baseUrl = "https://llm.example.com/v1",
                        protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                        authMode = AiAuthMode.KEYSTORE,
                        syncKeyInEnvelope = true,
                        newApiKeyInput = keyBeta,
                    ),
                )
                second.gateway.serverRecovery.publish(env.address, "", "")

                val configConflicts = second.gateway.serverRecovery.conflicts().filter {
                    it.objectId == WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID && !it.resolved
                }
                assertEquals(1, configConflicts.size)
                val conflictView = configConflicts.single()
                val combinedUiText = buildString {
                    appendLine(conflictView.localTitle)
                    appendLine(conflictView.remoteTitle)
                    appendLine(conflictView.localContent)
                    appendLine(conflictView.remoteContent)
                    conflictView.fields.forEach { field ->
                        appendLine(field.labelKey)
                        appendLine(field.detailLabel.orEmpty())
                        appendLine(field.localContent)
                        appendLine(field.remoteContent)
                    }
                }
                assertFalse(combinedUiText.contains(keyAlpha), "冲突审阅文本绝不能泄露本地同步 API Key")
                assertFalse(combinedUiText.contains(keyBeta), "冲突审阅文本绝不能泄露远端同步 API Key")
                assertTrue(combinedUiText.contains("••••••••"), "冲突字段视图应显示掩码占位符")
            }
        }

    /**
     * 验证在途工具调用流式探测期间一旦触发 `gateway.lock()`，底层连接立即取消而不挂起等待超时。
     */
    @Test
    fun cancelsInFlightToolProbeImmediatelyWhenWorkspaceLocks() =
        ServerRecoveryTestEnvironment().use { env ->
            val requestArrived = CompletableDeferred<Unit>()
            val holdLatch = CountDownLatch(1)
            val executor = Executors.newCachedThreadPool()
            val hangingServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = executor
                createContext("/v1/chat/completions") { exchange ->
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    requestArrived.complete(Unit)
                    holdLatch.await(30, TimeUnit.SECONDS)
                    runCatching { exchange.close() }
                }
                start()
            }
            try {
                runBlocking {
                    val device = env.device("lock-cancel")
                    checkNotNull(device.gateway.prepareCreation(null))
                    device.gateway.confirmCreation()
                    device.gateway.agentConfig.saveConnection(
                        WorkspaceConnectionSaveDraft(
                            connectionId = "conn-hang",
                            name = "Hanging Local",
                            baseUrl = "http://127.0.0.1:${hangingServer.address.port}/v1",
                            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                            authMode = AiAuthMode.NONE,
                            timeoutSeconds = 60,
                        ),
                    )
                    device.gateway.agentConfig.saveModel(
                        AiModelConfig(
                            connectionId = "conn-hang",
                            modelId = "slow-model",
                            displayName = "Slow Model",
                        ),
                    )

                    val probeDeferred = async {
                        runCatching { device.gateway.agentConfig.probeModelToolCalling("conn-hang::slow-model") }
                    }
                    withTimeout(5_000) { requestArrived.await() }
                    device.gateway.lock()
                    val outcome = withTimeout(5_000) { probeDeferred.await() }
                    assertTrue(outcome.isFailure, "锁定时在途流式探测必须立即中断失败")
                }
            } finally {
                holdLatch.countDown()
                hangingServer.stop(0)
                executor.shutdownNow()
            }
        }

    private fun assertNoPlaintextSecretInDirectory(directory: Path, secrets: List<String>) {
        Files.walk(directory).use { stream ->
            stream.filter(Files::isRegularFile).forEach { file ->
                if (file.fileName.toString().endsWith(".lock")) return@forEach
                val text = Files.readAllBytes(file).decodeToString()
                secrets.forEach { secret ->
                    assertFalse(text.contains(secret), "文件 ${file.fileName} 严禁包含明文 API Key")
                }
            }
        }
    }

    private fun assertNoPlaintextSecretInZip(zipPath: Path, secrets: List<String>) {
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                    secrets.forEach { secret ->
                        assertFalse(text.contains(secret), "备份条目 ${entry.name} 严禁包含明文 API Key")
                    }
                }
            }
        }
    }
}
