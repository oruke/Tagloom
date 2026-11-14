package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** 验证生产 API、安装分发 CLI 与官方 SDK MCP 形成真实笔记事务和持久化闭环。 */
class LocalAutomationEntryIntegrationTest {
    /** 创建预览不落盘，确认后跨 API/CLI/MCP 可读，重试幂等且磁盘审计与业务结果一致。 */
    @Test
    fun apiCliAndMcpShareConfirmedEncryptedState() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val discovery = fixture.owner.tools()
            assertEquals(21, discovery.tools.size)
            val create = LocalCallInput("create_note", buildJsonObject {
                put("title", "Entry sample"); put("markdown", "1. first sample\n2. second sample\n\n```kotlin\nval sample = 1\n```")
            }, "request-create-entry")
            val pending = fixture.owner.call(create)
            assertEquals("pending", pending.state)
            assertEquals(AgentConfirmationLevel.SINGLE, pending.confirmationLevel)
            assertTrue(fixture.session().loadNotes().isEmpty())
            val preview = fixture.owner.approvals().items.single()["preview"]!!.jsonObject["details"]!!.jsonObject
            val approved = fixture.owner.approve(pending.callId, LocalApprovalInput(true))
            val payload = approved.result!!.payload!!.jsonObject
            val noteId = payload["noteId"]!!.jsonPrimitive.content
            assertEquals(preview["noteId"], payload["noteId"])
            assertEquals(payload["note"], preview["after"])
            fixture.assertAuditedTransaction(ObjectId(noteId), pending.callId)
            assertEquals(approved, fixture.owner.call(create))
            assertEquals(1, fixture.session().loadNotes().size)
            val changedRetry = assertFailsWith<LocalApiException> {
                fixture.owner.call(create.copy(arguments = buildJsonObject { put("title", "Other sample") }))
            }
            assertEquals(409, changedRetry.status)

