package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.Serializable

/**
 * 标识模型连接绑定的明确流式协议适配器类别，严禁通过 URL 路径后缀猜测协议。
 */
@Serializable
enum class AiProtocolKind {
    /** OpenAI Responses API（`/responses` 语义化 SSE 事件流）。 */
    OPENAI_RESPONSES,

    /** OpenAI Chat Completions API（`/chat/completions` 兼容端点 SSE 流）。 */
    OPENAI_CHAT_COMPLETIONS,
}

/**
 * 标识模型连接的认证凭据获取模式。
 */
@Serializable
enum class AiAuthMode {
    /** 使用系统安全凭据库（DPAPI / Android Keystore）或可选工作区加密信封中的 API Key。 */
    KEYSTORE,

    /** 在桌面端运行时按环境变量名读取密钥，不把真实密钥写入工作区快照。 */
    ENV_VAR,

    /** 本机免密端点（如本机 Ollama / LM Studio），不附带 Authorization 头。 */
    NONE,
}

/**
 * 诚实标识模型对函数工具调用的支持等级，禁止对 `CHAT_ONLY` 模型用正则伪造工具调用。
 */
@Serializable
enum class AiModelToolsState {
    /** 已通过零笔记最小函数调用闭环实测验证。 */
    VERIFIED,

    /** 由用户或预设声明支持函数工具调用，尚未执行实测。 */
    DECLARED,

    /** 仅支持纯文本对话，自动禁用全库检索工具与结构化写入提案。 */
    CHAT_ONLY,
}

/**
 * 定义新会话默认的知识库检索暴露范围。
 */
@Serializable
enum class AiRetrievalScope {
    /** 默认仅读取用户显式 `@` 挂载的笔记、段落、类型或保存查询范围。 */
    CITED_ONLY,

    /** 允许在当前工作区所有有效策略为 `ALLOW` 的活动笔记内按需调用检索工具。 */
    ALL_NOTES,
}

/**
 * 定义推理模型的深度思考强度档位。
 */
@Serializable
enum class AiThinkingEffort {
    /** 关闭深度思考。 */
    OFF,

    /** 低推理预算。 */
    LOW,

    /** 中等推理预算。 */
    MEDIUM,

    /** 高推理预算。 */
    HIGH,
}

/**
 * 描述一个可随 `WORKSPACE_CONFIG` 加密快照跨设备同步的 Provider 连接骨架与可选同步密钥。
 */
@Serializable
data class AiProviderConnection(
    /** 工作区内唯一且稳定的连接标识，用于三方合并定位与本机凭据关联。 */
    val connectionId: String,
    /** 用户可读连接名称（如 `OpenAI`、`DeepSeek`、`本机 Ollama`）。 */
    val name: String,
    /** 基础 HTTP/HTTPS 端点地址，不得内嵌账号密码或查询参数密钥。 */
    val baseUrl: String,
    /** 显式绑定的底层协议适配器类型。 */
    val protocol: AiProtocolKind,
    /** 认证模式；默认使用系统安全凭据库隔离存储。 */
    val authMode: AiAuthMode = AiAuthMode.KEYSTORE,
    /** 当 [authMode] 为 [AiAuthMode.ENV_VAR] 时读取的桌面环境变量名。 */
    val envVarName: String? = null,
    /** 单次连接与流读取超时秒数，合法范围 `5..600`。 */
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** 非敏感自定义请求头映射，严禁存放 `Authorization` 等认证头。 */
    val customHeaders: Map<String, String> = emptyMap(),
    /** 是否显式勾选将该连接的 API Key 纳入工作区 `EncryptionEnvelope` 跨端同步。 */
    val syncKeyInEnvelope: Boolean = false,
    /** 仅当 [authMode] 为 [AiAuthMode.KEYSTORE] 且 [syncKeyInEnvelope] 为真时允许承载的加密快照内密钥。 */
    val syncedApiKey: String? = null,
) {
    companion object {
        /** 默认网络请求超时秒数。 */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 60

        /** 最小允许请求超时秒数。 */
        const val MIN_TIMEOUT_SECONDS: Int = 5

        /** 最大允许请求超时秒数。 */
        const val MAX_TIMEOUT_SECONDS: Int = 600
    }
}

/**
 * 描述挂载在特定连接下的单个模型配置、能力分级及独立上下文窗口大小。
 */
