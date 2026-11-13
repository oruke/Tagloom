package com.oruke.tagloom.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 标识多轮流式模型对话中的消息角色。
 */
@Serializable
enum class AiChatRole {
    /** 系统指令或工作区护栏说明。 */
    SYSTEM,

    /** 用户提问或挂载的引用上下文。 */
    USER,

    /** 模型回复或发起的工具调用记录。 */
    ASSISTANT,

    /** 工具执行完成后的结构化结果回传。 */
    TOOL,
}

/**
 * 描述模型发起的一次完整结构化函数工具调用。
 */
@Serializable
data class AiToolCall(
    /** 供后续工具结果消息关联的唯一调用 ID。 */
    val callId: String,
    /** 目标工具名称。 */
    val name: String,
    /** 工具入参的原始 JSON 文本。 */
    val argumentsJson: String,
)

/**
 * 描述传递给流式模型端口的一条标准化消息。
 */
@Serializable
data class AiChatMessage(
    /** 消息角色。 */
    val role: AiChatRole,
    /** 消息文本内容；纯工具调用轮次可为空字符串。 */
    val content: String = "",
    /** 当 [role] 为 [AiChatRole.ASSISTANT] 时可能携带的工具调用列表。 */
    val toolCalls: List<AiToolCall> = emptyList(),
    /** 当 [role] 为 [AiChatRole.TOOL] 时对应的 [AiToolCall.callId]。 */
    val toolCallId: String? = null,
    /** 当 [role] 为 [AiChatRole.TOOL] 时对应的工具函数名称。 */
    val toolName: String? = null,
)

/**
 * 描述向支持工具调用的模型声明的一个受控工具函数定义。
 */
data class AiToolDefinition(
    /** 稳定工具名称（如 `search_notes`、`patch_note_blocks`）。 */
    val name: String,
    /** 工具职责与调用边界说明。 */
    val description: String,
    /** 符合 JSON Schema Object 规范的参数结构定义。 */
    val parametersSchema: JsonObject,
)

/**
 * 描述一次可取消的模型流式推理请求。
 *
 * [resolvedApiKey] 仅在已解锁会话内存中按需解析传入，适配器严禁将其写入日志或异常消息。
 */
data class AiStreamRequest(
    /** 目标 Provider 连接配置。 */
    val connection: AiProviderConnection,
    /** 当前会话内存中按需解析的 API Key；免密端点为空。 */
    val resolvedApiKey: String?,
    /** 目标远端原始 `modelId`。 */
    val modelId: String,
    /** 按对话顺序排列的上下文消息序列。 */
    val messages: List<AiChatMessage>,
    /** 本轮暴露给模型的受控工具定义；`CHAT_ONLY` 模型应传空列表。 */
    val tools: List<AiToolDefinition> = emptyList(),
    /** 请求启用的深度思考档位。 */
    val thinkingEffort: AiThinkingEffort = AiThinkingEffort.OFF,
    /** 可选采样温度参数。 */
    val temperature: Double? = null,
    /** 可选最大输出 Token 上限。 */
    val maxOutputTokens: Int? = null,
)

/**
 * 精确记录单轮模型调用返回的输入、前缀缓存命中、输出与推理思考 Token 用量明细。
 */
@Serializable
data class AiTokenUsage(
    /** 未命中前缀缓存的输入 Token 数量（`>= 0`）。 */
    val uncachedInputTokens: Int = 0,
    /** 命中前缀缓存读取的输入 Token 数量（`>= 0`）。 */
    val cacheReadTokens: Int = 0,
    /** 模型总输出 Token 数量（包含 [reasoningTokens]，`>= 0`）。 */
    val outputTokens: Int = 0,
    /** 输出中包含的深度思考推理 Token 数量（`>= 0`）。 */
    val reasoningTokens: Int = 0,
) {
    init {
        require(uncachedInputTokens >= 0) { "未缓存输入 Token 不能为负数" }
        require(cacheReadTokens >= 0) { "缓存读取 Token 不能为负数" }
        require(outputTokens >= 0) { "输出 Token 不能为负数" }
        require(reasoningTokens >= 0) { "推理 Token 不能为负数" }
    }

    /** 本轮总输入 Token 数（未命中输入 + 缓存读取）。 */
    val totalInputTokens: Int
        get() = uncachedInputTokens + cacheReadTokens

    /** 本轮合计消耗总 Token 数（总输入 + 总输出）。 */
    val totalTokens: Int
        get() = totalInputTokens + outputTokens

    /**
     * 计算本轮输入前缀缓存命中率（`0.0..1.0`）；总输入为 `0` 时返回空。
     *
     * @return 前缀缓存命中比例，无输入统计时为空。
     */
    fun cacheHitRatio(): Double? {
        val input = totalInputTokens
        return if (input <= 0) null else cacheReadTokens.toDouble() / input.toDouble()
    }

    /**
     * 将两次调用的计费账单累加合并（用于多步工具循环或会话累计统计）。
     *
     * @param other 另一轮调用返回的 Token 明细。
     * @return 累加后的累计 Token 账单。
     */
    operator fun plus(other: AiTokenUsage): AiTokenUsage = AiTokenUsage(
        uncachedInputTokens = uncachedInputTokens + other.uncachedInputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
    )
}

