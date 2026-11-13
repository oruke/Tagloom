package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiErrorKind
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiProviderException
import com.oruke.tagloom.core.ai.AiRemoteModelInfo
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import kotlinx.coroutines.CancellationException

/**
 * 协调「Agent 与模型」设置页面的异步配置读写、连通性检测、远端模型拉取与最小工具调用实测。
 *
 * 严格遵循密钥零回显原则：控制器状态绝不保存任何 API Key 明文，且在工作区锁定或切换会话时立即清空。
 */
internal class WorkspaceAgentSettingsController(
    /** 关联的顶层工作区 UI 控制器，用于校验会话生命周期标记。 */
    private val workspace: WorkspaceUiController,
) {
    /** 当前平台是否已注入真实 Agent 配置网关。 */
    val available: Boolean
        get() = workspace.gateway.agentConfig != null

    /** 当前已解锁会话加载的脱敏配置与凭据就绪快照。 */
    var snapshot: WorkspaceAgentConfigSnapshot? by mutableStateOf(null)
        private set

    /** 当前解锁会话是否已成功完成至少一次配置加载。 */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** 是否正在执行初次加载或配置持久化动作。 */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** 是否正在执行端点连通性检测。 */
    var testingConnection: Boolean by mutableStateOf(false)
        private set

    /** 当前正在拉取 `/models` 的连接 ID；空闲时为空。 */
    var fetchingModelsConnectionId: String? by mutableStateOf(null)
        private set

    /** 当前正在执行工具调用实测的模型复合 ID；空闲时为空。 */
    var probingModelId: String? by mutableStateOf(null)
        private set

    /** 按 `connectionId` 缓存的最近一次 `/models` 发现结果，锁定会话即清空。 */
    var discoveredModelsByConnection: Map<String, List<AiRemoteModelInfo>> by mutableStateOf(emptyMap())
        private set

    /** 最近一次操作成功的可本地化语义编码。 */
    var statusCode: String? by mutableStateOf(null)
        private set

    /** 最近一次操作失败的脱敏可本地化错误编码。 */
    var errorCode: String? by mutableStateOf(null)
        private set

    /**
     * 在进入设置页且尚未加载时拉取当前工作区的脱敏 Agent 配置快照。
     */
    suspend fun ensureLoaded() {
        if (!available || loaded || busy) {
            return
        }
        val marker = workspace.workspaceSessionMarker
        busy = true
        errorCode = null
        try {
            val fresh = requireNotNull(workspace.gateway.agentConfig).load()
            if (workspace.workspaceSessionMarker === marker) {
                snapshot = fresh
                loaded = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                errorCode = classifyError(error)
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                busy = false
            }
        }
    }

    /**
     * 在外部同步或确认点刷新后静默同步已加载的 Agent 配置视图。
     *
     * @param expectedMarker 发起刷新时的会话标记。
     */
    suspend fun reloadIfLoaded(expectedMarker: Any) {
        val gateway = workspace.gateway.agentConfig ?: return
        if (!loaded || workspace.workspaceSessionMarker !== expectedMarker) {
            return
        }
        try {
            val fresh = gateway.load()
            if (workspace.workspaceSessionMarker === expectedMarker) {
                snapshot = fresh
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // 外部观察刷新失败由外层统一记录，不覆盖用户正在查看的配置表单。
        }
    }

    /**
     * 保存（新建或编辑）单个连接与分层密钥，成功后回调清理调用方的瞬态密钥输入。
     *
     * @param draft 连接配置与可选新密钥输入。
     * @param onSaved 保存成功且仍处同一会话时执行的 UI 收尾回调。
     */
    suspend fun saveConnection(
        draft: WorkspaceConnectionSaveDraft,
        onSaved: () -> Unit = {},
    ) = runConfigMutation(successCode = "connection_saved") { gateway, marker ->
        val updated = gateway.saveConnection(draft)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
            loaded = true
            onSaved()
        }
    }

    /**
     * 删除指定连接及其关联模型与本机隔离凭据。
     *
     * @param connectionId 待删除连接 ID。
     */
    suspend fun deleteConnection(connectionId: String) = runConfigMutation(successCode = "connection_deleted") { gateway, marker ->
        val updated = gateway.deleteConnection(connectionId)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
            discoveredModelsByConnection = discoveredModelsByConnection - connectionId
        }
    }

    /**
     * 对表单草稿执行真实网络连通性与鉴权校验。
     *
     * @param draft 待测试的连接表单草稿。
     */
    suspend fun testConnection(draft: WorkspaceConnectionSaveDraft) {
        val gateway = workspace.gateway.agentConfig ?: return
        if (testingConnection || busy) {
            return
        }
        val marker = workspace.workspaceSessionMarker
        testingConnection = true
        statusCode = null
        errorCode = null
        try {
            val result = gateway.testConnection(draft)
            if (workspace.workspaceSessionMarker === marker) {
                if (result.reachable) {
                    statusCode = "connection_ok"
                } else {
                    errorCode = "network"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                errorCode = classifyError(error)
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                testingConnection = false
            }
        }
    }

    /**
     * 从指定连接的 `/models` 端点拉取远端候选模型列表。
     *
     * @param connectionId 已保存连接 ID。
     */
    suspend fun fetchRemoteModels(connectionId: String) {
        val gateway = workspace.gateway.agentConfig ?: return
        if (fetchingModelsConnectionId != null || busy) {
            return
        }
        val marker = workspace.workspaceSessionMarker
        fetchingModelsConnectionId = connectionId
        statusCode = null
        errorCode = null
        try {
            val remoteModels = gateway.fetchRemoteModels(connectionId)
            if (workspace.workspaceSessionMarker === marker) {
                discoveredModelsByConnection = discoveredModelsByConnection + (connectionId to remoteModels)
                statusCode = if (remoteModels.isEmpty()) "models_empty" else "models_fetched"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                errorCode = classifyError(error)
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                fetchingModelsConnectionId = null
            }
        }
    }

    /**
     * 一键导入从 `/models` 发现的单个或多个候选模型。
     *
     * @param connectionId 所属连接 ID。
     * @param remoteModels 待导入的候选模型列表。
     */
    suspend fun importDiscoveredModels(
        connectionId: String,
        remoteModels: List<AiRemoteModelInfo>,
    ) = runConfigMutation(successCode = "model_saved") { gateway, marker ->
        val updated = gateway.importRemoteModels(connectionId, remoteModels)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
        }
    }

    /**
     * 保存（新增或更新）单个模型及其上下文窗口配置。
     *
     * @param model 目标模型配置。
     * @param onSaved 保存成功后的表单清理回调。
     */
    suspend fun saveModel(
        model: AiModelConfig,
        onSaved: () -> Unit = {},
    ) = runConfigMutation(successCode = "model_saved") { gateway, marker ->
        val updated = gateway.saveModel(model)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
            onSaved()
        }
    }

    /**
     * 移除指定模型配置。
     *
     * @param modelCompositeId 模型复合主键（`connectionId::modelId`）。
     */
    suspend fun deleteModel(modelCompositeId: String) = runConfigMutation(successCode = "model_deleted") { gateway, marker ->
        val updated = gateway.deleteModel(modelCompositeId)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
        }
    }

    /**
     * 对指定模型发起零笔记最小工具调用实测，并持久化验证结果。
     *
     * @param modelCompositeId 模型复合主键（`connectionId::modelId`）。
     */
    suspend fun probeModelToolCalling(modelCompositeId: String) {
        val gateway = workspace.gateway.agentConfig ?: return
        if (probingModelId != null || busy) {
            return
        }
        val marker = workspace.workspaceSessionMarker
        probingModelId = modelCompositeId
        statusCode = null
        errorCode = null
        try {
            val (probe, updated) = gateway.probeModelToolCalling(modelCompositeId)
            if (workspace.workspaceSessionMarker === marker) {
                snapshot = updated
                statusCode = if (probe.supported) "probe_verified" else "probe_chat_only"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                errorCode = classifyError(error)
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                probingModelId = null
            }
        }
    }

    /**
     * 保存自动上下文压缩水位（默认 `80% / 16%`）与单轮运行护栏偏好。
     *
     * @param preferences 待保存的偏好对象。
     */
    suspend fun savePreferences(preferences: AiAgentPreferences) = runConfigMutation(successCode = "preferences_saved") { gateway, marker ->
        val updated = gateway.savePreferences(preferences)
        if (workspace.workspaceSessionMarker === marker) {
            snapshot = updated
        }
    }

    /**
     * 在工作区锁定或切换会话时清空全部脱敏视图、远端模型缓存与提示编码。
     */
    fun clear() {
        snapshot = null
        loaded = false
        busy = false
        testingConnection = false
        fetchingModelsConnectionId = null
        probingModelId = null
        discoveredModelsByConnection = emptyMap()
        statusCode = null
        errorCode = null
    }

    /**
     * 串行执行一次配置修改操作并捕获脱敏错误编码。
     */
    private suspend fun runConfigMutation(
        successCode: String,
        block: suspend (com.oruke.tagloom.platform.WorkspaceAgentConfigGateway, Any) -> Unit,
    ) {
        val gateway = workspace.gateway.agentConfig ?: return
        if (busy) {
            return
        }
        val marker = workspace.workspaceSessionMarker
        busy = true
        statusCode = null
        errorCode = null
        try {
            block(gateway, marker)
            if (workspace.workspaceSessionMarker === marker) {
                statusCode = successCode
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (workspace.workspaceSessionMarker === marker) {
                errorCode = classifyError(error)
            }
        } finally {
            if (workspace.workspaceSessionMarker === marker) {
                busy = false
            }
        }
    }

    /**
     * 将领域层或参数校验异常映射为不含敏感文本的固定 UI 错误分类码。
     */
    private fun classifyError(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "validation"
        is AiProviderException -> when (error.kind) {
            AiErrorKind.UNAUTHORIZED, AiErrorKind.FORBIDDEN -> "auth"
            AiErrorKind.RATE_LIMITED -> "rate_limit"
            AiErrorKind.INVALID_REQUEST, AiErrorKind.NOT_FOUND, AiErrorKind.PROTOCOL -> "endpoint"
            AiErrorKind.NETWORK, AiErrorKind.TIMEOUT, AiErrorKind.SERVER_ERROR -> "network"
            AiErrorKind.CANCELLED -> "generic"
        }
        is ExternalCallException -> when {
            error.message?.contains("key", ignoreCase = true) == true ||
                error.message?.contains("auth", ignoreCase = true) == true ||
                error.message?.contains("credential", ignoreCase = true) == true -> "auth"
            else -> "generic"
        }
        else -> "generic"
    }
}
