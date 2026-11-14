package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentConfirmationLevel
import com.oruke.tagloom.core.agent.AgentPermissionScope
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.loadNotes
import com.oruke.tagloom.workspace.savePropertyDefinition
import com.oruke.tagloom.workspace.saveTagDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证阶段三 `patch_note_blocks`、`update_note_metadata` 及带属性 `create_note` 在 HTTP 回环、CLI 与 stdio MCP 三套入口上的端到端契约：
 * 1. 未触碰段落稳定 `BlockId` 与代码块原样保留、`replace_block` 继承原 `BlockId`、`remove_block` 生成 tombstone；
 * 2. 单自定义笔记类型约束（ADR-008）、只读与 `FORMULA` 属性拦截、清空属性（`null`）及 `aiAccessPolicy` 防篡改；
 * 3. `expectedRevision` 乐观锁冲突拦截与 `ALLOW / REFERENCE_ONLY / LOCKED` 权限门禁。
 */
class Stage3PatchAndMetadataAutomationTest {
    /** 验证 HTTP PATCH 路由、CLI `patch-blocks`/`update-metadata` 与 MCP 细粒度写提案及单次确认闭环。 */
    @Test
    fun httpCliAndMcpPreserveStableBlockIdsAndValidateMetadataAcrossApprovalLifecycle() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        val rawHttp = HttpClient(CIO)
        try {
            val statusPropId = PropertyId("prop-status")
            val readonlyPropId = PropertyId("prop-readonly")
            val formulaPropId = PropertyId("prop-formula")
            val specTagId = TagId("type-spec")
            val memoTagId = TagId("type-memo")

            fixture.session().savePropertyDefinition(
                PropertyDefinition(
                    statusPropId,
                    "验收状态",
                    PropertyTypes.SELECT,
                    options = listOf(
                        PropertyOption("opt-review", "评审中"),
                        PropertyOption("opt-done", "双端已验收"),
                    ),
                ),
            )
            fixture.session().savePropertyDefinition(
                PropertyDefinition(readonlyPropId, "审计流水号", PropertyTypes.TEXT),
            )
            fixture.session().savePropertyDefinition(
                PropertyDefinition(formulaPropId, "派生得分", PropertyTypes.FORMULA, formulaExpression = "1 + 1"),
            )
            fixture.session().saveTagDefinition(
                TagDefinition(
                    specTagId,
                    "技术方案",
                    propertyBindings = listOf(
                        TagPropertyBinding(
                            statusPropId,
                            defaultValue = JsonPrimitive("opt-review"),
                            defaultPolicy = DefaultValuePolicy.SNAPSHOT,
                        ),
                        TagPropertyBinding(
                            readonlyPropId,
                            defaultValue = JsonPrimitive("AUD-001"),
                            defaultPolicy = DefaultValuePolicy.SNAPSHOT,
                            readonly = true,
                        ),
                        TagPropertyBinding(formulaPropId),
                    ),
                ),
            )
            fixture.session().saveTagDefinition(TagDefinition(memoTagId, "会议纪要"))

            // 1. 通过 CLI create --set-properties 创建带类型与初始属性值的笔记（仅生成待审批预览）
            val cliCreateCallId = AutomationTestProcess(
                fixture,
                fixture.ownerToken,
                "create",
                "--title",
                "双向同步验收说明",
                "--content",
                "第一段背景\n\n第二段旧正文",
                "--tag",
                specTagId.value,
                "--set-properties",
                """{"prop-status":"opt-review"}""",
            ).use { cli ->
                val resp = cli.jsonLine()
                assertEquals("pending", resp["state"]!!.jsonPrimitive.content)
                assertTrue(fixture.session().loadNotes().isEmpty(), "未确认前磁盘严禁出现新建笔记")
                val id = resp["callId"]!!.jsonPrimitive.content
                cli.finish()
                id
            }
            val createdPayload = fixture.owner.approve(cliCreateCallId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val createdId = createdPayload["noteId"]!!.jsonPrimitive.content
            val createdNote = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), createdPayload["note"]!!)
            assertEquals("opt-review", createdNote.propertyValues[statusPropId]?.value?.jsonPrimitive?.content)

