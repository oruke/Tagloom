package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.workspace.loadNotes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** 验证生产 HTTP 与 stdio 入口在授权撤销、确认过期和重新解锁后的真实行为。 */
class LocalAutomationLifecycleIntegrationTest {
    /** 推进真实控制器时钟后取消过期预览，授权撤销同时销毁 pending 与已缓存正文。 */
    @Test
    fun expiresApprovalsAndRemovesRevokedResults() = runBlocking {
        val time = AtomicLong(0)
        val fixture = AutomationTestWorkspace.create(time::get)
        try {
            val created = fixture.owner.call(LocalCallInput("create_note", buildJsonObject { put("title", "Lifecycle sample") }))
            val payload = fixture.owner.approve(created.callId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val grant = fixture.owner.grant(LocalGrantInput(scopes = setOf(AgentPermissionScope.READ_NOTES, AgentPermissionScope.WRITE_NOTES)))
            fixture.client(grant.token).use { agent ->
                val read = agent.call(LocalCallInput("get_note", buildJsonObject { put("noteId", payload["noteId"]!!) }))
                val proposal = agent.call(LocalCallInput("create_note", buildJsonObject { put("title", "Revoked proposal sample") }))
                fixture.owner.revoke(grant.principalId)
                assertEquals(401, assertFailsWith<LocalApiException> { agent.result(read.callId) }.status)
                assertEquals(404, assertFailsWith<LocalApiException> { fixture.owner.result(read.callId) }.status)
                assertEquals(404, assertFailsWith<LocalApiException> { fixture.owner.approve(proposal.callId, LocalApprovalInput(true)) }.status)
                val cancellation = fixture.audits().single { it.requestId == proposal.callId }
                assertEquals(AgentAuditOutcome.CANCELED, cancellation.outcome)
                assertEquals(grant.principalId, cancellation.principalId)
            }
            val pending = fixture.owner.call(LocalCallInput("create_note", buildJsonObject { put("title", "Expired proposal sample") }))
            time.addAndGet(600_001)
            assertTrue(fixture.owner.approvals().items.isEmpty())
            assertEquals(404, assertFailsWith<LocalApiException> { fixture.owner.approve(pending.callId, LocalApprovalInput(true)) }.status)
            assertEquals(listOf("Lifecycle sample"), fixture.session().loadNotes().map { it.snapshot.payload.title })
            assertTrue(fixture.audits().any { it.requestId == pending.callId && it.outcome == AgentAuditOutcome.CANCELED })
        } finally { fixture.close() }
    }

    /** 旧 owner MCP 与已绑定 HTTP 客户端在重新解锁后失效，新的入口仍能读取同一密文工作区。 */
    @Test
    fun bindsOwnerMcpAndHttpToOneUnlockSession() = runBlocking<Unit> {
        val fixture = AutomationTestWorkspace.create()
        try {
            fixture.client(fixture.ownerToken).use { bound ->
                bound.bindSession(bound.tools().sessionId)
                AutomationTestProcess(fixture, fixture.ownerToken, "mcp").use { mcp ->
                    assertNotNull(mcp.rpc(1, "initialize", buildJsonObject {
                        put("protocolVersion", "2025-11-25"); put("capabilities", JsonObject(emptyMap()))
                        put("clientInfo", buildJsonObject { put("name", "lifecycle-client"); put("version", "1.0.0") })
                    })["result"])
                    mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                    fixture.reopen()
                    val changed = assertFailsWith<LocalApiException> { bound.call(LocalCallInput("list_notes")) }
                    assertEquals(423, changed.status)
                    assertEquals("session_changed", changed.code)
                    val response = mcp.rpc(2, "tools/call", buildJsonObject {
                        put("name", "list_notes"); put("arguments", JsonObject(emptyMap()))
                    })["result"]!!.jsonObject
                    assertTrue(response["isError"]!!.jsonPrimitive.boolean)
                    assertEquals("session_changed", response["structuredContent"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
                    mcp.finish()
                    assertNotNull(fixture.owner.tools())
                }
            }
        } finally { fixture.close() }
    }
}
