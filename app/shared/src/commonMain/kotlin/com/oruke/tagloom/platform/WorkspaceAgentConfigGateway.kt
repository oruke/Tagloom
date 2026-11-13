package com.oruke.tagloom.platform

import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiConnectionTestResult
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiRemoteModelInfo
import com.oruke.tagloom.core.ai.AiToolProbeResult
import com.oruke.tagloom.core.ai.WorkspaceConfig

/**
 * 描述单个 Provider 连接在当前设备上的脱敏凭据就绪状态，绝不向 UI 暴露原始密钥文本。
 */
enum class WorkspaceAgentCredentialStatus {
    /** 本机免密模式（无需 API Key）。 */
    READY_NONE,

    /** 已在当前设备的系统安全存储（DPAPI / Android Keystore）中保存可用密钥。 */
    READY_LOCAL_KEYSTORE,

    /** 已在工作区双层加密信封（`WORKSPACE_CONFIG`）中保存跨设备同步密钥。 */
    READY_SYNCED_ENVELOPE,

    /** 桌面端已检测到配置的环境变量非空可用。 */
    READY_ENV_VAR,

    /** 连接骨架已存在或已同步，但当前设备尚未填写本机 API Key。 */
    MISSING_LOCAL_KEY,

    /** 配置的桌面环境变量当前未设置或为空。 */
    MISSING_ENV_VAR,

    /** 当前移动平台不支持读取桌面环境变量。 */
    ENV_VAR_UNSUPPORTED,
}

/**
 * 传递给共享 UI 的脱敏工作区 Agent 配置视图（所有连接的 `syncedApiKey` 均已擦除为 `null`）。
 */
data class WorkspaceAgentConfigSnapshot(
    /** 已擦除 `syncedApiKey` 的安全工作区配置对象。 */
    val config: WorkspaceConfig,
    /** 按 `connectionId` 索引的当前设备凭据就绪状态。 */
    val credentialStatuses: Map<String, WorkspaceAgentCredentialStatus>,
    /** 当前运行平台是否支持按桌面环境变量名读取密钥。 */
    val supportsEnvironmentVariables: Boolean,
    /** 当前工作区是否处于只读恢复模式。 */
    val readOnly: Boolean = false,
)

/**
 * 描述用户在「Agent 与模型」设置页新建或编辑单个 API 连接时提交的表单草稿。
 */
data class WorkspaceConnectionSaveDraft(
    /** 编辑既有连接时的稳定 `connectionId`；新建连接时为空，由网关生成不透明 ID。 */
    val connectionId: String? = null,
    /** 连接显示名称。 */
    val name: String,
    /** 端点 Base URL。 */
    val baseUrl: String,
    /** 显式绑定的底层协议适配器。 */
    val protocol: AiProtocolKind,
    /** 认证凭据获取模式。 */
    val authMode: AiAuthMode,
    /** 当 [authMode] 为 [AiAuthMode.ENV_VAR] 时配置的环境变量名。 */
    val envVarName: String? = null,
    /** 请求超时秒数。 */
    val timeoutSeconds: Int = AiProviderConnection.DEFAULT_TIMEOUT_SECONDS,
    /** 非敏感自定义请求头。 */
    val customHeaders: Map<String, String> = emptyMap(),
    /** 是否勾选「随工作区加密同步此 API Key」。 */
    val syncKeyInEnvelope: Boolean = false,
    /** 用户本次新输入的 API Key；留空表示保持当前已存密钥不变。 */
    val newApiKeyInput: String = "",
    /** 是否显式清除当前连接已保存的密钥。 */
    val clearSavedApiKey: Boolean = false,
)

/**
 * 面向 `commonMain` 共享 UI 暴露的工作区 Agent 配置、本机隔离凭据与模型探测网关。
 */
interface WorkspaceAgentConfigGateway {
    /**
     * 读取当前已解锁工作区的脱敏 Agent 配置快照与各连接凭据状态。
     *
     * @return 脱敏配置与凭据就绪映射。
     */
    suspend fun load(): WorkspaceAgentConfigSnapshot

    /**
     * 保存（新建或更新）单个 Provider 连接及其分层密钥，并提交一次 `WORKSPACE_CONFIG` 快照事务。
     *
     * @param draft 连接编辑草稿与可选新密钥输入。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun saveConnection(draft: WorkspaceConnectionSaveDraft): WorkspaceAgentConfigSnapshot

    /**
     * 删除指定 Provider 连接、级联移除其下属模型并清理本机隔离密钥。
     *
     * @param connectionId 待删除连接的稳定 ID。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun deleteConnection(connectionId: String): WorkspaceAgentConfigSnapshot

    /**
     * 保存（新增或更新）单个模型及其 `contextWindowTokens`、启用状态与工具能力标记。
     *
     * @param model 目标模型配置。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun saveModel(model: AiModelConfig): WorkspaceAgentConfigSnapshot

    /**
     * 批量导入从 `/models` 拉取的候选模型（已存在的同 ID 模型保持用户原窗口配置不覆盖）。
     *
     * @param connectionId 所属连接 ID。
     * @param remoteModels 待导入的远端模型列表。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun importRemoteModels(
        connectionId: String,
        remoteModels: List<AiRemoteModelInfo>,
    ): WorkspaceAgentConfigSnapshot

    /**
     * 从工作区模型目录中移除指定模型；若其为默认模型则自动清空默认模型引用。
     *
     * @param modelCompositeId 模型复合主键（`connectionId::modelId`）。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun deleteModel(modelCompositeId: String): WorkspaceAgentConfigSnapshot

    /**
     * 保存自动上下文压缩（`80% / 16%`）与单轮安全护栏偏好。
     *
     * @param preferences 待保存的完整偏好参数。
     * @return 更新后的脱敏配置快照。
     */
    suspend fun savePreferences(preferences: AiAgentPreferences): WorkspaceAgentConfigSnapshot

    /**
     * 对已保存连接或未保存表单草稿执行真实连通性与认证测试。
     *
     * @param draft 当前连接草稿（若 `newApiKeyInput` 留空且 `connectionId` 存在，自动复用已存密钥）。
     * @return 脱敏连通性测试结果。
     */
    suspend fun testConnection(draft: WorkspaceConnectionSaveDraft): AiConnectionTestResult

    /**
     * 从已保存连接拉取远端 `/models` 候选列表。
     *
     * @param connectionId 已保存连接 ID。
     * @return 按 `modelId` 排序的远端候选模型列表。
     */
    suspend fun fetchRemoteModels(connectionId: String): List<AiRemoteModelInfo>

    /**
     * 对指定模型执行零笔记最小工具调用闭环实测；若实测成功自动将其 `toolsState` 持久化为 `VERIFIED`。
     *
     * @param modelCompositeId 待实测模型的复合主键（`connectionId::modelId`）。
     * @return 实测结果与更新后的脱敏配置快照。
     */
    suspend fun probeModelToolCalling(
        modelCompositeId: String,
    ): Pair<AiToolProbeResult, WorkspaceAgentConfigSnapshot>
}
