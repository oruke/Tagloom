package com.oruke.tagloom.core.agent

import kotlinx.serialization.json.JsonElement

/** 定义 Agent 工具调用的稳定审计结果。 */
enum class AgentAuditOutcome {
    /** scope 或目标范围拒绝。 */
    DENIED,

    /** 预览后用户没有完成所需确认。 */
    CANCELED,

    /** 工具已成功执行。 */
    EXECUTED,

    /** 预览或执行发生脱敏错误。 */
    FAILED,
}

/** 描述执行前展示给用户的最小变更或数据发送预览。 */
data class AgentToolPreview(
    /** 不包含正文的预览标题。 */
    val title: String,
    /** 预计读取、修改或发送的条目数。 */
    val affectedItems: Int,
    /** 是否包含 tombstone 或物理删除意图。 */
    val destructive: Boolean,
    /** 是否会把任何明文发送到工作区之外。 */
    val sendsContentRemotely: Boolean,
) {
    init {
        require(title.isNotBlank()) { "Agent 预览标题不能为空" }
        require(affectedItems >= 0) { "Agent 预览条目数不能为负数" }
    }
}

/** 保存用户对一次已预览请求给出的确认事实。 */
data class AgentConfirmationEvidence(
    /** 必须与预览请求一致的随机请求 ID。 */
    val requestId: String,
    /** 用户是否完成第一次明确确认。 */
    val firstConfirmed: Boolean,
    /** 用户是否完成风险说明后的第二次确认。 */
    val secondConfirmed: Boolean,
    /** 确认时间，使用 UTC ISO-8601 文本。 */
    val confirmedAt: String,
) {
    init {
        require(confirmedAt.isNotBlank()) { "Agent 确认时间不能为空" }
    }
}

/** 描述不包含密钥或 Token 的本地加密审计事件。 */
data class AgentAuditEvent(
    /** 原始工具请求 ID。 */
    val requestId: String,
    /** 外部 Agent principal ID。 */
    val principalId: String,
    /** 被调用工具。 */
    val tool: AgentToolName,
    /** 执行结果。 */
    val outcome: AgentAuditOutcome,
    /** 随机目标 Note IDs。 */
    val targetNoteIds: Set<com.oruke.tagloom.core.model.ObjectId>,
    /** 是否涉及远程发送明文。 */
    val sentContentRemotely: Boolean,
    /** 事件时间，使用 UTC ISO-8601 文本。 */
    val occurredAt: String,
) {
    init {
        require(occurredAt.isNotBlank()) { "Agent 审计时间不能为空" }
    }
}

/** 描述工具执行返回的结构化结果。 */
data class AgentToolResult(
    /** 工具是否完成预期业务结果。 */
    val succeeded: Boolean,
    /** 只在解锁调用方内存中存在的结构化结果。 */
    val payload: JsonElement? = null,
    /** 失败时不含正文或底层异常消息的稳定错误码。 */
    val errorCode: String? = null,
) {
    init {
        require(succeeded || !errorCode.isNullOrBlank()) { "失败的 Agent 结果必须包含稳定错误码" }
    }
}

/** 抽象 Agent 工具的只读预览与受控执行。 */
interface AgentToolExecutionPort {
    /**
     * 构造无副作用预览。
     *
     * @param request 已通过 scope 判定的工具请求。
     * @return 面向用户的最小预览。
     */
    suspend fun preview(request: AgentToolRequest): AgentToolPreview

    /**
     * 执行已确认请求并持久化成功审计事件。
     *
     * 写入工具实现必须把 [successAuditEvent] 与业务 Change 放入同一加密事务；只读工具可以在返回前写入独立加密审计记录。
     *
     * @param request 已授权且确认充分的原始请求。
     * @param successAuditEvent 必须与业务结果一起确认的成功审计事件。
     * @return 结构化工具结果。
     */
    suspend fun execute(
        request: AgentToolRequest,
        successAuditEvent: AgentAuditEvent,
    ): AgentToolResult
}

/** 抽象未执行、拒绝与失败调用的加密审计存储。 */
fun interface AgentAuditPort {
    /**
     * 持久化不包含正文、参数、Token 或密钥的审计事件。
     *
     * @param event 待写入本地加密历史的事件。
     */
    suspend fun append(event: AgentAuditEvent)
}

