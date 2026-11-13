package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.sync.WorkspaceReplicaAction
import com.oruke.tagloom.core.sync.WorkspaceReplicaCatalogConflictResolver
import com.oruke.tagloom.core.sync.WorkspaceReplicaCatalogValidator
import com.oruke.tagloom.core.sync.WorkspaceReplicaConflictReason
import com.oruke.tagloom.core.sync.WorkspaceReplicaMergePlanner
import com.oruke.tagloom.core.sync.WorkspaceReplicaObject
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证工作区 Agent 配置协议、80% 自动压缩阈值计算、分层密钥安全边界及多设备细粒度三方合并。
 */
class WorkspaceConfigAndMergeTest {
    /** 验证合法配置、80% 自动压缩线换算与 Token 账单缓存命中率计算。 */
    @Test
    fun validatesWorkspaceConfigAndCalculatesCompactionThresholdsAndTokenUsage() {
        val connection = AiProviderConnection(
            connectionId = "conn-deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.KEYSTORE,
            syncKeyInEnvelope = true,
            syncedApiKey = "sk-test-envelope-only",
        )
        val model128k = AiModelConfig(
            connectionId = "conn-deepseek",
            modelId = "deepseek-chat",
            displayName = "DeepSeek V3.2",
            contextWindowTokens = 128_000,
            toolsState = AiModelToolsState.VERIFIED,
            supportedThinkingEfforts = listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH),
        )
        val model200k = AiModelConfig(
            connectionId = "conn-deepseek",
            modelId = "o3-mini",
            displayName = "o3-mini",
            contextWindowTokens = 200_000,
        )
        val config = WorkspaceConfig(
            connections = listOf(connection),
            models = listOf(model128k, model200k),
            preferences = AiAgentPreferences(defaultModelId = model128k.id),
        )

        assertEquals(emptyList(), WorkspaceConfigValidator.validate(config))
        assertEquals(102_400, model128k.autoCompactThresholdTokens(80))
        assertEquals("102.4K", AiConnectionPresets.formatTokenCount(model128k.autoCompactThresholdTokens(80)))
        assertEquals(160_000, model200k.autoCompactThresholdTokens(80))
        assertEquals("160K", AiConnectionPresets.formatTokenCount(model200k.autoCompactThresholdTokens(80)))