@Serializable
data class AiModelConfig(
    /** 所属 Provider 连接的稳定 `connectionId`。 */
    val connectionId: String,
    /** 发送给远端接口的原始模型标识符（如 `gpt-4.1`、`deepseek-chat`）。 */
    val modelId: String,
    /** 界面展示名称。 */
    val displayName: String,
    /** 供三方合并按 `(connectionId, modelId)` 唯一定位的组合身份，格式固定为 `connectionId::modelId`。 */
    val id: String = composeModelConfigId(connectionId, modelId),
    /** 该模型的上下文窗口总 Token 容量，合法范围 `16_000..1_048_576`（`16K ~ 1M`）。 */
    val contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    /** 是否支持 SSE 流式增量返回。 */
    val streamingSupported: Boolean = true,
    /** 函数工具调用能力状态。 */
    val toolsState: AiModelToolsState = AiModelToolsState.DECLARED,
    /** 模型支持的可选非关闭思考强度档位列表（不含 [AiThinkingEffort.OFF]）。 */
    val supportedThinkingEfforts: List<AiThinkingEffort> = emptyList(),
    /** 是否在对话作曲栏的模型切换列表中启用。 */
    val enabled: Boolean = true,
) {
    /**
     * 按给定百分比向下取整计算触发自动上下文压缩的 Token 阈值。
     *
     * @param autoCompactPercent 自动压缩触发百分比（如默认 `80`）。
     * @return 触发自动摘要压缩的活跃上下文 Token 水位线。
     */
    fun autoCompactThresholdTokens(
        autoCompactPercent: Int = AiAgentPreferences.DEFAULT_AUTO_COMPACT_PERCENT,
    ): Int = ((contextWindowTokens.toLong() * autoCompactPercent.coerceIn(1, 100)) / 100L).toInt()

    companion object {
        /** 最小支持的模型上下文窗口（16K）。 */
        const val MIN_CONTEXT_WINDOW_TOKENS: Int = 16_000

        /** 最大支持的模型上下文窗口（兼容十进制 1M 与二进制 1Mi = 1,048,576）。 */
        const val MAX_CONTEXT_WINDOW_TOKENS: Int = 1_048_576

        /** 默认模型上下文窗口（128K）。 */
        const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 128_000

        /** 设置页提供的常用上下文窗口快捷档位（16K / 32K / 64K / 128K / 200K / 256K / 1M）。 */
        val PRESET_CONTEXT_WINDOWS: List<Int> = listOf(
            16_000,
            32_000,
            64_000,
            128_000,
            200_000,
            256_000,
            1_000_000,
        )

        /**
         * 构造模型配置在工作区快照中的确定性复合身份。
         *
         * @param connectionId 所属连接 ID。
         * @param modelId 原始模型 ID。
         * @return 形如 `connectionId::modelId` 的复合主键。
         */
        fun composeModelConfigId(connectionId: String, modelId: String): String =
            "${connectionId.trim()}::${modelId.trim()}"
    }
}

/**
 * 描述工作区共享的 Agent 默认运行偏好、单轮检索护栏与 80% 自动上下文压缩参数。
 */
@Serializable
data class AiAgentPreferences(
    /** 新建会话优先选中的模型复合 ID（对应 [AiModelConfig.id]）；为空或失效时回退首个已启用模型。 */
    val defaultModelId: String? = null,
    /** 新建会话默认检索暴露范围。 */
    val defaultScope: AiRetrievalScope = AiRetrievalScope.CITED_ONLY,
    /** 单轮用户提问允许的最大连续工具调用步数，合法范围 `1..20`。 */
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /** 单轮上下文预算最多注入的笔记篇数，合法范围 `1..100`。 */
    val maxNotes: Int = DEFAULT_MAX_NOTES,
    /** 单轮上下文预算最多注入的段落块数，合法范围 `1..500`。 */
    val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    /** 单次工具读取或注入的最大字符安全保护上限，合法范围 `1_000..200_000`。 */
    val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    /** 是否在回合完成后达到窗口阈值时自动执行无损脉络摘要压缩。 */
    val autoCompactEnabled: Boolean = true,
    /** 触发自动上下文压缩的模型窗口占用百分比，默认 `80`（合法范围 `50..95`）。 */
    val autoCompactPercent: Int = DEFAULT_AUTO_COMPACT_PERCENT,
    /** 自动压缩后强制逐字保留的近期对话窗口百分比，默认 `16`（合法范围 `8..40`）。 */
    val retainPercent: Int = DEFAULT_RETAIN_PERCENT,
) {
    /**
     * 将当前偏好中的单轮读取护栏转换为领域层 [AiContextBudget]。
     *
     * @return 与当前偏好参数一致的上下文裁剪预算。
     */
    fun toContextBudget(): AiContextBudget = AiContextBudget(
        maxNotes = maxNotes,
        maxBlocks = maxBlocks,
        maxCharacters = maxCharacters,
    )

    companion object {
        /** 默认单轮最大工具循环步数。 */
        const val DEFAULT_MAX_TOOL_ROUNDS: Int = 6

        /** 默认单轮最大注入笔记数。 */
        const val DEFAULT_MAX_NOTES: Int = 8

        /** 默认单轮最大注入块数。 */
        const val DEFAULT_MAX_BLOCKS: Int = 32

        /** 默认单次注入字符数保护上限。 */
        const val DEFAULT_MAX_CHARACTERS: Int = 12_000

        /** 默认触发自动压缩的上下文窗口百分比（80%）。 */
        const val DEFAULT_AUTO_COMPACT_PERCENT: Int = 80

        /** 默认自动压缩保留近期窗口百分比（16%）。 */
        const val DEFAULT_RETAIN_PERCENT: Int = 16
    }
}

/**
 * 工作区级 Agent 与模型权威加密快照载荷（对应 `ObjectType.WORKSPACE_CONFIG`）。
 *
 * 外层由 `WrappingKeyPurpose.SNAPSHOT` + 随机 DEK 的 `EncryptionEnvelope` 保护，支持主密钥轮换与细粒度三方合并。
 */
@Serializable
data class WorkspaceConfig(
    /** 配置载荷协议版本号。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 单例工作区配置固定对象 ID（固定为 [WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID]）。 */
    val id: ObjectId = WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID,
    /** 已配置的 Provider 连接列表，按 `connectionId` 执行逐成员三方合并。 */
    val connections: List<AiProviderConnection> = emptyList(),
    /** 已配置的模型及各自上下文窗口目录，按 `id`（`connectionId::modelId`）执行逐成员三方合并。 */
    val models: List<AiModelConfig> = emptyList(),
    /** 工作区默认模型、单轮护栏与自动压缩运行偏好。 */
    val preferences: AiAgentPreferences = AiAgentPreferences(),
) {
    companion object {
        /** 当前 `WorkspaceConfig` schema 版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