/**
 * 标识单轮流式生成的结束原因。
 */
@Serializable
enum class AiFinishReason {
    /** 模型正常完成文本回答。 */
    STOP,

    /** 模型请求执行一个或多个工具调用。 */
    TOOL_CALLS,

    /** 达到输出最大长度限制被截断。 */
    LENGTH,

    /** 被远端服务内容过滤器中断。 */
    CONTENT_FILTER,

    /** 被客户端用户点击停止或工作区锁定主动取消。 */
    CANCELLED,

    /** 服务端未提供明确原因或未知枚举。 */
    UNKNOWN,
}

/**
 * 流式模型端口在单次推理生命周期内按序发出的标准化增量事件。
 */
sealed interface AiStreamEvent {
    /**
     * 正文增量文本片段。
     */
    data class TextDelta(
        /** 新增的正文文本块。 */
        val text: String,
    ) : AiStreamEvent

    /**
     * 深度思考摘要或推理链增量片段。
     */
    data class ThinkingDelta(
        /** 新增的思考摘要文本块。 */
        val text: String,
    ) : AiStreamEvent

    /**
     * 模型开始构造一个新的函数工具调用项。
     */
    data class ToolCallStarted(
        /** 当前批次中的工具调用槽位索引。 */
        val index: Int,
        /** 工具调用关联 ID。 */
        val callId: String,
        /** 工具函数名。 */
        val name: String,
    ) : AiStreamEvent

    /**
     * 工具调用 JSON 参数的字符串增量片段。
     */
    data class ToolCallArgumentsDelta(
        /** 当前批次中的工具调用槽位索引。 */
        val index: Int,
        /** 工具调用关联 ID（若流事件已携带）。 */
        val callId: String?,
        /** 新增的参数 JSON 片段。 */
        val argumentsDelta: String,
    ) : AiStreamEvent

    /**
     * 单个工具调用的参数已完整收齐，可供 Harness 执行权限校验与调用。
     */
    data class ToolCallReady(
        /** 组装完毕的工具调用对象。 */
        val toolCall: AiToolCall,
    ) : AiStreamEvent

    /**
     * 整轮流式响应正常收尾，携带最终累计的精确 Token `usage` 与结束原因。
     */
    data class Completed(
        /** 服务端在流末尾回传的本轮 Token 用量；部分本机兼容服务未返回时为空。 */
        val usage: AiTokenUsage?,
        /** 本轮结束原因。 */
        val finishReason: AiFinishReason,
    ) : AiStreamEvent
}

/**
 * 稳定、脱敏的流式模型调用错误分类，供 UI 呈现明确的恢复指引。
 */
enum class AiErrorKind {
    /** 认证失败（401 或缺少所需 API Key）。 */
    UNAUTHORIZED,

    /** 权限不足或配额被拒（403）。 */
    FORBIDDEN,

    /** 端点路径或模型不存在（404）。 */
    NOT_FOUND,

    /** 触发速率或并发配额限制（429）。 */
    RATE_LIMITED,

    /** 请求参数不被目标模型或协议接受（400 / 422）。 */
    INVALID_REQUEST,

    /** 远端供应商内部错误（5xx）。 */
    SERVER_ERROR,

    /** 连接或流读取超时。 */
    TIMEOUT,

