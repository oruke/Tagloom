package com.oruke.tagloom.core.agent

/** 统一执行 UI、CLI、MCP 与内置 Agent 的 scope、目标范围和确认级别判定。 */
object AgentAuthorizationService {
    /**
     * 对一个工具请求执行默认拒绝的确定性授权判定。
     *
     * @param principal 外部 Agent 身份与 scopes。
     * @param request 待调用工具及目标范围。
     * @return 是否允许、基础 scope 和执行前确认级别。
     */
    fun authorize(
        principal: AgentPrincipal,
        request: AgentToolRequest,
    ): AgentAuthorizationDecision {
        val requiredScope = requiredScope(request.tool)
        if (requiredScope !in principal.scopes) {
            return denied(requiredScope, AgentDenialCode.MISSING_SCOPE)
        }
        if (request.itemCount > principal.maxItemsPerCall) {
            return denied(requiredScope, AgentDenialCode.ITEM_LIMIT_EXCEEDED)
        }
        val allowList = principal.allowedNoteIds
        // 受限授权不能把新建对象自动扩展为整个工作区访问范围。
        if (allowList != null && request.tool == AgentToolName.CREATE_NOTE) {
            return denied(requiredScope, AgentDenialCode.TARGET_OUT_OF_SCOPE)
        }
        if (allowList != null && !allowList.containsAll(request.targetNoteIds)) {
            return denied(requiredScope, AgentDenialCode.TARGET_OUT_OF_SCOPE)
        }

        var confirmation = baseConfirmation(request.tool)
        if (request.itemCount > 1 && confirmation != AgentConfirmationLevel.NONE) {
            confirmation = maxConfirmation(confirmation, AgentConfirmationLevel.SECOND)
        }
        return AgentAuthorizationDecision(
            allowed = true,
            confirmationLevel = confirmation,
            requiredScope = requiredScope,
        )
    }

    /**
     * 返回工具的最小基础 scope。
     *
     * @param tool 待调用工具。
     * @return 缺失时必须拒绝的 scope。
     */
    fun requiredScope(tool: AgentToolName): AgentPermissionScope = when (tool) {
        AgentToolName.SEARCH_NOTES,
        AgentToolName.QUERY_NOTES,
        AgentToolName.GET_BACKLINKS,
        AgentToolName.RUN_SAVED_QUERY,
        -> AgentPermissionScope.SEARCH_NOTES

        AgentToolName.GET_NOTE,
        AgentToolName.GET_BLOCK,
        AgentToolName.GET_TAGS,
        AgentToolName.GET_TAG_SCHEMA,
        AgentToolName.GET_WORKSPACE_SCHEMA,
        AgentToolName.LIST_NOTES,
        AgentToolName.GET_SAVED_QUERIES,
        AgentToolName.GET_SAVED_QUERY,
        -> AgentPermissionScope.READ_NOTES

        AgentToolName.SUGGEST_TAGS,
        AgentToolName.SUGGEST_PROPERTIES,
        -> AgentPermissionScope.SUGGEST_CHANGES

        AgentToolName.CREATE_NOTE,
        AgentToolName.UPDATE_NOTE,
        AgentToolName.ADD_TAG_TO_NOTE,
        AgentToolName.PATCH_NOTE_BLOCKS,
        AgentToolName.UPDATE_NOTE_METADATA,
        -> AgentPermissionScope.WRITE_NOTES

        AgentToolName.DELETE_NOTE -> AgentPermissionScope.DELETE_NOTES
        AgentToolName.EXPORT_NOTE -> AgentPermissionScope.EXPORT_NOTES
    }

    /**
     * 返回单条本地请求的基础确认级别。
     *
     * @param tool 待调用工具。
     * @return 默认确认强度。
     */
    private fun baseConfirmation(tool: AgentToolName): AgentConfirmationLevel = when (tool) {
        AgentToolName.CREATE_NOTE,
        AgentToolName.UPDATE_NOTE,
        AgentToolName.ADD_TAG_TO_NOTE,
        AgentToolName.PATCH_NOTE_BLOCKS,
        AgentToolName.UPDATE_NOTE_METADATA,
        -> AgentConfirmationLevel.SINGLE

        AgentToolName.DELETE_NOTE,
        AgentToolName.EXPORT_NOTE,
        -> AgentConfirmationLevel.SECOND

        else -> AgentConfirmationLevel.NONE
    }

    /**
     * 选择两个确认级别中更严格的一项。
     *
     * @param left 第一确认级别。
     * @param right 第二确认级别。
     * @return 更严格的确认级别。
     */
    private fun maxConfirmation(
        left: AgentConfirmationLevel,
        right: AgentConfirmationLevel,
    ): AgentConfirmationLevel = if (left.ordinal >= right.ordinal) left else right

    /**
     * 创建不允许执行的稳定授权结果。
     *
     * @param requiredScope 工具要求的基础 scope。
     * @param code 稳定拒绝原因。
     * @return 无确认机会的拒绝结果。
     */
    private fun denied(
        requiredScope: AgentPermissionScope,
        code: AgentDenialCode,
    ): AgentAuthorizationDecision = AgentAuthorizationDecision(
        allowed = false,
        confirmationLevel = AgentConfirmationLevel.NONE,
        requiredScope = requiredScope,
        denialCode = code,
    )
}
