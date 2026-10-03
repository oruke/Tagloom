package com.oruke.tagloom.automation

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.workspace.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** 从实际 API 入口验证完整 Note 写入不会绕过属性约束或确认期间的 schema 版本。 */
class LocalAutomationSchemaIntegrationTest {
    /** 无效固定选项、只读覆盖与非法日期不落盘；合法值规范为用户来源并进入同一业务事务。 */
    @Test
    fun validatesFullNotePropertiesBeforePreviewAndRechecksSchema() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val choiceId = PropertyId("choice-sample")
            val numberId = PropertyId("number-sample")
            val dateId = PropertyId("date-sample")
            val readonlyId = PropertyId("readonly-sample")
            val typeId = TagId("schema-type-sample")
            val choice = PropertyDefinition(choiceId, "Choice sample", PropertyTypes.SELECT,
                options = listOf(PropertyOption("option-a", "A sample"), PropertyOption("option-b", "B sample")))
            fixture.session().savePropertyDefinition(choice)
            fixture.session().savePropertyDefinition(PropertyDefinition(numberId, "Number sample", PropertyTypes.NUMBER))
            fixture.session().savePropertyDefinition(PropertyDefinition(dateId, "Date sample", PropertyTypes.DATE))
            fixture.session().savePropertyDefinition(PropertyDefinition(readonlyId, "Readonly sample", PropertyTypes.TEXT))
            fixture.session().saveTagDefinition(TagDefinition(typeId, "Schema sample", propertyBindings = listOf(
                TagPropertyBinding(choiceId, required = true, defaultValue = JsonPrimitive("option-a"), defaultPolicy = DefaultValuePolicy.SNAPSHOT),
                TagPropertyBinding(numberId), TagPropertyBinding(dateId),
                TagPropertyBinding(readonlyId, defaultValue = JsonPrimitive("readonly value sample"), defaultPolicy = DefaultValuePolicy.SNAPSHOT, readonly = true),
            )))
            val proposal = fixture.owner.call(LocalCallInput("create_note", buildJsonObject {
                put("title", "Property sample"); put("tagIds", JsonArray(listOf(JsonPrimitive(typeId.value))))
            }))
            val created = fixture.owner.approve(proposal.callId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), created["note"]!!)
            val invalidValues = listOf(choiceId to JsonPrimitive("missing-option"), numberId to JsonPrimitive("42"),
                dateId to JsonPrimitive("2025-02-29"), readonlyId to JsonPrimitive("forged readonly sample"))
            for ((id, value) in invalidValues) {
                val invalid = note.copy(propertyValues = note.propertyValues + (id to NotePropertyValue(value, PropertyValueSource.SYSTEM,
                    updatedAt = "2026-01-01T00:00:00Z")), manualPropertyBindings = listOf(ManualPropertyBinding(readonlyId, readonly = false)))
                val error = assertFailsWith<LocalApiException> { fixture.owner.call(updateInput(created, invalid)) }
                assertEquals(400, error.status)
                assertEquals(if (id == readonlyId) "property_readonly" else "property_value_invalid", error.code)
            }
            assertEquals(note, fixture.session().loadNotes().single().snapshot.payload)
            val valid = note.copy(propertyValues = note.propertyValues + mapOf(
                choiceId to NotePropertyValue(JsonPrimitive("option-b"), PropertyValueSource.SYSTEM, updatedAt = "2026-01-01T00:00:00Z"),
                numberId to NotePropertyValue(JsonPrimitive(42), PropertyValueSource.USER, updatedAt = "2026-01-01T00:00:00Z"),
                dateId to NotePropertyValue(JsonPrimitive("2024-02-29"), PropertyValueSource.USER, updatedAt = "2026-01-01T00:00:00Z"),
            ))
            val pending = fixture.owner.call(updateInput(created, valid))
            val saved = fixture.owner.approve(pending.callId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val confirmed = fixture.session().loadNotes().single()
            assertEquals(JsonPrimitive("option-b"), confirmed.snapshot.payload.propertyValues[choiceId]!!.value)
            assertEquals(PropertyValueSource.USER, confirmed.snapshot.payload.propertyValues[choiceId]!!.source)
            assertEquals(note.blocks, confirmed.snapshot.payload.blocks)
            fixture.assertAuditedTransaction(confirmed.id, pending.callId)
            val stale = fixture.owner.call(LocalCallInput("update_note", buildJsonObject {
                put("noteId", saved["noteId"]!!); put("expectedRevision", saved["revision"]!!); put("title", "Schema changed proposal sample")
            }))
            fixture.session().savePropertyDefinition(choice.copy(options = choice.options + PropertyOption("option-c", "C sample")))
            assertEquals("schema_changed", fixture.owner.approve(stale.callId, LocalApprovalInput(true)).result!!.errorCode)
            assertEquals(confirmed.snapshot, fixture.session().loadNotes().single().snapshot)
            fixture.reopen()
            assertEquals(confirmed.snapshot, fixture.session().loadNotes().single().snapshot)
        } finally { fixture.close() }
    }
}

/**
 * 将读取时的 revision 与完整 Note 一起传给实际更新工具。
 * @param response 实际创建或更新响应。
 * @param note 待校验完整 payload。
 * @return 不伪造授权元数据的协议请求。
 */
private fun updateInput(response: JsonObject, note: Note): LocalCallInput = LocalCallInput("update_note", buildJsonObject {
    put("noteId", response["noteId"]!!); put("expectedRevision", response["revision"]!!)
    put("note", CanonicalJson.format.encodeToJsonElement(Note.serializer(), note))
})