    /** 本机网络不可达、DNS 失败或连接中断。 */
    NETWORK,

    /** 返回内容不符合绑定协议的 SSE / JSON 结构。 */
    PROTOCOL,

    /** 调用被协程取消。 */
    CANCELLED,
}

/**
 * 模型通信层抛出的脱敏领域异常。
 *
 * 异常消息严禁包含真实 API Key、完整请求头、用户输入草稿或笔记明文。
 *
 * @param kind 稳定错误分类。
 * @param statusCode 可选 HTTP 状态码。
 * @param diagnosticCode 稳定的简短诊断编码，不含敏感载荷。
 */
class AiProviderException(
    /** 稳定错误分类。 */
    val kind: AiErrorKind,
    /** 可选 HTTP 响应状态码。 */
    val statusCode: Int? = null,
    /** 不含密钥或笔记明文的简短诊断标识。 */
    val diagnosticCode: String = kind.name.lowercase(),
) : RuntimeException(diagnosticCode)

/**
 * 描述从 Provider `/models` 端点拉取到的候选模型元数据。
 */
data class AiRemoteModelInfo(
    /** 远端原始 `modelId`。 */
    val modelId: String,
    /** 展示名称。 */
    val displayName: String,
    /** 根据已知模型族推断的初始上下文窗口大小。 */
    val suggestedContextWindowTokens: Int,
    /** 根据已知推理模型族推断的可选思考档位。 */
    val suggestedThinkingEfforts: List<AiThinkingEffort> = emptyList(),
)

/**
 * 描述一次 API 连接可达性与认证测试的脱敏结果。
 */
data class AiConnectionTestResult(
    /** 端点是否成功响应并完成认证校验。 */
    val reachable: Boolean,
    /** 往返耗时毫秒数。 */
    val latencyMillis: Long,
    /** 若 `/models` 端点可用，返回发现的模型数量；端点未实现 `/models` 但协议可达时为空。 */
    val remoteModelCount: Int? = null,
)

/**
 * 描述一次无笔记最小函数调用实测（`probeToolCalling`）的验证结果。
 */
data class AiToolProbeResult(
    /** 模型是否真正返回了预期的结构化函数调用。 */
    val supported: Boolean,
    /** 实测命中的探测函数名。 */
    val invokedToolName: String? = null,
    /** 实测请求消耗的少量 Token 明细。 */
    val usage: AiTokenUsage? = null,
)

/**
 * 统一的可取消流式模型推理、连接检测、模型拉取与工具能力实测端口。
 *
 * 严格遵循连接显式配置的 [AiProtocolKind] 路由到底层协议适配器，取消收集协程时必须立即中止在途网络连接。
 */
interface AiStreamPort {
    /**
     * 发起冷流式推理请求；取消收集器协程将立即关闭底层 HTTP/SSE 流。
     *
     * @param request 包含连接配置、按需内存密钥、消息列表与工具定义的完整请求。
     * @return 按到达顺序发出的标准化增量事件流。
     */
    fun stream(request: AiStreamRequest): Flow<AiStreamEvent>

    /**
     * 校验指定 Provider 连接的网络可达性与认证有效性，不发送任何工作区笔记数据。
     *
     * @param connection 待测试连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥；免密模式为空。
     * @return 连接测试耗时与可用模型数摘要。
     */
    suspend fun testConnection(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
    ): AiConnectionTestResult

    /**
     * 调用连接端点的 `/models` 接口拉取候选模型目录。
     *
     * @param connection 目标连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥；免密模式为空。
     * @return 按 `modelId` 稳定排序的远端候选模型列表。
     */
    suspend fun listModels(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
    ): List<AiRemoteModelInfo>

    /**
     * 使用零笔记最小探测函数发起一次真实请求，实测目标模型是否具备结构化工具调用能力。
     *
     * @param connection 目标连接配置。
     * @param resolvedApiKey 当前会话内存解析的密钥；免密模式为空。
     * @param modelId 待实测的远端原始 `modelId`。
     * @return 是否成功触发结构化工具调用的实测结果。
     */
    suspend fun probeToolCalling(
        connection: AiProviderConnection,
        resolvedApiKey: String?,
        modelId: String,
    ): AiToolProbeResult
}
