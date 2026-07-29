package com.oruke.tagloom.core.agent

import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 定义外部 Agent 可以被单独授予的最小权限 scope。 */
@Serializable
enum class AgentPermissionScope {
    /** 读取授权 Note。 */
    READ_NOTES,

    /** 使用全文与结构化索引。 */
    SEARCH_NOTES,

    /** 生成但不直接应用修改建议。 */
    SUGGEST_CHANGES,

    /** 创建或更新 Note。 */
    WRITE_NOTES,

    /** 创建 tombstone 或删除 Note。 */
    DELETE_NOTES,

    /** 导出授权内容。 */
    EXPORT_NOTES,

    /** 把明确授权的最小上下文发送到远端 AI。 */
    CALL_REMOTE_AI,
}

/** 定义 CLI、MCP 与内置 Agent 共享的稳定知识工具名称。 */
@Serializable
enum class AgentToolName {
    /** 全文搜索 Note。 */
    SEARCH_NOTES,

    /** 按标签和属性查询 Note。 */
    QUERY_NOTES,

    /** 获取一篇 Note。 */
    GET_NOTE,

    /** 获取一个 Block。 */
    GET_BLOCK,

    /** 获取反向链接。 */
    GET_BACKLINKS,

    /** 获取 Tag 列表。 */
    GET_TAGS,

    /** 获取 Tag 属性 schema。 */
    GET_TAG_SCHEMA,

    /** 建议 Tag，不直接写入。 */
    SUGGEST_TAGS,

    /** 建议属性，不直接写入。 */
    SUGGEST_PROPERTIES,

    /** 创建 Note。 */
    CREATE_NOTE,

    /** 更新 Note。 */
    UPDATE_NOTE,

    /** 给 Note 添加 Tag。 */
    ADD_TAG_TO_NOTE,

    /** 删除 Note 或 Block。 */
    DELETE_NOTE,

    /** 导出 Note。 */
    EXPORT_NOTE,

    /** 调用远端 AI Gateway。 */
    CALL_REMOTE_AI,
}

/** 定义工具执行前需要的用户确认强度。 */
@Serializable
enum class AgentConfirmationLevel {
    /** 权限校验通过后可直接执行。 */
    NONE,

    /** 展示预览后需要一次明确确认。 */
    SINGLE,

    /** 批量、删除、导出或远程发送需要二次确认。 */
    SECOND,
}

/** 定义稳定且不泄露内容的授权拒绝原因。 */
@Serializable
enum class AgentDenialCode {
    /** 缺少工具基础 scope。 */
    MISSING_SCOPE,

    /** 目标 Note 超出 principal allow-list。 */
    TARGET_OUT_OF_SCOPE,

    /** 请求条目数超过 principal 单次上限。 */
    ITEM_LIMIT_EXCEEDED,

    /** 请求远程发送但缺少远端 AI scope。 */
    REMOTE_AI_NOT_ALLOWED,
}

/** 描述一个外部 Agent 的稳定身份与最小授权范围。 */
data class AgentPrincipal(
    /** 随机且不含应用名、账号或邮箱的 principal ID。 */
    val principalId: String,
    /** 明确授予的最小权限集合。 */
    val scopes: Set<AgentPermissionScope>,
    /** 可访问 Note allow-list；为空表示 scope 覆盖整个当前工作区。 */
    val allowedNoteIds: Set<ObjectId>? = null,
    /** 单次工具调用最大返回或修改条目数。 */
    val maxItemsPerCall: Int = 100,
) {
    init {
        require(principalId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Agent principal ID 格式无效" }
        require(maxItemsPerCall in 1..500) { "Agent 单次条目上限必须在 1 到 500 之间" }
    }
}

/** 描述一次待授权的 Agent 工具调用。 */
data class AgentToolRequest(
    /** 调用方生成的随机请求 ID，用于审计幂等关联。 */
    val requestId: String,
    /** 待调用工具。 */
    val tool: AgentToolName,
    /** 请求直接访问或修改的随机 Note IDs。 */
    val targetNoteIds: Set<ObjectId> = emptySet(),
    /** 预计读取、返回或修改的条目数。 */
    val itemCount: Int = 1,
    /** 本次请求是否会把任何明文发送到工作区之外。 */
    val sendsContentRemotely: Boolean = false,
    /** 工具协议参数；只在解锁客户端内存和加密审计中存在。 */
    val arguments: JsonElement? = null,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Agent 请求 ID 格式无效" }
        require(itemCount in 1..500) { "Agent 请求条目数必须在 1 到 500 之间" }
    }
}

/** 描述授权判定及执行前确认要求。 */
data class AgentAuthorizationDecision(
    /** 当前请求是否在 principal 权限范围内。 */
    val allowed: Boolean,
    /** 允许时执行前所需确认强度。 */
    val confirmationLevel: AgentConfirmationLevel,
    /** 工具要求的基础 scope。 */
    val requiredScope: AgentPermissionScope,
    /** 拒绝时的稳定原因；允许时为空。 */
    val denialCode: AgentDenialCode? = null,
)
