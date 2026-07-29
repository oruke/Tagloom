package com.oruke.tagloom.core.agent

import com.oruke.tagloom.core.model.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 验证 Agent 默认拒绝、最小 scope、目标 allow-list 与两阶段确认。 */
class AgentAuthorizationServiceTest {
    /** 验证只读工具无需确认，而写入、批量、删除和远程发送逐级提高确认强度。 */
    @Test
    fun computesLeastPrivilegeAndConfirmationLevel() {
        val principal = AgentPrincipal(
            principalId = "agent-a",
            scopes = setOf(
                AgentPermissionScope.READ_NOTES,
                AgentPermissionScope.SEARCH_NOTES,
                AgentPermissionScope.WRITE_NOTES,
                AgentPermissionScope.DELETE_NOTES,
                AgentPermissionScope.CALL_REMOTE_AI,
            ),
        )

        assertEquals(
            AgentConfirmationLevel.NONE,
            AgentAuthorizationService.authorize(principal, request("read", AgentToolName.GET_NOTE)).confirmationLevel,
        )
        assertEquals(
            AgentConfirmationLevel.SINGLE,
            AgentAuthorizationService.authorize(principal, request("write", AgentToolName.UPDATE_NOTE)).confirmationLevel,
        )
        assertEquals(
            AgentConfirmationLevel.SECOND,
            AgentAuthorizationService.authorize(
                principal,
                request("batch", AgentToolName.UPDATE_NOTE, itemCount = 2),
            ).confirmationLevel,
        )
        assertEquals(
            AgentConfirmationLevel.SECOND,
            AgentAuthorizationService.authorize(principal, request("delete", AgentToolName.DELETE_NOTE)).confirmationLevel,
        )
        assertEquals(
            AgentConfirmationLevel.SECOND,
            AgentAuthorizationService.authorize(
                principal,
                request("remote", AgentToolName.GET_NOTE, sendsContentRemotely = true),
            ).confirmationLevel,
        )
    }

    /** 验证缺少 scope、越过 Note allow-list 或未获远程发送权限都会稳定拒绝。 */
    @Test
    fun deniesMissingScopeOutOfScopeTargetAndRemoteTransmission() {
        val noteId = ObjectId("note-allowed")
        val principal = AgentPrincipal(
            principalId = "agent-limited",
            scopes = setOf(AgentPermissionScope.READ_NOTES),
            allowedNoteIds = setOf(noteId),
        )

        val missingScope = AgentAuthorizationService.authorize(
            principal,
            request("search", AgentToolName.SEARCH_NOTES),
        )
        val targetDenied = AgentAuthorizationService.authorize(
            principal,
            request("target", AgentToolName.GET_NOTE, targets = setOf(ObjectId("note-other"))),
        )
        val remoteDenied = AgentAuthorizationService.authorize(
            principal,
            request("remote", AgentToolName.GET_NOTE, targets = setOf(noteId), sendsContentRemotely = true),
        )

        assertEquals(AgentDenialCode.MISSING_SCOPE, missingScope.denialCode)
        assertEquals(AgentDenialCode.TARGET_OUT_OF_SCOPE, targetDenied.denialCode)
        assertEquals(AgentDenialCode.REMOTE_AI_NOT_ALLOWED, remoteDenied.denialCode)
    }

    /** 验证二次确认不足时没有执行副作用，充分确认后成功事件交给执行端原子持久化。 */
    @Test
    fun coordinatorRequiresSecondConfirmationBeforeBatchWrite() = runBlocking {
        val execution = RecordingExecutionPort()
        val audit = RecordingAuditPort()
        val coordinator = AgentToolCoordinator(execution, audit)
        val principal = AgentPrincipal(
            principalId = "agent-writer",
            scopes = setOf(AgentPermissionScope.WRITE_NOTES),
        )
        val request = request("request-batch", AgentToolName.UPDATE_NOTE, itemCount = 2)
        val prepared = coordinator.prepare(principal, request, "2026-07-13T00:00:00Z")

        val canceled = coordinator.execute(
            prepared,
            AgentConfirmationEvidence(
                request.requestId,
                firstConfirmed = true,
                secondConfirmed = false,
                confirmedAt = "2026-07-13T00:01:00Z",
            ),
            "2026-07-13T00:01:00Z",
        )
        assertFalse(canceled.succeeded)
        assertEquals(0, execution.executeCount)
        assertEquals(AgentAuditOutcome.CANCELED, audit.events.single().outcome)

        val completed = coordinator.execute(
            prepared,
            AgentConfirmationEvidence(
                request.requestId,
                firstConfirmed = true,
                secondConfirmed = true,
                confirmedAt = "2026-07-13T00:02:00Z",
            ),
            "2026-07-13T00:02:00Z",
        )
        assertTrue(completed.succeeded)
        assertEquals(1, execution.executeCount)
        assertEquals(AgentAuditOutcome.EXECUTED, execution.successAuditEvent?.outcome)
    }

    /**
     * 创建测试工具请求。
     *
     * @param id 请求 ID。
     * @param tool 工具名称。
     * @param itemCount 条目数。
     * @param targets 目标 Note IDs。
     * @param sendsContentRemotely 是否远程发送。
     * @return 测试请求。
     */
    private fun request(
        id: String,
        tool: AgentToolName,
        itemCount: Int = 1,
        targets: Set<ObjectId> = emptySet(),
        sendsContentRemotely: Boolean = false,
    ): AgentToolRequest = AgentToolRequest(
        requestId = id,
        tool = tool,
        targetNoteIds = targets,
        itemCount = itemCount,
        sendsContentRemotely = sendsContentRemotely,
    )
}

/** 记录预览、执行次数与成功审计事件的测试工具端口。 */
private class RecordingExecutionPort : AgentToolExecutionPort {
    /** 实际执行次数。 */
    var executeCount: Int = 0

    /** 执行端收到并应与业务写入原子持久化的成功事件。 */
    var successAuditEvent: AgentAuditEvent? = null

    /**
     * 返回固定无副作用预览。
     *
     * @param request 已授权请求。
     * @return 测试预览。
     */
    override suspend fun preview(request: AgentToolRequest): AgentToolPreview = AgentToolPreview(
        title = "更新预览",
        affectedItems = request.itemCount,
        destructive = false,
        sendsContentRemotely = request.sendsContentRemotely,
    )

    /**
     * 记录执行与成功审计事件。
     *
     * @param request 已确认请求。
     * @param successAuditEvent 应与写入一起持久化的事件。
     * @return 成功结果。
     */
    override suspend fun execute(
        request: AgentToolRequest,
        successAuditEvent: AgentAuditEvent,
    ): AgentToolResult {
        executeCount += 1
        this.successAuditEvent = successAuditEvent
        return AgentToolResult(succeeded = true)
    }
}

/** 在内存中记录拒绝、取消和失败事件的测试审计端口。 */
private class RecordingAuditPort : AgentAuditPort {
    /** 已追加事件。 */
    val events: MutableList<AgentAuditEvent> = mutableListOf()

    /**
     * 追加测试审计事件。
     *
     * @param event 待记录事件。
     */
    override suspend fun append(event: AgentAuditEvent) {
        events += event
    }
}
