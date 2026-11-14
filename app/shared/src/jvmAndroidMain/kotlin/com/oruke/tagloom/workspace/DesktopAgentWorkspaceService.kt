package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** 将 Agent、API、CLI、MCP 接到现有客户端会话，统一锁定状态、预览、事务和加密审计。 */
class DesktopAgentWorkspaceService(
    /** 持有主密钥和唯一事务协调器的已解锁会话。 */
    private val session: DesktopWorkspaceSession,
    /** 成功修改笔记后通知桌面刷新；不传递正文或凭据。 */
    private val onMutation: () -> Unit = {},
) : AgentWorkspaceSession {
    /** 解锁 Actor 身份每次重新分配，不能复用上一工作区的授权。 */
    override val sessionId: String = session.actorId.value

    /** 预览时冻结的完整笔记计划，执行后立即删除。 */
    private val plans = mutableMapOf<String, DesktopAgentNoteMutation>()

    /** 记录待执行请求是否由本机 owner token 直接发起。 */
    private val ownerFlags = mutableMapOf<String, Boolean>()

    init {
        session.onExternalClosed {
            plans.clear()
            ownerFlags.clear()
        }
    }

    /**
     * 转交当前解锁会话的同步关闭通知。
     * @param listener 只清理外部内存缓存的回调。
     */
    override fun onClosed(listener: () -> Unit) { session.onExternalClosed(listener) }

    /**
     * 从参数重新推导授权元数据，在会话互斥锁中执行 `AiAccessPolicy` 门禁并生成确定的预览。
     * @param principal 可信认证身份。
     * @param request 外部工具请求，不信任调用方声明的目标和风险。
     * @param isOwnerDirectCall 当前请求是否直接持有本机 owner token。
     * @return 授权结果、预览及冻结的写入计划。
     */
    override suspend fun prepare(
        principal: AgentPrincipal,
        request: AgentToolRequest,
        isOwnerDirectCall: Boolean,
    ): AgentPreparedToolCall =
        session.sessionMutex.withLock {
            session.requireOpen()
            val canonical = ExternalToolRequestFactory.create(request.tool, request.arguments as? JsonObject
                ?: throw ExternalCallException("invalid_arguments"), request.requestId)
            ownerFlags[canonical.requestId] = isOwnerDirectCall
            val prepared = try {
                coordinator(principal, isOwnerDirectCall).prepare(principal, canonical, session.timestampProvider())
            } catch (error: Throwable) {
                ownerFlags.remove(canonical.requestId)
                throw error
            }
            if (!prepared.decision.allowed) {
                ownerFlags.remove(canonical.requestId)
            }
            prepared
        }

    /**
     * 在原会话锁中确认和执行，旧会话锁定后不允许继续读取或提交。
     * @param prepared 本服务已预览的请求。
     * @param confirmation 持有者确认事实。
     * @return 已持久化审计的结构化结果。
     */
    override suspend fun execute(prepared: AgentPreparedToolCall, confirmation: AgentConfirmationEvidence?): AgentToolResult =
        session.sessionMutex.withLock {
            session.requireOpen()
            val isOwnerDirectCall = ownerFlags[prepared.request.requestId] ?: false
            try {
                coordinator(prepared.principal, isOwnerDirectCall).execute(prepared, confirmation, session.timestampProvider())
            } finally {
                plans.remove(prepared.request.requestId)
                ownerFlags.remove(prepared.request.requestId)
            }
        }

    /**
     * 为身份绑定同一预览、执行、策略门禁和审计端口。
     * @param principal 请求身份，执行端口不能脱离其 Note 范围。
     * @param isOwnerDirectCall 当前调用是否直接持有本机 owner token。
     * @return 共享核心协调器，不引入第二写入实现。
     */
    private fun coordinator(
        principal: AgentPrincipal,
        isOwnerDirectCall: Boolean,
    ): AgentToolCoordinator = AgentToolCoordinator(
        executionPort = DesktopAgentExecutionPort(session, principal, plans, onMutation, isOwnerDirectCall),
        auditPort = AgentAuditPort { event -> appendAgentAuditLocked(session, event) },
        targetPolicyEvaluator = { targetId ->
            if (com.oruke.tagloom.core.model.SystemDatabaseDefinitions.isProtectedSystemPage(targetId)) {
                AgentDenialCode.TARGET_OUT_OF_SCOPE
            } else {
                val stored = session.loadActiveNotes().singleOrNull { it.id == targetId }
                if (stored == null) {
                    null
                } else {
                    val effective = session.loadAiAccessPolicyResolver()
                        .resolveEffectiveNotePolicy(stored.snapshot.payload)
                    AgentNotePolicyGate.checkExternalDirectAccess(
                        effectivePolicy = effective,
                        noteId = targetId,
                        principal = principal,
                        isOwnerDirectCall = isOwnerDirectCall,
                    )
                }
            }
        },
    )
}