            // 2. 直接构造带显式稳定 BlockId 与代码块的权威笔记，验证 HTTP PATCH /v1/notes/{noteId}/blocks 与 /metadata
            val seeded = fixture.session().saveNote(
                null,
                Note(
                    title = "工作区双向同步设计",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, specTagId),
                    blocks = listOf(
                        Block(
                            id = BlockId("blk-sync-1"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "0000000000",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("首段加密协议不变")))),
                        ),
                        Block(
                            id = BlockId("blk-sync-2"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "0000000001",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("待替换的旧合并描述")))),
                        ),
                        Block(
                            id = BlockId("blk-sync-code"),
                            type = BlockTypes.CODE,
                            orderKey = "0000000002",
                            content = CodeBlockContent(language = "kotlin", text = "val protocolVersion = 3"),
                        ),
                    ),
                ),
            )
            val rev1 = "${seeded.snapshot.header.revision.actorId.value}:${seeded.snapshot.header.revision.sequence}"

            // 只读属性与 FORMULA 属性一律拒绝 400 property_readonly
            val readonlyErr = assertFailsWith<LocalApiException> {
                fixture.owner.call(
                    LocalCallInput(
                        "update_note_metadata",
                        buildJsonObject {
                            put("noteId", seeded.id.value)
                            put("expectedRevision", rev1)
                            put("propertyUpdates", buildJsonObject { put(readonlyPropId.value, "HACKED") })
                        },
                    ),
                )
            }
            assertEquals(400, readonlyErr.status)
            assertEquals("property_readonly", readonlyErr.code)

            val formulaErr = assertFailsWith<LocalApiException> {
                fixture.owner.call(
                    LocalCallInput(
                        "update_note_metadata",
                        buildJsonObject {
                            put("noteId", seeded.id.value)
                            put("expectedRevision", rev1)
                            put("propertyUpdates", buildJsonObject { put(formulaPropId.value, "999") })
                        },
                    ),
                )
            }
            assertEquals(400, formulaErr.status)
            assertEquals("property_readonly", formulaErr.code)

            // HTTP PATCH /v1/notes/{noteId}/metadata 预览并确认
            val patchMetaHttp = rawHttp.patch("${fixture.url}/v1/notes/${seeded.id.value}/metadata") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("expectedRevision", rev1)
                        put("propertyUpdates", buildJsonObject { put(statusPropId.value, "opt-done") })
                    }.toString(),
                )
            }
            assertEquals(HttpStatusCode.Accepted, patchMetaHttp.status)
            val patchMetaJson = Json.parseToJsonElement(patchMetaHttp.bodyAsText()).jsonObject
            assertEquals(AgentConfirmationLevel.SINGLE.name, patchMetaJson["confirmationLevel"]!!.jsonPrimitive.content)
            val metaApproved = fixture.owner.approve(
                patchMetaJson["callId"]!!.jsonPrimitive.content,
                LocalApprovalInput(true),
            ).result!!.payload!!.jsonObject
            val rev2 = metaApproved["revision"]!!.jsonPrimitive.content
            val afterMetaNote = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), metaApproved["note"]!!)
            assertEquals("opt-done", afterMetaNote.propertyValues[statusPropId]?.value?.jsonPrimitive?.content)
            assertEquals(
                listOf("blk-sync-1", "blk-sync-2", "blk-sync-code"),
                afterMetaNote.blocks.filterNot { it.deleted }.map { it.id.value },
                "元数据更新必须 100% 保留原有稳定 BlockId",
            )

            // HTTP PATCH /v1/notes/{noteId}/blocks 局部替换 blk-sync-2 并在其后插入新段落，未触碰块原样保留
            val patchBlocksHttp = rawHttp.patch("${fixture.url}/v1/notes/${seeded.id.value}/blocks") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.ownerToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("expectedRevision", rev2)
                        put(
                            "operations",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("op", "replace_block")
                                        put("blockId", "blk-sync-2")
                                        put("markdown", "已完成 Windows 与 Android 双端加密快照合并验收。")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("op", "insert_after_block")
                                        put("blockId", "blk-sync-2")
                                        put("markdown", "补充 AGENT_THREAD 提案状态三路合并说明。")
                                    },
                                )
                            },
                        )
                    }.toString(),
                )
            }
            assertEquals(HttpStatusCode.Accepted, patchBlocksHttp.status)
            val patchBlocksCallId = Json.parseToJsonElement(patchBlocksHttp.bodyAsText()).jsonObject["callId"]!!.jsonPrimitive.content
            val blocksApproved = fixture.owner.approve(patchBlocksCallId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val rev3 = blocksApproved["revision"]!!.jsonPrimitive.content
            val afterBlocksNote = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), blocksApproved["note"]!!)
            val activeBlocks = afterBlocksNote.blocks.filterNot { it.deleted }.sortedBy { it.orderKey }
            assertEquals(4, activeBlocks.size)
            assertEquals("blk-sync-1", activeBlocks[0].id.value)
            assertEquals("blk-sync-2", activeBlocks[1].id.value, "replace_block 首块必须继承原稳定 BlockId")
            assertEquals("blk-sync-code", activeBlocks[3].id.value, "未触碰代码块必须保留原稳定 BlockId")
            assertEquals("val protocolVersion = 3", (activeBlocks[3].content as CodeBlockContent).text)

            // 过期 expectedRevision 立即返回 409 revision_conflict
            val staleConflict = assertFailsWith<LocalApiException> {
                fixture.owner.call(
                    LocalCallInput(
                        "patch_note_blocks",
                        buildJsonObject {
                            put("noteId", seeded.id.value)
                            put("expectedRevision", rev1)
                            put(
                                "operations",
                                JsonArray(listOf(buildJsonObject { put("op", "append_blocks"); put("markdown", "过期提交") })),
                            )
                        },
                    ),
                )
            }
            assertEquals(409, staleConflict.status)
            assertEquals("revision_conflict", staleConflict.code)

            // 3. CLI update-metadata 切换单自定义类型（保留内置根 Note）并清空属性（null），及 MCP patch_note_blocks remove_block 墓碑
            val cliMetaCallId = AutomationTestProcess(
                fixture,
                fixture.ownerToken,
                "update-metadata",
                seeded.id.value,
                "--revision",
                rev3,
                "--customTagId",
                memoTagId.value,
                "--set-properties",
                """{"prop-status":null}""",
            ).use { cli ->
                val id = cli.jsonLine()["callId"]!!.jsonPrimitive.content
                cli.finish()
                id
            }
            val cliMetaApproved = fixture.owner.approve(cliMetaCallId, LocalApprovalInput(true)).result!!.payload!!.jsonObject
            val rev4 = cliMetaApproved["revision"]!!.jsonPrimitive.content
            val afterCliMeta = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), cliMetaApproved["note"]!!)
            assertEquals(setOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, memoTagId), afterCliMeta.directTagIds.toSet())
            assertNull(afterCliMeta.propertyValues[statusPropId])

            val grant = fixture.owner.grant(
                LocalGrantInput(
                    scopes = setOf(AgentPermissionScope.READ_NOTES, AgentPermissionScope.WRITE_NOTES),
                ),
            )
            AutomationTestProcess(fixture, grant.token, "mcp").use { mcp ->
                mcp.rpc(
                    1,
                    "initialize",
                    buildJsonObject {
                        put("protocolVersion", "2025-11-25")
                        put("capabilities", buildJsonObject {})
                        put("clientInfo", buildJsonObject { put("name", "stage3-mcp"); put("version", "1.0.0") })
                    },
                )
                mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                val mcpTools = mcp.rpc(2, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    .toSet()
                assertTrue("patch_note_blocks" in mcpTools)
                assertTrue("update_note_metadata" in mcpTools)
                assertFalse("delete_note" in mcpTools)
                assertFalse("export_note" in mcpTools)

                val insertedBlockId = activeBlocks[2].id.value
                val mcpPatch = mcp.rpc(
                    3,
                    "tools/call",
                    buildJsonObject {
                        put("name", "patch_note_blocks")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("noteId", seeded.id.value)
                                put("expectedRevision", rev4)
                                put(
                                    "operations",
                                    buildJsonArray {
                                        add(buildJsonObject { put("op", "remove_block"); put("blockId", insertedBlockId) })
                                    },
                                )
                            },
                        )
                    },
                )["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                assertEquals("pending", mcpPatch["state"]!!.jsonPrimitive.content)
                val approvedRemove = fixture.owner.approve(
                    mcpPatch["callId"]!!.jsonPrimitive.content,
                    LocalApprovalInput(true),
                ).result!!.payload!!.jsonObject
                val afterRemove = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), approvedRemove["note"]!!)
                assertTrue(afterRemove.blocks.any { it.id.value == insertedBlockId && it.deleted }, "remove_block 必须生成 deleted=true 的 tombstone")
                assertEquals(
                    listOf("blk-sync-1", "blk-sync-2", "blk-sync-code"),
                    afterRemove.blocks.filterNot { it.deleted }.sortedBy { it.orderKey }.map { it.id.value },
                )
                mcp.finish()
            }

            // 4. LOCKED 与未白名单授权的 REFERENCE_ONLY 对新细粒度写工具同样严格拦截 403
            val lockedNote = fixture.session().saveNote(
                null,
                Note(
                    title = "绝密恢复金库",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    aiAccessPolicy = AiAccessPolicy.LOCKED,
                    blocks = listOf(
                        Block(
                            id = BlockId("b-lock"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("secret")))),
                        ),
                    ),
                ),
            )
            val lockedRev = "${lockedNote.snapshot.header.revision.actorId.value}:${lockedNote.snapshot.header.revision.sequence}"
            assertEquals(
                403,
                assertFailsWith<LocalApiException> {
                    fixture.owner.call(
                        LocalCallInput(
                            "update_note_metadata",
                            buildJsonObject { put("noteId", lockedNote.id.value); put("expectedRevision", lockedRev); put("title", "篡改") },
                        ),
                    )
                }.status,
            )

            val refOnlyNote = fixture.session().saveNote(
                null,
                Note(
                    title = "仅引用草稿",
                    directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
                    blocks = listOf(
                        Block(
                            id = BlockId("b-ref"),
                            type = BlockTypes.PARAGRAPH,
                            orderKey = "a",
                            content = RichTextBlockContent(RichText(listOf(InlineRun("ref body")))),
                        ),
                    ),
                ),
            )
            val refRev = "${refOnlyNote.snapshot.header.revision.actorId.value}:${refOnlyNote.snapshot.header.revision.sequence}"
            fixture.client(grant.token).use { broadAgent ->
                assertEquals(
                    403,
                    assertFailsWith<LocalApiException> {
                        broadAgent.call(
                            LocalCallInput(
                                "patch_note_blocks",
                                buildJsonObject {
                                    put("noteId", refOnlyNote.id.value)
                                    put("expectedRevision", refRev)
                                    put("operations", buildJsonArray { add(buildJsonObject { put("op", "append_blocks"); put("markdown", "x") }) })
                                },
                            ),
                        )
                    }.status,
                )
            }
            assertTrue(createdId.isNotBlank())
        } finally {
            rawHttp.close()
            fixture.close()
        }
    }
}
