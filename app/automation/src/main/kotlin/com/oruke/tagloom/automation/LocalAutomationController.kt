package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 保存一次调用的冻结授权与结果，防止网络重试再次提交事务。 */
private data class LocalCallRecord(
    /** 当前调用不可变的共享请求。 */
    val prepared: AgentPreparedToolCall,
    /** 预览时绑定的唯一业务会话。 */
    val workspace: AgentWorkspaceSession,
    /** 用于有效期检查的单调时钟时间。 */
    val expiresAt: Long,
    /** 最新调用状态和唯一执行结果。 */
    var response: LocalCallResponse,
)

/** 编排当前会话的授权、调用去重与持有者审批；实际业务全部交给客户端会话端口。 */
class LocalAutomationController(
    /** 本机宿主配置的 owner 凭据。 */
    ownerToken: String,
    /** 获取当前会话；锁定时为空，切换或解锁时身份必须变化。 */
    private val sessionProvider: () -> AgentWorkspaceSession?,
    /** 单调毫秒源，避免系统时间回拨延长审批。 */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** 序列化授权撤销、审批和请求去重，业务会话另有唯一写入锁。 */
    private val mutex = Mutex()
    /** 当前会话的凭据摘要与最小权限。 */
    private val access = LocalAccessRegistry(ownerToken)
    /** 最近一次观察到的解锁会话。 */
    @Volatile
    private var sessionId: String? = null
    /** 当前会话内最多 256 个有界幂等结果和审批计划。 */
    private val calls = ConcurrentHashMap<String, LocalCallRecord>()
    /** 每个身份一分钟内的调用次数；不记录 Token 或参数。 */
    private val rate = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * 返回当前身份可发现的可执行工具。
     * @param token 当前调用凭据。
     * @param expectedSessionId 长驻客户端绑定的解锁会话；为空时使用当前会话。
     * @return scope 过滤的工具 schema 和会话身份。
     */
    suspend fun tools(token: String, expectedSessionId: String? = null): LocalToolList = mutex.withLock {
        val workspace = current(expectedSessionId)
        val auth = authenticate(token)
        val visible = ExternalToolCatalog.visibleTools(ExternalSurfaceKind.MCP_STDIO, auth.principal, auth.includeDangerous)
            .map { ExternalToolDefinitions.definition(it.tool) }
        LocalToolList(workspace.sessionId, auth.principal, visible)
    }

    /**
     * 提交调用，只读直接完成，高风险操作留在不可变待确认状态。
     * @param token 当前调用凭据。
     * @param input 工具名称、参数和可选幂等身份。
     * @param expectedSessionId 客户端绑定会话，禁止跨会话执行。
     * @return 完成结果或等待持有者确认的 call ID。
     */
    suspend fun call(token: String, input: LocalCallInput, expectedSessionId: String? = null): LocalCallResponse = mutex.withLock {
        val workspace = current(expectedSessionId)
        val auth = authenticate(token)
        val tool = ExternalToolNames.resolve(input.tool)
        if (tool == AgentToolName.DELETE_NOTE && !auth.includeDangerous) throw LocalApiException(403, "tool_unavailable")
        val request = ExternalToolRequestFactory.create(tool, input.arguments, input.requestId ?: UUID.randomUUID().toString())
        val key = "${auth.principal.principalId}:${request.requestId}"
        calls[key]?.let { previous ->
            if (previous.prepared.request != request) throw LocalApiException(409, "idempotency_conflict")
            return@withLock previous.response
        }
        if (calls.size >= 256) throw LocalApiException(429, "call_limit")
        if (calls.values.any { it.prepared.request.requestId == request.requestId }) throw LocalApiException(409, "idempotency_conflict")
        val prepared = workspace.prepare(auth.principal, request)
        if (!prepared.decision.allowed) throw LocalApiException(403, prepared.decision.denialCode?.name?.lowercase() ?: "authorization_denied")
        val preview = prepared.preview?.copy(details = null)
        var response = LocalCallResponse(request.requestId, "pending", prepared.decision.confirmationLevel, preview)
        if (prepared.decision.confirmationLevel == AgentConfirmationLevel.NONE) {
            response = response.copy(state = "completed", result = workspace.execute(prepared, null))
        }
        synchronized(this) {
            if (sessionId != workspace.sessionId) throw LocalApiException(423, "workspace_locked")
            calls[key] = LocalCallRecord(prepared, workspace, now() + CALL_TTL_MILLIS, response)
        }
        response
    }

    /**
     * 查询本身份发起的调用，owner 可以读取任意待确认调用。
     * @param token 当前调用凭据。
     * @param callId 随机调用身份。
     * @param expectedSessionId 客户端绑定会话。
     * @return 当前状态及执行后的结果。
     */
    suspend fun result(token: String, callId: String, expectedSessionId: String? = null): LocalCallResponse = mutex.withLock {
        current(expectedSessionId); val auth = authenticate(token)
        find(auth, callId).response
    }

    /**
     * 分页读取持有者审批队列；包含具体变更，只向 owner 开放。
     * @param token owner 凭据。
     * @param limit 单页数量。
     * @param offset 当前待审批队列偏移。
     * @param expectedSessionId 客户端绑定会话。
     * @return 只包含当前有效授权和当前会话的审批。
     */
    suspend fun approvals(token: String, limit: Int, offset: Int, expectedSessionId: String? = null): LocalJsonPage = mutex.withLock {
        current(expectedSessionId); requireOwner(authenticate(token))
        validatePage(limit, offset)
        val pending = calls.values.filter { it.response.state == "pending" && access.active(it.prepared.principal.principalId) }
        val items = pending.drop(offset).take(limit).map { record -> buildJsonObject {
            put("callId", record.response.callId)
            put("principalId", record.prepared.principal.principalId)
            put("tool", ExternalToolDefinitions.definition(record.prepared.request.tool).name)
            put("arguments", record.prepared.request.arguments ?: JsonNull)
            put("confirmationLevel", record.response.confirmationLevel.name)
            put("preview", Json.encodeToJsonElement(AgentToolPreview.serializer(), record.prepared.preview!!))
        } }
        val more = offset.toLong() + items.size < pending.size
        LocalJsonPage(items, more, if (more) offset + items.size else null)
    }

    /**
     * 由持有者完成所需确认并执行一次冻结请求。
     * @param token owner 凭据，普通 Agent Token 禁止进入。
     * @param callId 当前会话内的调用身份。
     * @param evidence 首次和二次确认事实。
     * @param expectedSessionId 客户端绑定会话。
     * @return 唯一执行结果，重复批准返回原结果。
     */
    suspend fun approve(token: String, callId: String, evidence: LocalApprovalInput, expectedSessionId: String? = null): LocalCallResponse = mutex.withLock {
        current(expectedSessionId); val auth = authenticate(token); requireOwner(auth)
        val record = find(auth, callId)
        if (!access.active(record.prepared.principal.principalId)) throw LocalApiException(403, "authorization_revoked")
        if (record.response.state != "pending") return@withLock record.response
        val level = record.prepared.decision.confirmationLevel
        if (!evidence.firstConfirmed || (level == AgentConfirmationLevel.SECOND && !evidence.secondConfirmed)) {
            throw LocalApiException(409, "confirmation_required")
        }
        // 执行前就封住重试入口；即使响应丢失，也不能再次提交同一计划。
        record.response = record.response.copy(state = "completed", result = AgentToolResult(false, errorCode = "execution_failed"))
        val result = try {
            record.workspace.execute(record.prepared, AgentConfirmationEvidence(callId, true, evidence.secondConfirmed, Instant.now().toString()))
        } catch (error: ExternalCallException) {
            AgentToolResult(false, errorCode = error.code)
        }
        record.response = record.response.copy(result = result)
        record.response
    }

    /**
     * 取消当前身份的待确认请求并记录取消审计。
     * @param token 发起者或 owner 凭据。
     * @param callId 当前待确认调用。
     * @param expectedSessionId 客户端绑定会话。
     * @return 已取消或已经完成的状态。
     */
    suspend fun cancel(token: String, callId: String, expectedSessionId: String? = null): LocalCallResponse = mutex.withLock {
        current(expectedSessionId); val record = find(authenticate(token), callId)
        if (record.response.state == "pending") {
            val result = record.workspace.execute(record.prepared, null)
            record.response = record.response.copy(state = "canceled", result = result)
        }
        record.response
    }

    /**
     * 授予一个当前工作区会话有效的独立 Agent Token。
     * @param token owner 凭据。
     * @param input 最小 scope 与 Note 范围。
     * @param expectedSessionId 客户端绑定会话。
     * @return 一次性凭据响应。
     */
    suspend fun grant(token: String, input: LocalGrantInput, expectedSessionId: String? = null): LocalGrantResponse = mutex.withLock {
        val workspace = current(expectedSessionId); requireOwner(authenticate(token))
        synchronized(this) {
            if (sessionId != workspace.sessionId) throw LocalApiException(423, "workspace_locked")
            access.grant(input, workspace.sessionId)
        }
    }

    /**
     * 撤销访问身份，销毁缓存并取消业务层冻结计划；取消记录进入加密审计。
     * @param token owner 凭据。
     * @param principalId 随机访问身份。
     * @param expectedSessionId 客户端绑定会话。
     */
    suspend fun revoke(token: String, principalId: String, expectedSessionId: String? = null): Unit = mutex.withLock {
        current(expectedSessionId); requireOwner(authenticate(token)); access.revoke(principalId)
        val revoked = calls.values.filter { it.prepared.principal.principalId == principalId }
        // 阶段 1：先失效凭据和协议缓存，审计失败也不能恢复被撤销的访问范围。
        calls.entries.removeIf { it.value.prepared.principal.principalId == principalId }
        rate.remove(principalId)
        // 阶段 2：逐项取消业务计划，不能只删协议记录，否则冻结正文会一直留在宿主内存中。
        // 已撤销后即使 HTTP 客户端断开，也必须完成全部清理；任一审计失败在清理结束后报告。
        var failure: Throwable? = null
        withContext(NonCancellable) {
            for (record in revoked.filter { it.response.state == "pending" }) {
                try { record.workspace.execute(record.prepared, null) }
                catch (error: Throwable) { if (failure == null) failure = error }
            }
        }
        failure?.let { throw it }
    }

    /** 清除所有授权与明文请求缓存，供宿主退出时调用。 */
    suspend fun close() = mutex.withLock { access.clear(); calls.clear(); rate.clear(); sessionId = null }

    /**
     * 同步会话身份并销毁上一会话所有外部授权与明文缓存。
     * @param expectedSessionId 可选绑定身份，在同一互斥锁中拒绝会话切换竞态。
     * @return 当前已解锁会话，锁定时返回 423。
     */
    private suspend fun current(expectedSessionId: String?): AgentWorkspaceSession {
        val workspace = sessionProvider()
        if (sessionId != workspace?.sessionId) {
            synchronized(this) { access.clear(); calls.clear(); rate.clear(); sessionId = workspace?.sessionId }
            workspace?.onClosed { invalidateSession(workspace.sessionId) }
        }
        if (workspace == null) throw LocalApiException(423, "workspace_locked")
        if (expectedSessionId != null && expectedSessionId != workspace.sessionId) throw LocalApiException(423, "session_changed")
        val expired = calls.filterValues { it.expiresAt <= now() }
        for ((key, record) in expired) {
            if (record.response.state == "pending") record.workspace.execute(record.prepared, null)
            calls.remove(key)
        }
        return workspace
    }

    /**
     * 不等待 API 互斥锁即销毁旧会话授权与明文引用，避免反向锁序。
     * @param closedSession 已关闭会话的随机身份。
     */
    @Synchronized
    private fun invalidateSession(closedSession: String) {
        if (sessionId == closedSession) { sessionId = null; access.clear(); calls.clear(); rate.clear() }
    }

    /**
     * 验证身份并限制每个身份每分钟 120 次调用。
     * @param token 当前 Bearer 凭据。
     * @return 已认证的最小身份。
     */
    private fun authenticate(token: String): LocalAccess {
        val auth = access.authenticate(token)
        val previous = rate[auth.principal.principalId]
        val at = now()
        val next = if (previous == null || at - previous.first >= 60_000) at to 1 else previous.first to (previous.second + 1)
        if (next.second > 120) throw LocalApiException(429, "rate_limited")
        rate[auth.principal.principalId] = next
        return auth
    }

    /**
     * 查找本身份或 owner 可以读取的唯一调用。
     * @param auth 当前可信身份。
     * @param callId 随机调用身份。
     * @return 可读取记录，越权与缺失统一返回 404。
     */
    private fun find(auth: LocalAccess, callId: String): LocalCallRecord {
        ExternalToolArguments.opaque(callId)
        return calls.values.singleOrNull { it.response.callId == callId && (auth.owner || it.prepared.principal.principalId == auth.principal.principalId) }
            ?: throw LocalApiException(404, "call_not_found")
    }

    /**
     * 限制审批与授予能力仅由持有者调用。
     * @param auth 当前可信身份。
     */
    private fun requireOwner(auth: LocalAccess) { if (!auth.owner) throw LocalApiException(403, "owner_required") }

    /**
     * 校验集合分页参数。
     * @param limit 单页数量。
     * @param offset 集合偏移。
     */
    private fun validatePage(limit: Int, offset: Int) {
        if (limit !in 1..100 || offset !in 0..1_000_000) throw LocalApiException(400, "invalid_arguments")
    }

    private companion object {
        /** 确认和幂等结果在同一解锁会话内最多保留十分钟。 */
        const val CALL_TTL_MILLIS: Long = 600_000
    }
}
