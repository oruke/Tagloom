package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentPermissionScope
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.saveTagDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证外部 HTTP / Local Automation 全链路对 `ALLOW / REFERENCE_ONLY / LOCKED` 策略边界、不可篡改护栏及系统默认页防删保护的强制执行。
 */
class Stage2AiAccessPolicyAutomationTest {
    /** 验证 `LOCKED` 对持有者与白名单一律禁读，`REFERENCE_ONLY` 在发现型接口隐身但支持显式白名单读取。 */
    @Test
    fun enforcesStrictPolicyBoundariesAcrossDiscoveryDirectReadAndAllowLists() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val vaultTagId = TagId("type-vault")
            fixture.session().saveTagDefinition(
                TagDefinition(vaultTagId, "机密凭据", aiAccessPolicy = AiAccessPolicy.LOCKED),
            )

            val allowNote = fixture.session().saveNote(
                null,
                Note(
                    title = "quarterly roadmap allow",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    blocks = listOf(
                        Block(
                            id = BlockId("b-allow-1"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("quarterly public plan")))),
                        ),
                    ),
                ),
            )
            val refOnlyNote = fixture.session().saveNote(
                null,
                Note(
                    title = "quarterly draft reference only",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
                    blocks = listOf(
                        Block(
                            id = BlockId("b-ref-1"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("quarterly draft memo")))),
                        ),
                        Block(
                            id = BlockId("b-ref-link"),
                            type = BlockTypes.NOTE_REFERENCE,
                            orderKey = "b",
                            content = NoteReferenceBlockContent(allowNote.id),
                        ),
                    ),
                ),
            )
            val inheritedLockedNote = fixture.session().saveNote(
                null,
                Note(
                    title = "quarterly master secret",
                    directTagIds = listOf(vaultTagId),
                    blocks = listOf(
                        Block(
                            id = BlockId("b-lock-1"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("quarterly secret payload")))),
                        ),
                        Block(
                            id = BlockId("b-lock-link"),
                            type = BlockTypes.NOTE_REFERENCE,
                            orderKey = "b",
                            content = NoteReferenceBlockContent(allowNote.id),
                        ),
                    ),
                ),
            )
            val savedQuery = fixture.session().saveQuery(SavedQuery("Quarterly search", text = "quarterly"))

            // 1. 发现型接口（search / list / run_saved_query / backlinks）对 owner 也必须隐藏 LOCKED 与 REFERENCE_ONLY
            val searchItems = fixture.owner.call(
                LocalCallInput("search_notes", buildJsonObject { put("query", "quarterly"); put("limit", 10) }),
            ).result!!.payload!!.jsonObject["items"]!!.jsonArray
            assertEquals(listOf(allowNote.id.value), searchItems.map { it.jsonObject["noteId"]!!.jsonPrimitive.content })

            val listItems = fixture.owner.call(
                LocalCallInput("list_notes", buildJsonObject { put("limit", 10) }),
            ).result!!.payload!!.jsonObject["items"]!!.jsonArray
            assertEquals(listOf(allowNote.id.value), listItems.map { it.jsonObject["noteId"]!!.jsonPrimitive.content })

            val queryItems = fixture.owner.call(
                LocalCallInput("run_saved_query", buildJsonObject { put("queryId", savedQuery.id.value) }),
            ).result!!.payload!!.jsonObject["items"]!!.jsonArray
            assertEquals(listOf(allowNote.id.value), queryItems.map { it.jsonObject["noteId"]!!.jsonPrimitive.content })

            val backlinkItems = fixture.owner.call(
                LocalCallInput("get_backlinks", buildJsonObject { put("noteId", allowNote.id.value) }),
            ).result!!.payload!!.jsonObject["items"]!!.jsonArray
            assertTrue(backlinkItems.isEmpty())

            // 2. LOCKED 对 owner 直接按 ID 读取或显式 allowedNoteIds 白名单一律拒绝 403
            assertEquals(
                403,
                assertFailsWith<LocalApiException> {
                    fixture.owner.call(LocalCallInput("get_note", buildJsonObject { put("noteId", inheritedLockedNote.id.value) }))
                }.status,
            )
            val whitelistLockedGrant = fixture.owner.grant(
                LocalGrantInput(
                    scopes = setOf(AgentPermissionScope.READ_NOTES),
                    allowedNoteIds = setOf(inheritedLockedNote.id, refOnlyNote.id),
                ),
            )
            fixture.client(whitelistLockedGrant.token).use { whitelistedAgent ->
                assertEquals(
                    403,
                    assertFailsWith<LocalApiException> {
                        whitelistedAgent.call(LocalCallInput("get_note", buildJsonObject { put("noteId", inheritedLockedNote.id.value) }))
                    }.status,
                )
                // REFERENCE_ONLY 在显式 allowedNoteIds 白名单内可读
                val refRead = whitelistedAgent.call(LocalCallInput("get_note", buildJsonObject { put("noteId", refOnlyNote.id.value) }))
                assertEquals(refOnlyNote.id.value, refRead.result!!.payload!!.jsonObject["noteId"]!!.jsonPrimitive.content)
            }

            // 3. 全库泛授权外部 token（无 allowedNoteIds 白名单）不能按 ID 直接读取 REFERENCE_ONLY
            val broadGrant = fixture.owner.grant(LocalGrantInput(scopes = setOf(AgentPermissionScope.READ_NOTES)))
            fixture.client(broadGrant.token).use { broadAgent ->
                assertEquals(
                    403,
                    assertFailsWith<LocalApiException> {
                        broadAgent.call(LocalCallInput("get_note", buildJsonObject { put("noteId", refOnlyNote.id.value) }))
                    }.status,
                )
            }

            // 4. owner 持有者可直接按 ID 读取 REFERENCE_ONLY
            val ownerRefRead = fixture.owner.call(LocalCallInput("get_note", buildJsonObject { put("noteId", refOnlyNote.id.value) }))
            assertEquals(refOnlyNote.id.value, ownerRefRead.result!!.payload!!.jsonObject["noteId"]!!.jsonPrimitive.content)

            // 5. 任何工具调用严禁篡改笔记 aiAccessPolicy
            val currentRevision = fixture.owner.call(
                LocalCallInput("get_note", buildJsonObject { put("noteId", allowNote.id.value) }),
            ).result!!.payload!!.jsonObject["revision"]!!.jsonPrimitive.content
            val tampered = allowNote.snapshot.payload.copy(aiAccessPolicy = AiAccessPolicy.LOCKED)
            assertEquals(
                403,
                assertFailsWith<LocalApiException> {
                    fixture.owner.call(
                        LocalCallInput(
                            "update_note",
                            buildJsonObject {
                                put("noteId", allowNote.id.value)
                                put("expectedRevision", currentRevision)
                                put("note", CanonicalJson.format.encodeToJsonElement(Note.serializer(), tampered))
                            },
                        ),
                    )
                }.status,
            )

            // 6. 系统内置「全部笔记」页面 ID 拒绝通过工具删除
            assertFailsWith<LocalApiException> {
                fixture.owner.call(
                    LocalCallInput(
                        "delete_note",
                        buildJsonObject {
                            put("noteId", SystemDatabaseDefinitions.ALL_NOTES_PAGE_ID.value)
                            put("expectedRevision", "system:1")
                        },
                    ),
                )
            }
            assertFalse(searchItems.toString().contains("secret"))
        } finally {
            fixture.close()
        }
    }
}