/**
 * 在调用方持有会话锁时独立确认加密审计。
 * @param session 已解锁且持有互斥锁的工作区会话。
 * @param event 不包含正文或凭据的事件。
 */
internal suspend fun appendAgentAuditLocked(session: DesktopWorkspaceSession, event: AgentAuditEvent) {
    session.requireWritable()
    try {
        session.refreshManifest(session.agentAuditService.commit(session.unlocked, event, TransactionId(session.idFactory())))
    } catch (error: Throwable) {
        // 审计也可能在 manifest 确认后发生派生更新失败，刷新以保持原会话确认点一致。
        runCatching { session.refreshManifest() }
        throw error
    }
}

/** 以冻结笔记计划和现有索引执行统一 Agent 工具，不开放任意路径或底层存储。 */
internal class DesktopAgentExecutionPort(
    /** 当前已解锁工作区会话。 */
    private val session: DesktopWorkspaceSession,
    /** 当前外部身份。 */
    private val principal: AgentPrincipal,
    /** 已预览且仅存在内存的写入计划。 */
    private val plans: MutableMap<String, DesktopAgentNoteMutation>,
    /** 完成变更后的桌面通知。 */
    private val onMutation: () -> Unit,
    /** 当前调用是否直接持有本机 owner token。 */
    private val isOwnerDirectCall: Boolean = false,
) : AgentToolExecutionPort {
    /**
     * 验证输入并冻结变化，预览数据只交给持有者确认。
     * @param request 已通过基础授权的请求。
     * @return 最小风险说明及持有者可查看的具体差异。
     */
    override suspend fun preview(request: AgentToolRequest): AgentToolPreview {
        val definition = ExternalToolDefinitions.definition(request.tool)
        val mutation = DesktopAgentMutationService(session, principal, isOwnerDirectCall)
        val details = when {
            !definition.readOnly -> {
                val plan = mutation.plan(request)
                plans[request.requestId] = plan
                buildJsonObject {
                    put("noteId", plan.noteId.value)
                    put("expectedRevision", plan.expectedRevision?.externalText()?.let(::JsonPrimitive) ?: JsonNull)
                    put("after", CanonicalJson.format.encodeToJsonElement(com.oruke.tagloom.core.model.Note.serializer(), plan.note))
                    put("deleted", plan.deleted)
                }
            }
            request.tool == AgentToolName.EXPORT_NOTE -> mutation.export(request)
            else -> null
        }
        return AgentToolPreview(definition.name, request.itemCount, definition.destructive, details)
    }

    /**
     * 在原事务中写入 Note 和成功审计；读取在返回前确认独立加密审计。
     * @param request 已授权、确认充分的请求。
     * @param successAuditEvent 与成功业务结果关联的脱敏事件。
     * @return 已确认的业务结果。
     */
    override suspend fun execute(request: AgentToolRequest, successAuditEvent: AgentAuditEvent): AgentToolResult {
        val definition = ExternalToolDefinitions.definition(request.tool)
        val payload: JsonElement = if (!definition.readOnly) {
            val plan = plans[request.requestId] ?: throw ExternalCallException("preview_required")
            if (plan.expectedRevision != null) {
                DesktopAgentReadService(session, principal, isOwnerDirectCall).note(plan.noteId)
            }
            if (plan.deleted) {
                session.deleteNoteLocked(plan.noteId, plan.expectedRevision, successAuditEvent)
                buildJsonObject { put("noteId", plan.noteId.value); put("deleted", true) }
            } else {
                DesktopAgentNoteSchemaService(session).requireUnchanged(plan.schemaRevisions)
                externalNoteJson(session.saveNoteLocked(plan.noteId, plan.note, plan.expectedRevision, successAuditEvent))
            }
        } else {
            val data = if (request.tool == AgentToolName.EXPORT_NOTE) {
                DesktopAgentMutationService(session, principal, isOwnerDirectCall).export(request)
            } else {
                DesktopAgentReadService(session, principal, isOwnerDirectCall).execute(request)
            }
            appendAgentAuditLocked(session, successAuditEvent)
            data
        }
        if (!definition.readOnly) onMutation()
        return AgentToolResult(true, payload)
    }
}
