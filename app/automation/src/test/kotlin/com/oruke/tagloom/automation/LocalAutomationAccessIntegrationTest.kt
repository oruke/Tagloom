package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** 用实际工作区、生产 HTTP 入口和密文审计验证授权、schema、分页及会话生命周期。 */
class LocalAutomationAccessIntegrationTest {
    /** 搜索分页前取授权交集，资源和伪造元数据不能越权，锁定后旧授权与预览失效。 */
    @Test
    fun filtersAuthorizedResultsAndInvalidatesOldSessions() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val allowed = fixture.session().saveNote(null, Note("shared sample allowed", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)))
            val denied = fixture.session().saveNote(null, Note("shared sample private", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)))
            val grant = fixture.owner.grant(LocalGrantInput(allowedNoteIds = setOf(allowed.id), maxItemsPerCall = 1))
            fixture.client(grant.token).use { agent ->
                val search = agent.call(LocalCallInput("search_notes", buildJsonObject { put("query", "shared sample"); put("limit", 1) }))
                assertEquals("completed", search.state)
                assertEquals(AgentConfirmationLevel.NONE, search.confirmationLevel)
                val page = search.result!!.payload!!.jsonObject
                assertEquals(allowed.id.value, page["items"]!!.jsonArray.single().jsonObject["noteId"]!!.jsonPrimitive.content)
                assertFalse(page["hasMore"]!!.jsonPrimitive.boolean)
                assertFalse(page.toString().contains("private"))
                assertEquals(403, assertFailsWith<LocalApiException> { agent.resource("note://${denied.id.value}") }.status)
                assertEquals(403, assertFailsWith<LocalApiException> {
                    agent.call(LocalCallInput("update_note", buildJsonObject {
                        put("noteId", allowed.id.value); put("expectedRevision", "actor:1"); put("title", "denied write")
                    }))
                }.status)
                assertEquals(400, assertFailsWith<LocalApiException> { agent.resource("note://../outside") }.status)
                val scopedWriter = fixture.owner.grant(LocalGrantInput(scopes = setOf(AgentPermissionScope.WRITE_NOTES), allowedNoteIds = setOf(allowed.id)))
                fixture.client(scopedWriter.token).use { writer ->
                    assertEquals(403, assertFailsWith<LocalApiException> {
                        writer.call(LocalCallInput("create_note", buildJsonObject { put("title", "not allowed") }))
                    }.status)
                }
                val service = DesktopAgentWorkspaceService(fixture.session())
                val prepared = service.prepare(AgentPrincipal("test-agent", setOf(AgentPermissionScope.READ_NOTES), setOf(allowed.id)),
                    AgentToolRequest("request-spoof-target", AgentToolName.GET_NOTE, setOf(allowed.id),
                        arguments = buildJsonObject { put("noteId", denied.id.value) }))
                assertFalse(prepared.decision.allowed)
                assertEquals(AgentDenialCode.TARGET_OUT_OF_SCOPE, prepared.decision.denialCode)
                var invalidated = false
                service.onClosed { invalidated = true }
                val oldSessionId = fixture.owner.tools().sessionId
                fixture.manager.lock()
                assertTrue(invalidated)
                assertEquals(423, assertFailsWith<LocalApiException> { agent.tools() }.status)
                assertFailsWith<ExternalCallException> { service.execute(prepared, null) }
                fixture.reopen()
                assertNotEquals(oldSessionId, fixture.owner.tools().sessionId)
                assertEquals(401, assertFailsWith<LocalApiException> { agent.tools() }.status)
            }
            assertTrue(fixture.audits().any { it.requestId == "request-spoof-target" && it.outcome == AgentAuditOutcome.DENIED })
            fixture.assertEncrypted("shared sample private", "shared sample allowed")
        } finally { fixture.close() }
    }

    /** 真实 schema 继承、默认值和选项可读，Markdown 和完整 Note 更新保存 Block/RichText/属性。 */
    @Test
    fun exposesTypeSchemaDefaultsPropertiesAndSavedQueries() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val propertyId = PropertyId("property-example")
            val typeId = TagId("type-example")
            fixture.session().savePropertyDefinition(PropertyDefinition(propertyId, "Choice sample", PropertyTypes.SELECT,
                options = listOf(PropertyOption("option-active", "Active sample"), PropertyOption("option-done", "Done sample"))))
            fixture.session().saveTagDefinition(TagDefinition(typeId, "Type sample", propertyBindings = listOf(
                TagPropertyBinding(propertyId, defaultValue = JsonPrimitive("option-active"), defaultPolicy = DefaultValuePolicy.SNAPSHOT),
            )))
            val schema = fixture.owner.resource("tag://${typeId.value}").result!!.payload!!.jsonObject
            val effective = schema["effectiveProperties"]!!.jsonObject["properties"]!!.jsonArray
                .single { it.jsonObject["propertyId"]!!.jsonPrimitive.content == propertyId.value }.jsonObject
            assertEquals("option-active", effective["value"]!!.jsonPrimitive.content)
            assertEquals(2, effective["definition"]!!.jsonObject["options"]!!.jsonArray.size)
            val pending = fixture.owner.call(LocalCallInput("create_note", buildJsonObject {
                put("title", "Type entry sample"); put("markdown", "**rich sample**\n\n- item sample")
                put("tagIds", JsonArray(listOf(JsonPrimitive(typeId.value))))
            }))
            val created = fixture.owner.approve(pending.callId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val noteId = created["noteId"]!!.jsonPrimitive.content
            val note = CanonicalJsonForTest.decodeNote(created["note"]!!)
            assertEquals(JsonPrimitive("option-active"), note.propertyValues[propertyId]!!.value)
            assertTrue(note.blocks.isNotEmpty())
            val blockId = note.blocks.first().id
            val block = fixture.owner.call(LocalCallInput("get_block", buildJsonObject { put("noteId", noteId); put("blockId", blockId.value) }))
            assertEquals(blockId.value, block.result!!.payload!!.jsonObject["block"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val suggestion = fixture.owner.call(LocalCallInput("suggest_properties", buildJsonObject { put("noteId", noteId) }))
            assertNotNull(suggestion.result!!.payload!!.jsonObject["properties"])
            val replacement = note.copy(propertyValues = note.propertyValues + (propertyId to NotePropertyValue(
                JsonPrimitive("option-done"), PropertyValueSource.USER, updatedAt = "2026-01-01T00:00:00Z")))
            val update = fixture.owner.call(LocalCallInput("update_note", buildJsonObject {
                put("noteId", noteId); put("expectedRevision", created["revision"]!!)
                put("note", com.oruke.tagloom.core.protocol.CanonicalJson.format.encodeToJsonElement(Note.serializer(), replacement))
            }))
            fixture.owner.approve(update.callId, LocalApprovalInput(true))
            val confirmed = fixture.session().loadNotes().single().snapshot.payload
            assertEquals(replacement.blocks, confirmed.blocks)
            assertEquals(JsonPrimitive("option-done"), confirmed.propertyValues[propertyId]!!.value)
            val query = fixture.session().saveQuery(SavedQuery("Saved sample", text = "rich sample", tagIds = listOf(typeId)))
            val saved = fixture.owner.resource("query://saved/${query.id.value}")
            assertEquals(query.id.value, saved.result!!.payload!!.jsonObject["queryId"]!!.jsonPrimitive.content)
            val hits = fixture.owner.call(LocalCallInput("run_saved_query", buildJsonObject { put("queryId", query.id.value) }))
            assertEquals(noteId, hits.result!!.payload!!.jsonObject["items"]!!.jsonArray.single().jsonObject["noteId"]!!.jsonPrimitive.content)
            fixture.reopen()
            assertEquals(replacement.blocks, fixture.session().loadNotes().single().snapshot.payload.blocks)
        } finally { fixture.close() }
    }

    /** Host/Origin、认证、未知字段、请求大小和速率限制均经过真实 HTTP 服务，不泄露参数。 */
    @Test
    fun rejectsUnsafeTransportAndMalformedInputs() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        val http = HttpClient(CIO)
        try {
            assertEquals(HttpStatusCode.Unauthorized, http.get(fixture.url + "/v1/tools").status)
            val foreignOrigin = http.get(fixture.url + "/v1/tools") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}"); header(HttpHeaders.Origin, "https://example.test")
            }
            assertEquals(HttpStatusCode.Forbidden, foreignOrigin.status)
            val invalid = http.post(fixture.url + "/v1/calls") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}"); contentType(ContentType.Application.Json)
                setBody("{\"tool\":\"create_note\",\"arguments\":{\"title\":\"private sample\"},\"targetNoteIds\":[]}")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertFalse(invalid.bodyAsText().contains("private sample"))
            val tooLarge = http.post(fixture.url + "/v1/calls") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}"); contentType(ContentType.Application.Json)
                setBody("x".repeat(1_048_577))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            val openapi = http.get(fixture.url + "/v1/openapi") { header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}") }
            assertEquals("3.1.0", Json.parseToJsonElement(openapi.bodyAsText()).jsonObject["openapi"]!!.jsonPrimitive.content)
            val grant = fixture.owner.grant(LocalGrantInput())
            var limited = false
            fixture.client(grant.token).use { agent ->
                repeat(121) { if (!limited) try { agent.tools() } catch (error: LocalApiException) { assertEquals(429, error.status); limited = true } }
            }
            assertTrue(limited)
        } finally { http.close(); fixture.close() }
    }
}

/** 仅测试代码需要的规范 Note 解码入口，不引入生产路径。 */
private object CanonicalJsonForTest {
    /**
     * 解码生产 API 返回的完整 Note。
     * @param element API payload 内的 Note JSON。
     * @return 对应领域对象。
     */
    fun decodeNote(element: JsonElement): Note = com.oruke.tagloom.core.protocol.CanonicalJson.format.decodeFromJsonElement(Note.serializer(), element)
}