        val firstTurn = AiTokenUsage(uncachedInputTokens = 1_200, cacheReadTokens = 3_800, outputTokens = 400, reasoningTokens = 150)
        val secondTurn = AiTokenUsage(uncachedInputTokens = 800, cacheReadTokens = 4_200, outputTokens = 600, reasoningTokens = 250)
        val total = firstTurn + secondTurn
        assertEquals(10_000, total.totalInputTokens)
        assertEquals(11_000, total.totalTokens)
        assertEquals(400, total.reasoningTokens)
        assertEquals(0.8, total.cacheHitRatio())
        assertNull(AiTokenUsage().cacheHitRatio())
    }

    /** 验证未开启信封同步却携带密钥、伪装认证请求头、URL 夹带凭据及越界窗口等违规均被拦截。 */
    @Test
    fun rejectsSecretLeakageVectorsAndOutOfRangeParameters() {
        val leakedKeyConn = AiProviderConnection(
            connectionId = "conn-leak",
            name = "Leak",
            baseUrl = "https://user:secret@api.openai.com/v1?api_key=123",
            protocol = AiProtocolKind.OPENAI_RESPONSES,
            authMode = AiAuthMode.KEYSTORE,
            customHeaders = mapOf("Authorization" to "Bearer secret"),
            syncKeyInEnvelope = false,
            syncedApiKey = "sk-should-not-be-here",
        )
        val badEnvConn = AiProviderConnection(
            connectionId = "conn-env",
            name = "Env",
            baseUrl = "http://127.0.0.1:11434/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.ENV_VAR,
            envVarName = "$(whoami)",
            syncKeyInEnvelope = true,
        )
        val badModel = AiModelConfig(
            id = "wrong-id",
            connectionId = "missing-conn",
            modelId = "tiny-model",
            displayName = "Tiny",
            contextWindowTokens = 4_096,
            supportedThinkingEfforts = listOf(AiThinkingEffort.OFF),
        )
        val badPrefs = AiAgentPreferences(
            autoCompactPercent = 45,
            retainPercent = 50,
        )
        val violations = WorkspaceConfigValidator.validate(
            WorkspaceConfig(
                connections = listOf(leakedKeyConn, badEnvConn),
                models = listOf(badModel),
                preferences = badPrefs,
            ),
        )
        assertTrue("connection-base-url-invalid" in violations)
        assertTrue("connection-header-forbidden-auth" in violations)
        assertTrue("connection-synced-key-forbidden-when-disabled" in violations)
        assertTrue("connection-env-var-invalid" in violations)
        assertTrue("connection-synced-key-forbidden-for-env-var" in violations)
        assertTrue("model-connection-missing" in violations)
        assertTrue("model-composite-id-mismatch" in violations)
        assertTrue("model-context-window-out-of-range" in violations)
        assertTrue("model-thinking-efforts-invalid" in violations)
        assertTrue("preferences-auto-compact-percent-out-of-range" in violations)
        assertTrue("preferences-retain-exceeds-compact-threshold" in violations)
    }

    /** 验证基于共同祖先时，多设备并发修改不同连接、模型窗口和自动压缩偏好可无冲突合并。 */
    @Test
    fun mergesConcurrentConnectionModelWindowAndPreferenceEditsWithoutConflict() {
        val baseConn = AiProviderConnection(
            connectionId = "conn-openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = AiProtocolKind.OPENAI_RESPONSES,
        )
        val baseModel = AiModelConfig(
            connectionId = "conn-openai",
            modelId = "gpt-4.1",
            displayName = "GPT-4.1",
            contextWindowTokens = 128_000,
        )
        val baseConfig = WorkspaceConfig(
            connections = listOf(baseConn),
            models = listOf(baseModel),
            preferences = AiAgentPreferences(autoCompactPercent = 80, maxToolRounds = 6),
        )

        val localConfig = baseConfig.copy(
            models = listOf(baseModel.copy(contextWindowTokens = 1_000_000, toolsState = AiModelToolsState.VERIFIED)),
            preferences = baseConfig.preferences.copy(maxToolRounds = 10),
        )
        val ollamaConn = AiProviderConnection(
            connectionId = "conn-ollama",
            name = "Ollama",
            baseUrl = "http://127.0.0.1:11434/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.NONE,
        )
        val ollamaModel = AiModelConfig(
            connectionId = "conn-ollama",
            modelId = "qwen3:8b",
            displayName = "Qwen3 8B",
            contextWindowTokens = 32_000,
        )
        val remoteConfig = baseConfig.copy(
            connections = listOf(baseConn, ollamaConn),
            models = listOf(baseModel, ollamaModel),
            preferences = baseConfig.preferences.copy(autoCompactPercent = 85),
        )

        val root = configChange("cfg-root", "actor-root", 1L, baseConfig)
        val local = configChange("cfg-local", "actor-a", 10L, localConfig, listOf(root.changeId), ChangeKind.UPDATE)
        val remote = configChange("cfg-remote", "actor-b", 20L, remoteConfig, listOf(root.changeId), ChangeKind.UPDATE)

        val plan = WorkspaceReplicaMergePlanner.plan(
            local = listOf(configReplica(local, localConfig)),
            remote = listOf(configReplica(remote, remoteConfig)),
            changes = listOf(root, local, remote),
            mergedAt = "2026-11-13T10:00:00Z",
        ) { _, _, _, _ -> "unused-conflict" }

        assertTrue(plan.conflicts.isEmpty())
        val decision = plan.objects.single()
        assertEquals(WorkspaceReplicaAction.MERGE, decision.action)
        WorkspaceReplicaCatalogValidator.validate(listOf(WorkspaceReplicaObject(decision.kind, decision.snapshot)))

        val merged = CanonicalJson.format.decodeFromJsonElement(WorkspaceConfig.serializer(), decision.snapshot.payload)
        assertEquals(listOf("conn-openai", "conn-ollama"), merged.connections.map { it.connectionId })
        val mergedGpt = merged.models.first { it.modelId == "gpt-4.1" }
        assertEquals(1_000_000, mergedGpt.contextWindowTokens)
        assertEquals(AiModelToolsState.VERIFIED, mergedGpt.toolsState)
        assertEquals(32_000, merged.models.first { it.modelId == "qwen3:8b" }.contextWindowTokens)
        assertEquals(10, merged.preferences.maxToolRounds)
        assertEquals(85, merged.preferences.autoCompactPercent)
    }

    /** 验证两台设备在全新工作区上各自首次创建单例配置时，可基于隐式空配置无损合并双端连接。 */
    @Test
    fun mergesIndependentFirstTimeSingletonConfigCreationsAcrossDevices() {
        val localConn = AiProviderConnection(
            connectionId = "conn-a",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = AiProtocolKind.OPENAI_RESPONSES,
        )
        val remoteConn = AiProviderConnection(
            connectionId = "conn-b",
            name = "LM Studio",
            baseUrl = "http://127.0.0.1:1234/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.NONE,
        )
        val localConfig = WorkspaceConfig(connections = listOf(localConn))
        val remoteConfig = WorkspaceConfig(connections = listOf(remoteConn))
        val localCreate = configChange("create-a", "actor-a", 1L, localConfig)
        val remoteCreate = configChange("create-b", "actor-b", 2L, remoteConfig)

        val plan = WorkspaceReplicaMergePlanner.plan(
            local = listOf(configReplica(localCreate, localConfig)),
            remote = listOf(configReplica(remoteCreate, remoteConfig)),
            changes = listOf(localCreate, remoteCreate),
            mergedAt = "2026-11-13T10:05:00Z",
        ) { _, _, _, _ -> "unused-conflict" }

        assertTrue(plan.conflicts.isEmpty())
        val merged = CanonicalJson.format.decodeFromJsonElement(
            WorkspaceConfig.serializer(),
            plan.objects.single().snapshot.payload,
        )
        assertEquals(setOf("conn-a", "conn-b"), merged.connections.map { it.connectionId }.toSet())
    }

    /** 验证一端删除连接而另一端并发在该连接下新增模型时，由目录冲突解决器整组选择合法配置并保留证据。 */
    @Test
    fun fallsBackToConsistentCatalogWhenConnectionDeletedConcurrentlyWithModelAddition() {
        val connA = AiProviderConnection(
            connectionId = "conn-a",
            name = "Conn A",
            baseUrl = "https://api.openai.com/v1",
            protocol = AiProtocolKind.OPENAI_RESPONSES,
        )
        val baseConfig = WorkspaceConfig(connections = listOf(connA))
        val localDeletedConn = WorkspaceConfig(connections = emptyList())
        val remoteAddedModel = WorkspaceConfig(
            connections = listOf(connA),
            models = listOf(
                AiModelConfig(
                    connectionId = "conn-a",
                    modelId = "gpt-4.1-mini",
                    displayName = "GPT-4.1 Mini",
                    contextWindowTokens = 128_000,
                ),
            ),
        )
        val root = configChange("cfg-root", "actor-root", 1L, baseConfig)
        val local = configChange("cfg-del", "actor-a", 10L, localDeletedConn, listOf(root.changeId), ChangeKind.UPDATE)
        val remote = configChange("cfg-add", "actor-b", 20L, remoteAddedModel, listOf(root.changeId), ChangeKind.UPDATE)

        val resolved = WorkspaceReplicaCatalogConflictResolver.plan(
            local = listOf(configReplica(local, localDeletedConn)),
            remote = listOf(configReplica(remote, remoteAddedModel)),
            changes = listOf(root, local, remote),
            mergedAt = "2026-11-13T10:10:00Z",
        ) { _, _, _, _ -> "cfg-invariant-conflict" }

        WorkspaceReplicaCatalogValidator.validate(
            resolved.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) },
        )
        assertEquals(WorkspaceReplicaConflictReason.CATALOG_INVARIANT, resolved.conflicts.single().reason)
    }

    /**
     * 构造测试用配置逻辑 Change。
     *
     * @param id Change 标识。
     * @param actor Actor 标识。
     * @param time 逻辑毫秒时间。
     * @param config 配置载荷。
     * @param parents 父 Change 列表。
     * @param kind Change 类型。
     * @return 测试用 `ObjectChange`。
     */
    private fun configChange(
        id: String,
        actor: String,
        time: Long,
        config: WorkspaceConfig,
        parents: List<ChangeId> = emptyList(),
        kind: ChangeKind = ChangeKind.CREATE,
    ): ObjectChange = ObjectChange(
        changeId = ChangeId(id),
        objectId = WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID,
        parentChangeIds = parents,
        actorId = ActorId(actor),
        hybridLogicalTime = HybridLogicalTime(time, 0, ActorId(actor)),
        baseSnapshotRevision = null,
        transactionId = TransactionId("tx-$id"),
        kind = kind,
        operations = listOf(
            ChangeOperation(
                type = "replace-workspace-config",
                payload = CanonicalJson.format.encodeToJsonElement(WorkspaceConfig.serializer(), config),
            ),
        ),
    )

    /**
     * 构造测试用同步候选配置对象。
     *
     * @param change 最新 Change。
     * @param config 配置载荷。
     * @return 包装为 `JsonElement` 快照的同步对象。
     */
    private fun configReplica(change: ObjectChange, config: WorkspaceConfig): WorkspaceReplicaObject =
        WorkspaceReplicaObject(
            kind = ManifestObjectKind.WORKSPACE_CONFIG,
            snapshot = ObjectSnapshot(
                header = SnapshotHeader(
                    schemaVersion = 1,
                    objectId = ObjectId("workspace-config"),
                    objectType = ObjectType.WORKSPACE_CONFIG,
                    revision = ObjectRevision(change.actorId, change.hybridLogicalTime.physicalTimeMillis),
                    lastChangeId = change.changeId,
                    createdAt = "2026-11-13T09:00:00Z",
                    updatedAt = "2026-11-13T09:30:00Z",
                ),
                payload = CanonicalJson.format.encodeToJsonElement(WorkspaceConfig.serializer(), config),
            ),
        )
}