            AutomationTestProcess(fixture, fixture.ownerToken, "get", noteId).use { cli ->
                val actual = cli.jsonLine()["result"]!!.jsonObject["payload"]!!.jsonObject
                assertEquals(payload["note"], actual["note"])
                assertEquals(payload["revision"], actual["revision"])
                cli.finish()
            }
            val grant = fixture.owner.grant(LocalGrantInput(scopes = setOf(AgentPermissionScope.READ_NOTES,
                AgentPermissionScope.SEARCH_NOTES, AgentPermissionScope.WRITE_NOTES)))
            fixture.client(grant.token).use { agent ->
                AutomationTestProcess(fixture, grant.token, "mcp").use { mcp ->
                    val initialized = mcp.rpc(1, "initialize", buildJsonObject {
                        put("protocolVersion", "2025-11-25"); put("capabilities", JsonObject(emptyMap()))
                        put("clientInfo", buildJsonObject { put("name", "integration-client"); put("version", "1.0.0") })
                    })
                    assertNotNull(initialized["result"])
                    mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                    val listed = mcp.rpc(2, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
                    assertTrue(listed.any { it.jsonObject["name"]!!.jsonPrimitive.content == "create_note" })
                    assertFalse(listed.any { it.jsonObject["name"]!!.jsonPrimitive.content == "delete_note" })
                    val resources = mcp.rpc(3, "resources/templates/list")["result"]!!.jsonObject["resourceTemplates"]!!.jsonArray
                    assertEquals(3, resources.size)
                    val resource = mcp.rpc(4, "resources/read", buildJsonObject { put("uri", "note://$noteId") })
                    val content = resource["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                    assertEquals(payload["note"], Json.parseToJsonElement(content).jsonObject["note"])
                    val proposal = mcp.rpc(5, "tools/call", buildJsonObject {
                        put("name", "create_note"); put("arguments", buildJsonObject { put("title", "MCP sample"); put("markdown", "- MCP sample item") })
                    })["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                    assertEquals("pending", proposal["state"]!!.jsonPrimitive.content)
                    val callId = proposal["callId"]!!.jsonPrimitive.content
                    assertEquals(1, fixture.session().loadNotes().size)
                    assertEquals(403, assertFailsWith<LocalApiException> { agent.approve(callId, LocalApprovalInput(true)) }.status)
                    AutomationTestProcess(fixture, fixture.ownerToken, "approve", callId).use { cli ->
                        cli.send("confirm")
                        assertTrue(cli.jsonLine()["result"]!!.jsonObject["succeeded"]!!.jsonPrimitive.boolean)
                        cli.finish()
                    }
                    val completed = mcp.rpc(6, "tools/call", buildJsonObject {
                        put("name", "tagloom_get_call_result"); put("arguments", buildJsonObject { put("callId", callId) })
                    })["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                    assertEquals("completed", completed["state"]!!.jsonPrimitive.content)
                    assertTrue(completed["result"]!!.jsonObject["succeeded"]!!.jsonPrimitive.boolean)
                    mcp.finish()
                }
            }
            assertEquals(2, fixture.session().loadNotes().size)
            assertEquals(1, fixture.session().search(SearchIndexQuery(text = "first sample")).size)
            val audits = fixture.audits()
            val success = audits.single { it.requestId == create.requestId && it.outcome == AgentAuditOutcome.EXECUTED }
            assertEquals(setOf(ObjectId(noteId)), success.targetNoteIds)
            assertTrue(audits.any { it.tool == AgentToolName.GET_NOTE && it.outcome == AgentAuditOutcome.EXECUTED })
            fixture.assertEncrypted("Entry sample", "first sample", "MCP sample item")
            fixture.reopen()
            assertEquals(setOf("Entry sample", "MCP sample"), fixture.session().loadNotes().map { it.snapshot.payload.title }.toSet())
            assertEquals(401, assertFailsWith<LocalApiException> { fixture.client(grant.token).use { it.tools() } }.status)
        } finally { fixture.close() }
    }

    /** 真实快照在预览后变更时拒绝覆盖，导出和删除需要二次确认，取消不产生笔记变化。 */
    @Test
    fun revisionsConfirmationAndEncryptedAuditStayConsistent() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val pending = fixture.owner.call(LocalCallInput("create_note", buildJsonObject { put("title", "Conflict sample"); put("markdown", "sample body") }))
            val created = fixture.owner.approve(pending.callId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val noteId = created["noteId"]!!.jsonPrimitive.content
            val revision = created["revision"]!!.jsonPrimitive.content
            val update = fixture.owner.call(LocalCallInput("update_note", buildJsonObject {
                put("noteId", noteId); put("expectedRevision", revision); put("title", "External sample")
            }))
            val current = fixture.session().loadNotes().single()
            fixture.session().saveNote(current.id, current.snapshot.payload.copy(title = "Desktop sample"), current.snapshot.header.revision)
            val conflict = fixture.owner.approve(update.callId, LocalApprovalInput(true))
            assertEquals("revision_conflict", conflict.result!!.errorCode)
            assertEquals("Desktop sample", fixture.session().loadNotes().single().snapshot.payload.title)
            assertEquals(conflict, fixture.owner.approve(update.callId, LocalApprovalInput(true)))
            val get = fixture.owner.call(LocalCallInput("get_note", buildJsonObject { put("noteId", noteId) })).result!!.payload!!.jsonObject
            val export = fixture.owner.call(LocalCallInput("export_note", buildJsonObject {
                put("noteId", noteId); put("expectedRevision", get["revision"]!!); put("mode", "extended")
            }))
            assertEquals(AgentConfirmationLevel.SECOND, export.confirmationLevel)
            assertEquals(409, assertFailsWith<LocalApiException> { fixture.owner.approve(export.callId, LocalApprovalInput(true)) }.status)
            val exported = fixture.owner.approve(export.callId, LocalApprovalInput(true, true))
            assertTrue(exported.result!!.payload!!.jsonObject["markdown"]!!.jsonPrimitive.content.contains("sample body"))
            val delete = fixture.owner.call(LocalCallInput("delete_note", buildJsonObject {
                put("noteId", noteId); put("expectedRevision", get["revision"]!!)
            }))
            assertEquals("canceled", fixture.owner.cancel(delete.callId).state)
            assertEquals(1, fixture.session().loadNotes().size)
            val finalDelete = fixture.owner.call(LocalCallInput("delete_note", buildJsonObject {
                put("noteId", noteId); put("expectedRevision", get["revision"]!!)
            }))
            fixture.owner.approve(finalDelete.callId, LocalApprovalInput(true, true))
            assertTrue(fixture.session().loadNotes().isEmpty())
            assertTrue(fixture.session().search(SearchIndexQuery(text = "sample body")).isEmpty())
            val audits = fixture.audits()
            assertTrue(audits.any { it.requestId == update.callId && it.outcome == AgentAuditOutcome.FAILED })
            assertTrue(audits.any { it.requestId == delete.callId && it.outcome == AgentAuditOutcome.CANCELED })
            assertTrue(audits.any { it.requestId == finalDelete.callId && it.outcome == AgentAuditOutcome.EXECUTED })
            fixture.reopen()
            assertTrue(fixture.session().loadNotes().isEmpty())
        } finally { fixture.close() }
    }
}
