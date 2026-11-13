package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiConnectionTestResult
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiRemoteModelInfo
import com.oruke.tagloom.core.ai.AiStreamPort
import com.oruke.tagloom.core.ai.AiToolProbeResult
import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.platform.WorkspaceAgentConfigGateway
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import com.oruke.tagloom.sync.ai.JvmKtorAiStreamPortFactory
import com.oruke.tagloom.sync.ai.KtorAiStreamPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 JVM 与 Android 上桥接共享 UI 与已解锁工作区 `WORKSPACE_CONFIG` 加密快照、本机隔离凭据库及流式探测端口。
 *
 * 确保所有返回给共享 UI 的快照均擦除 `syncedApiKey`，并在切换密钥同步开关时安全迁移或彻底清理本机/信封密钥。
 *
 * @param gateway 持有当前唯一活动会话的桌面工作区网关。
 * @param apiKeyStore 独立于工作区目录与备份包的本机凭据存储。
 * @param streamPort 用于连通性测试、`/models` 拉取与工具调用实测的流式模型端口。
 * @param onConfigCommitted 配置确认写入后的无明文变更通知回调。
 */
internal class DesktopAgentConfigGateway(
    private val gateway: DesktopWorkspaceGateway,
    private val apiKeyStore: AgentApiKeyStore,
    private val streamPort: AiStreamPort = JvmKtorAiStreamPortFactory.create(),
    private val onConfigCommitted: () -> Unit = {},
) : WorkspaceAgentConfigGateway {
    /**
     * 读取当前工作区的脱敏配置与各连接凭据状态。
     *
     * @return 不含任何真实密钥文本的 [WorkspaceAgentConfigSnapshot]。
     */
    override suspend fun load(): WorkspaceAgentConfigSnapshot = withContext(Dispatchers.IO) {
        val session = activeSession()
        val rawConfig = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        toSanitizedSnapshot(session, rawConfig)
    }

    /**
     * 保存单个连接及分层凭据，并在单次事务中持久化 `WORKSPACE_CONFIG`。
     *
     * @param draft 用户编辑草稿。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun saveConnection(draft: WorkspaceConnectionSaveDraft): WorkspaceAgentConfigSnapshot =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val connectionId = draft.connectionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: gateway.createOpaqueId("ai-connection")
            val existing = current.connections.firstOrNull { it.connectionId == connectionId }
            val typedKey = draft.newApiKeyInput.trim().takeIf { it.isNotEmpty() }

            val resolvedAuthMode = draft.authMode
            val syncInEnvelope = resolvedAuthMode == AiAuthMode.KEYSTORE && draft.syncKeyInEnvelope

            val envelopeApiKey: String? = when {
                resolvedAuthMode != AiAuthMode.KEYSTORE -> null
                !syncInEnvelope -> null
                draft.clearSavedApiKey -> null
                typedKey != null -> typedKey
                !existing?.syncedApiKey.isNullOrBlank() -> existing.syncedApiKey
                else -> apiKeyStore.loadLocalApiKeyOrNull(session.workspaceId, connectionId)
            }

            val updatedConnection = AiProviderConnection(
                connectionId = connectionId,
                name = draft.name.trim(),
                baseUrl = draft.baseUrl.trim().trimEnd('/'),
                protocol = draft.protocol,
                authMode = resolvedAuthMode,
                envVarName = if (resolvedAuthMode == AiAuthMode.ENV_VAR) draft.envVarName?.trim() else null,
                timeoutSeconds = draft.timeoutSeconds,
                customHeaders = draft.customHeaders
                    .mapKeys { (k, _) -> k.trim() }
                    .mapValues { (_, v) -> v.trim() }
                    .filterKeys { it.isNotEmpty() },
                syncKeyInEnvelope = syncInEnvelope,
                syncedApiKey = envelopeApiKey,
            )

            val updatedConnections = if (existing == null) {
                current.connections + updatedConnection
            } else {
                current.connections.map { item ->
                    if (item.connectionId == connectionId) updatedConnection else item
                }
            }
            val saved = session.saveWorkspaceConfig(current.copy(connections = updatedConnections)).payload

            when {
                resolvedAuthMode != AiAuthMode.KEYSTORE || draft.clearSavedApiKey -> {
                    apiKeyStore.deleteLocalApiKey(session.workspaceId, connectionId)
                }

                syncInEnvelope -> {
                    apiKeyStore.deleteLocalApiKey(session.workspaceId, connectionId)
                }

                typedKey != null -> {
                    apiKeyStore.saveLocalApiKey(session.workspaceId, connectionId, typedKey)
                }

                existing != null && existing.syncKeyInEnvelope && !existing.syncedApiKey.isNullOrBlank() -> {
                    apiKeyStore.saveLocalApiKey(
                        session.workspaceId,
                        connectionId,
                        requireNotNull(existing.syncedApiKey),
                    )
                }
            }

            onConfigCommitted()
            toSanitizedSnapshot(session, saved)
        }

    /**
     * 删除连接、级联清理下属模型及本机隔离密钥。
     *
     * @param connectionId 待删除连接稳定 ID。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun deleteConnection(connectionId: String): WorkspaceAgentConfigSnapshot =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val remainingConnections = current.connections.filterNot { it.connectionId == connectionId }
            val remainingModels = current.models.filterNot { it.connectionId == connectionId }
            val validModelIds = remainingModels.map(AiModelConfig::id).toSet()
            val updatedPrefs = if (current.preferences.defaultModelId != null &&
                current.preferences.defaultModelId !in validModelIds
            ) {
                current.preferences.copy(defaultModelId = null)
            } else {
                current.preferences
            }
            val saved = session.saveWorkspaceConfig(
                current.copy(
                    connections = remainingConnections,
                    models = remainingModels,
                    preferences = updatedPrefs,
                ),
            ).payload
            apiKeyStore.deleteLocalApiKey(session.workspaceId, connectionId)
            onConfigCommitted()
            toSanitizedSnapshot(session, saved)
        }

    /**
     * 保存（新增或更新）单个模型配置及其 `contextWindowTokens`。
     *
     * @param model 目标模型定义。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun saveModel(model: AiModelConfig): WorkspaceAgentConfigSnapshot =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val normalizedId = AiModelConfig.composeModelConfigId(model.connectionId, model.modelId)
            val normalized = model.copy(
                id = normalizedId,
                connectionId = model.connectionId.trim(),
                modelId = model.modelId.trim(),
                displayName = model.displayName.trim().ifEmpty { model.modelId.trim() },
            )
            val exists = current.models.any { it.id == normalized.id }
            val updatedModels = if (exists) {
                current.models.map { item -> if (item.id == normalized.id) normalized else item }
            } else {
                current.models + normalized
            }
            val saved = session.saveWorkspaceConfig(
                current.copy(models = updatedModels),
            ).payload
            onConfigCommitted()
            toSanitizedSnapshot(session, saved)
        }

    /**
     * 批量导入远端候选模型（保留已有同 ID 模型的用户自定义窗口与开关）。
     *
     * @param connectionId 所属连接 ID。
     * @param remoteModels 待导入候选模型列表。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun importRemoteModels(
        connectionId: String,
        remoteModels: List<AiRemoteModelInfo>,
    ): WorkspaceAgentConfigSnapshot = withContext(Dispatchers.IO) {
        val session = activeSession()
        val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        if (remoteModels.isEmpty()) return@withContext toSanitizedSnapshot(session, current)
        val existingIds = current.models.map(AiModelConfig::id).toSet()
        val additions = remoteModels.mapNotNull { remote ->
            val compositeId = AiModelConfig.composeModelConfigId(connectionId, remote.modelId)
            if (compositeId in existingIds) return@mapNotNull null
            AiModelConfig(
                connectionId = connectionId,
                modelId = remote.modelId.trim(),
                displayName = remote.displayName.trim().ifEmpty { remote.modelId.trim() },
                id = compositeId,
                contextWindowTokens = remote.suggestedContextWindowTokens,
                toolsState = AiModelToolsState.DECLARED,
                supportedThinkingEfforts = remote.suggestedThinkingEfforts,
                enabled = true,
            )
        }
        if (additions.isEmpty()) return@withContext toSanitizedSnapshot(session, current)
        val mergedModels = current.models + additions
        val saved = session.saveWorkspaceConfig(
            current.copy(models = mergedModels),
        ).payload
        onConfigCommitted()
        toSanitizedSnapshot(session, saved)
    }

    /**
     * 删除指定模型配置。
     *
     * @param modelCompositeId 模型复合主键。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun deleteModel(modelCompositeId: String): WorkspaceAgentConfigSnapshot =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val remaining = current.models.filterNot { it.id == modelCompositeId }
            val updatedPrefs = if (current.preferences.defaultModelId == modelCompositeId) {
                current.preferences.copy(defaultModelId = remaining.firstOrNull { it.enabled }?.id)
            } else {
                current.preferences
            }
            val saved = session.saveWorkspaceConfig(
                current.copy(models = remaining, preferences = updatedPrefs),
            ).payload
            onConfigCommitted()
            toSanitizedSnapshot(session, saved)
        }

    /**
     * 保存运行偏好与自动上下文压缩参数。
     *
     * @param preferences 偏好配置。
     * @return 更新后的脱敏配置快照。
     */
    override suspend fun savePreferences(preferences: AiAgentPreferences): WorkspaceAgentConfigSnapshot =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val saved = session.saveWorkspaceConfig(current.copy(preferences = preferences)).payload
            onConfigCommitted()
            toSanitizedSnapshot(session, saved)
        }

    /**
     * 执行连接连通性与认证测试（网络等待期间不持有 `sessionMutex`）。
     *
     * @param draft 当前连接草稿。
     * @return 连通性测试结果。
     */
    override suspend fun testConnection(draft: WorkspaceConnectionSaveDraft): AiConnectionTestResult =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val existing = draft.connectionId?.let { id -> current.connections.firstOrNull { it.connectionId == id } }
            val tempId = draft.connectionId ?: "draft-connection"
            val candidate = AiProviderConnection(
                connectionId = tempId,
                name = draft.name.trim().ifEmpty { "Test" },
                baseUrl = draft.baseUrl.trim().trimEnd('/'),
                protocol = draft.protocol,
                authMode = draft.authMode,
                envVarName = if (draft.authMode == AiAuthMode.ENV_VAR) draft.envVarName?.trim() else null,
                timeoutSeconds = draft.timeoutSeconds,
                customHeaders = draft.customHeaders,
                syncKeyInEnvelope = false,
                syncedApiKey = null,
            )
            val typedKey = draft.newApiKeyInput.trim().takeIf { it.isNotEmpty() }
            val resolvedKey = when (draft.authMode) {
                AiAuthMode.NONE -> null
                AiAuthMode.ENV_VAR -> apiKeyStore.resolveForRequest(session.workspaceId, candidate)
                AiAuthMode.KEYSTORE -> typedKey
                    ?: existing?.let { apiKeyStore.resolveForRequest(session.workspaceId, it) }
            }
            streamPort.testConnection(candidate, resolvedKey)
        }

    /**
     * 拉取已保存连接的 `/models` 远端模型目录（网络等待期间不持有写锁）。
     *
     * @param connectionId 已保存连接 ID。
     * @return 候选模型列表。
     */
    override suspend fun fetchRemoteModels(connectionId: String): List<AiRemoteModelInfo> =
        withContext(Dispatchers.IO) {
            val session = activeSession()
            val current = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
            val connection = requireNotNull(current.connections.firstOrNull { it.connectionId == connectionId }) {
                "目标 API 连接不存在"
            }
            val resolvedKey = apiKeyStore.resolveForRequest(session.workspaceId, connection)
            streamPort.listModels(connection, resolvedKey)
        }

    /**
     * 执行零笔记工具调用实测，并在实测通过且工作区可写时将模型标记为 `VERIFIED`。
     *
     * @param modelCompositeId 模型复合主键。
     * @return 实测结果与最新脱敏配置快照。
     */
    override suspend fun probeModelToolCalling(
        modelCompositeId: String,
    ): Pair<AiToolProbeResult, WorkspaceAgentConfigSnapshot> = withContext(Dispatchers.IO) {
        val session = activeSession()
        val before = session.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        val model = requireNotNull(before.models.firstOrNull { it.id == modelCompositeId }) { "目标模型不存在" }
        val connection = requireNotNull(before.connections.firstOrNull { it.connectionId == model.connectionId }) {
            "模型所属连接不存在"
        }
        val resolvedKey = apiKeyStore.resolveForRequest(session.workspaceId, connection)
        val probe = streamPort.probeToolCalling(connection, resolvedKey, model.modelId)

        val latestSession = activeSession()
        val latestConfig = latestSession.loadWorkspaceConfig()?.payload ?: WorkspaceConfig()
        val nextToolsState = if (probe.supported) AiModelToolsState.VERIFIED else AiModelToolsState.CHAT_ONLY
        val updatedConfig = if (!latestSession.readOnly) {
            val updatedModels = latestConfig.models.map { candidate ->
                if (candidate.id == modelCompositeId) {
                    candidate.copy(toolsState = nextToolsState)
                } else {
                    candidate
                }
            }
            if (updatedModels != latestConfig.models) {
                latestSession.saveWorkspaceConfig(latestConfig.copy(models = updatedModels)).payload.also {
                    onConfigCommitted()
                }
            } else {
                latestConfig
            }
        } else {
            latestConfig
        }
        probe to toSanitizedSnapshot(latestSession, updatedConfig)
    }

    /**
     * 在工作区锁定时立即取消所有在途模型连通性/流式请求。
     */
    fun cancelActiveOperations() {
        (streamPort as? KtorAiStreamPort)?.cancelActiveRequests()
    }

    /**
     * 获取当前已解锁工作区会话，锁定时抛出统一受控异常。
     *
     * @return 当前活动 `DesktopWorkspaceSession`。
     */
    private fun activeSession(): DesktopWorkspaceSession =
        requireNotNull(gateway.sessionOrNull()) { "工作区尚未解锁" }

    /**
     * 剥离所有连接中的 `syncedApiKey` 并汇总当前设备的脱敏凭据状态。
     *
     * @param session 当前已解锁会话。
     * @param raw 权威配置载荷。
     * @return 供共享 UI 安全消费的脱敏快照。
     */
    private fun toSanitizedSnapshot(
        session: DesktopWorkspaceSession,
        raw: WorkspaceConfig,
    ): WorkspaceAgentConfigSnapshot {
        val statuses = raw.connections.associate { conn ->
            conn.connectionId to apiKeyStore.inspectStatus(session.workspaceId, conn)
        }
        val sanitizedConnections = raw.connections.map { conn ->
            conn.copy(syncedApiKey = null)
        }
        return WorkspaceAgentConfigSnapshot(
            config = raw.copy(connections = sanitizedConnections),
            credentialStatuses = statuses,
            supportsEnvironmentVariables = apiKeyStore.envSupported,
            readOnly = session.readOnly,
        )
    }
}
