package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.ExternalToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 统一表示一个工具调用，目标和风险信息由业务层从参数推导。 */
@Serializable
data class LocalCallInput(
    /** 稳定工具名称。 */
    val tool: String,
    /** 严格匹配工具 schema 的参数。 */
    val arguments: JsonObject = JsonObject(emptyMap()),
    /** 可选随机幂等键，仅在当前解锁会话与有效期内去重。 */
    val requestId: String? = null,
)

/** 描述立即完成、等待持有者批准或已拒绝的调用。 */
@Serializable
data class LocalCallResponse(
    /** 审计请求与当前调用身份。 */
    val callId: String,
    /** completed、pending 或 canceled。 */
    val state: String,
    /** 需要的持有者确认强度。 */
    val confirmationLevel: AgentConfirmationLevel,
    /** 不包含正文的风险预览。 */
    val preview: AgentToolPreview? = null,
    /** 只有实际执行后才存在的结构化结果。 */
    val result: AgentToolResult? = null,
)

/** 描述持有者授予当前会话的最小外部访问范围。 */
@Serializable
data class LocalGrantInput(
    /** 默认只允许笔记读取和查询。 */
    val scopes: Set<AgentPermissionScope> = setOf(AgentPermissionScope.READ_NOTES, AgentPermissionScope.SEARCH_NOTES),
    /** 指定 Note allow-list；空值表示当前工作区，空集合表示无笔记权限。 */
    val allowedNoteIds: Set<com.oruke.tagloom.core.model.ObjectId>? = null,
    /** 单次最多返回或修改的数量。 */
    val maxItemsPerCall: Int = 100,
    /** 是否额外发现删除工具；仍要求 DELETE_NOTES scope 和二次确认。 */
    val includeDangerous: Boolean = false,
)

/** 返回新授权身份与只展示一次的 Token，禁止写入日志。 */
@Serializable
data class LocalGrantResponse(
    /** 随机 principal 身份，同时用于撤销授权。 */
    val principalId: String,
    /** 仅本次响应包含的高熵访问凭据。 */
    val token: String,
    /** 随机解锁会话身份。 */
    val sessionId: String,
)

/** 返回当前 Token 可发现且已真实实现的工具。 */
@Serializable
data class LocalToolList(
    /** 当前已解锁会话，用于客户端检测切换。 */
    val sessionId: String,
    /** 最小身份和 scope，不包含 Token。 */
    val principal: AgentPrincipal,
    /** scope 过滤后的工具 schema。 */
    val tools: List<ExternalToolDefinition>,
)

/** 仅 owner 能提交的确认事实，Agent Token 不具备此端点权限。 */
@Serializable
data class LocalApprovalInput(
    /** 是否确认已审阅具体变化。 */
    val firstConfirmed: Boolean,
    /** 是否在删除、导出等风险说明后再次确认。 */
    val secondConfirmed: Boolean = false,
)

/** 返回 RFC Problem Details 和稳定业务失败码，不携带底层异常文本。 */
@Serializable
data class LocalApiProblem(
    /** 错误类别的稳定 URI。 */
    val type: String,
    /** 可安全显示的失败代码。 */
    val title: String,
    /** HTTP 状态。 */
    val status: Int,
    /** 用于程序判定的稳定错误码。 */
    val code: String,
)

/** 统一承载集合分页信息。 */
@Serializable
data class LocalJsonPage(
    /** 当前页对象。 */
    val items: List<JsonObject>,
    /** 是否仍有下一页。 */
    val hasMore: Boolean,
    /** 下一页偏移，无更多时为空。 */
    val nextOffset: Int?,
)