/** 保存授权判定和无副作用预览，构造函数只对核心模块开放。 */
class AgentPreparedToolCall internal constructor(
    /** 发起调用的 principal。 */
    val principal: AgentPrincipal,
    /** 原始工具请求。 */
    val request: AgentToolRequest,
    /** 确定性授权判定。 */
    val decision: AgentAuthorizationDecision,
    /** 允许请求的执行前预览；拒绝时为空。 */
    val preview: AgentToolPreview?,
)

/** 编排统一授权、无副作用预览、确认校验和审计。 */
class AgentToolCoordinator(
    /** 具体知识工具的预览和执行端口。 */
    private val executionPort: AgentToolExecutionPort,
    /** 记录拒绝、取消和失败事件的加密审计端口。 */
    private val auditPort: AgentAuditPort,
) {
    /**
     * 执行授权判定并仅在允许时生成无副作用预览。
     *
     * @param principal 外部 Agent 身份与 scopes。
     * @param request 待调用工具请求。
     * @param occurredAt 拒绝或预览失败的 UTC 时间。
     * @return 不可由调用方构造的 prepared call。
     */
    suspend fun prepare(
        principal: AgentPrincipal,
        request: AgentToolRequest,
        occurredAt: String,
    ): AgentPreparedToolCall {
        val decision = AgentAuthorizationService.authorize(principal, request)
        if (!decision.allowed) {
            auditPort.append(auditEvent(principal, request, AgentAuditOutcome.DENIED, occurredAt))
            return AgentPreparedToolCall(principal, request, decision, preview = null)
        }
        return try {
            AgentPreparedToolCall(principal, request, decision, executionPort.preview(request))
        } catch (error: Throwable) {
            auditPort.append(auditEvent(principal, request, AgentAuditOutcome.FAILED, occurredAt))
            throw error
        }
    }

    /**
     * 校验 prepared call 与确认事实后执行工具；不足确认只记录取消，不产生业务副作用。
     *
     * @param prepared 由 [prepare] 返回的调用。
     * @param confirmation 用户确认事实；无需确认时可以为空。
     * @param occurredAt 执行、取消或失败审计时间。
     * @return 执行结果；拒绝或取消返回稳定错误码。
     */
    suspend fun execute(
        prepared: AgentPreparedToolCall,
        confirmation: AgentConfirmationEvidence?,
        occurredAt: String,
    ): AgentToolResult {
        if (!prepared.decision.allowed) return AgentToolResult(false, errorCode = "authorization_denied")
        if (!confirmationSatisfies(prepared, confirmation)) {
            auditPort.append(auditEvent(prepared.principal, prepared.request, AgentAuditOutcome.CANCELED, occurredAt))
            return AgentToolResult(false, errorCode = "confirmation_required")
        }
        val successEvent = auditEvent(prepared.principal, prepared.request, AgentAuditOutcome.EXECUTED, occurredAt)
        return try {
            executionPort.execute(prepared.request, successEvent)
        } catch (error: Throwable) {
            auditPort.append(auditEvent(prepared.principal, prepared.request, AgentAuditOutcome.FAILED, occurredAt))
            throw error
        }
    }

    /**
     * 判断确认事实是否满足授权结果要求。
     *
     * @param prepared 已授权 prepared call。
     * @param evidence 可选用户确认事实。
     * @return 确认充分时为真。
     */
    private fun confirmationSatisfies(
        prepared: AgentPreparedToolCall,
        evidence: AgentConfirmationEvidence?,
    ): Boolean = when (prepared.decision.confirmationLevel) {
        AgentConfirmationLevel.NONE -> true
        AgentConfirmationLevel.SINGLE -> evidence?.requestId == prepared.request.requestId && evidence.firstConfirmed
        AgentConfirmationLevel.SECOND ->
            evidence?.requestId == prepared.request.requestId && evidence.firstConfirmed && evidence.secondConfirmed
    }

    /**
     * 创建不包含工具参数或正文的审计事件。
     *
     * @param principal 外部 Agent principal。
     * @param request 原始工具请求。
     * @param outcome 审计结果。
     * @param occurredAt UTC 事件时间。
     * @return 可进入加密历史的最小审计事件。
     */
    private fun auditEvent(
        principal: AgentPrincipal,
        request: AgentToolRequest,
        outcome: AgentAuditOutcome,
        occurredAt: String,
    ): AgentAuditEvent = AgentAuditEvent(
        requestId = request.requestId,
        principalId = principal.principalId,
        tool = request.tool,
        outcome = outcome,
        targetNoteIds = request.targetNoteIds,
        sentContentRemotely = request.sendsContentRemotely,
        occurredAt = occurredAt,
    )
}
