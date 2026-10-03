package com.oruke.tagloom.automation

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** 在真实加密索引中核对类型/属性组合查询和反向链接的授权交集。 */
class LocalAutomationQueryIntegrationTest {
    /** 先过滤授权来源再分页，不能通过双链标题或属性筛选泄露其他笔记。 */
    @Test
    fun intersectsBacklinksAndStructuredQueriesWithAllowedNotes() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val rootType = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
            val propertyId = PropertyId("query-property-sample")
            fixture.session().savePropertyDefinition(PropertyDefinition(propertyId, "Query sample", PropertyTypes.TEXT))
            val target = fixture.session().saveNote(null, Note("Target sample", listOf(rootType)))
            val values = mapOf(propertyId to NotePropertyValue(JsonPrimitive("matching sample"), PropertyValueSource.USER,
                updatedAt = "2026-01-01T00:00:00Z"))
            val sourcePayload = Note("Allowed source sample", listOf(rootType), propertyValues = values,
                blocks = listOf(Block(BlockId("reference-sample"), BlockTypes.NOTE_REFERENCE, orderKey = "a",
                    content = NoteReferenceBlockContent(target.id))))
            val source = fixture.session().saveNote(null, sourcePayload)
            fixture.session().saveNote(null, sourcePayload.copy(title = "Private source sample"))
            val grant = fixture.owner.grant(LocalGrantInput(allowedNoteIds = setOf(target.id, source.id)))
            fixture.client(grant.token).use { agent ->
                val links = agent.call(LocalCallInput("get_backlinks", buildJsonObject {
                    put("noteId", target.id.value); put("limit", 1)
                })).result!!.payload!!.jsonObject
                assertEquals(source.id.value, links["items"]!!.jsonArray.single().jsonObject["noteId"]!!.jsonPrimitive.content)
                assertFalse(links["hasMore"]!!.jsonPrimitive.boolean)
                assertFalse(links.toString().contains("Private source"))
                val hits = agent.call(LocalCallInput("query_notes", buildJsonObject {
                    put("tagIds", JsonArray(listOf(JsonPrimitive(rootType.value))))
                    put("properties", buildJsonObject { put(propertyId.value, "matching sample") }); put("limit", 1)
                })).result!!.payload!!.jsonObject
                assertEquals(source.id.value, hits["items"]!!.jsonArray.single().jsonObject["noteId"]!!.jsonPrimitive.content)
                assertFalse(hits["hasMore"]!!.jsonPrimitive.boolean)
            }
            fixture.reopen()
            assertEquals(2, fixture.session().backlinks(target.id).size)
        } finally { fixture.close() }
    }
}
