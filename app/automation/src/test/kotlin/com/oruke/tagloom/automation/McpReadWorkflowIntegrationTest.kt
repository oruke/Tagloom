package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentPermissionScope
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** 经真实分发的 stdio MCP 验证十个独立知识问题的资料链，不把协议验证当成模型效果评估。 */
class McpReadWorkflowIntegrationTest {
    /** 答案只由 MCP 的查询、资源、属性继承、代码块和双链返回值推导，随后与固定 XML 逐项核对。 */
    @Test
    fun derivesArchiveAnswersFromRealMcpReadTools() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            McpReadWorkflowScenario.seed(fixture.session())
            val grant = fixture.owner.grant(LocalGrantInput(scopes = setOf(AgentPermissionScope.READ_NOTES,
                AgentPermissionScope.SEARCH_NOTES, AgentPermissionScope.SUGGEST_CHANGES)))
            AutomationTestProcess(fixture, grant.token, "mcp").use { process ->
                assertNotNull(process.rpc(1, "initialize", buildJsonObject {
                    put("protocolVersion", "2025-11-25"); put("capabilities", JsonObject(emptyMap()))
                    put("clientInfo", buildJsonObject { put("name", "read-workflow-client"); put("version", "1.0.0") })
                })["result"])
                process.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                val reader = McpReadWorkflowClient(process)
                val schema = reader.resource("workspace://schema")
                val definitions = schema["properties"]!!.jsonArray.map {
                    CanonicalJson.format.decodeFromJsonElement(PropertyDefinition.serializer(), it)
                }.associateBy { it.name }
                val status = definitions.getValue("Delivery status")
                val effort = definitions.getValue("Effort").id
                val date = definitions.getValue("Target date").id
                val types = reader.page("get_tags").map {
                    CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(), it)
                }.associateBy { it.id }
                assertEquals(7, reader.page("list_notes").size)
                val query = reader.page("get_saved_queries").single {
                    it["query"]!!.jsonObject["name"]!!.jsonPrimitive.content == "Confirmed release work"
                }
                val queryId = query["queryId"]!!.jsonPrimitive.content
                val saved = reader.call("get_saved_query", buildJsonObject { put("queryId", queryId) })
                assertEquals(query["query"], saved["query"])
                val confirmedIds = reader.page("run_saved_query", buildJsonObject { put("queryId", queryId) })
                    .map { it["noteId"]!!.jsonPrimitive.content }.toSet()
                val archiveId = reader.page("search_notes", buildJsonObject { put("query", "Release record") })
                    .single()["noteId"]!!.jsonPrimitive.content
                val archive = reader.note(archiveId)
                val archived = archive.blocks.map { (it.content as NoteReferenceBlockContent).noteId.value }
                    .associateWith { reader.note(it) }
                val confirmed = archived.filterKeys { it in confirmedIds }.values
                val reviewEntry = archived.entries.single { (_, note) -> note.blocks.count { it.content is NoteReferenceBlockContent } == 2 }
                val review = reviewEntry.value
                val dependencyIds = review.blocks.map { (it.content as NoteReferenceBlockContent).noteId.value }
                val heaviest = archived.values.maxBy { it.propertyValues.getValue(effort).value.jsonPrimitive.int }
                val heaviestConfirmed = confirmed.maxBy { it.propertyValues.getValue(effort).value.jsonPrimitive.int }
                val heaviestConfirmedId = archived.entries.single { it.value == heaviestConfirmed }.key
                val codeId = heaviestConfirmed.blocks.single { it.content is CodeBlockContent }.id.value
                val code = reader.call("get_block", buildJsonObject { put("noteId", heaviestConfirmedId); put("blockId", codeId) })
                val reviewType = review.directTagIds.mapNotNull(types::get).single { it.name != "Note" }
                val tagSchema = reader.resource("tag://${reviewType.id.value}")
                val effectiveStatus = tagSchema["effectiveProperties"]!!.jsonObject["properties"]!!.jsonArray
                    .single { it.jsonObject["propertyId"]!!.jsonPrimitive.content == status.id.value }.jsonObject
                val links = reader.page("get_backlinks", buildJsonObject { put("noteId", heaviestConfirmedId) })
                val taskBacklinks = links.map { reader.note(it["noteId"]!!.jsonPrimitive.content) }
                    .count { reviewType.id in it.directTagIds }
                val suggested = reader.page("suggest_tags", buildJsonObject { put("text", "Release task") })
                assertEquals(reviewType.id.value, suggested.single()["id"]!!.jsonPrimitive.content)
                assertNotNull(reader.call("suggest_properties", buildJsonObject { put("noteId", reviewEntry.key) })["properties"])
                val answers = mapOf(
                    "confirmed_effort" to confirmed.sumOf { it.propertyValues.getValue(effort).value.jsonPrimitive.int }.toString(),
                    "earliest_target" to confirmed.minOf { it.propertyValues.getValue(date).value.jsonPrimitive.content },
                    "review_dependencies" to dependencyIds.sumOf { archived.getValue(it).propertyValues.getValue(effort).value.jsonPrimitive.int }.toString(),
                    "largest_status" to status.options.single { it.id == heaviest.propertyValues.getValue(status.id).value.jsonPrimitive.content }.label,
                    "dependent_type" to reviewType.name,
                    "largest_confirmed_code" to code["block"]!!.jsonObject["content"]!!.jsonObject["language"]!!.jsonPrimitive.content,
                    "task_backlinks" to taskBacklinks.toString(),
                    "unconfirmed_references" to archived.keys.count { it !in confirmedIds }.toString(),
                    "inherited_status" to status.options.single { it.id == effectiveStatus["value"]!!.jsonPrimitive.content }.label,
                    "archive_last_target" to archived.values.maxOf { it.propertyValues.getValue(date).value.jsonPrimitive.content },
                )
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                val document = javaClass.getResourceAsStream("/mcp-evaluation.xml").use { input ->
                    factory.newDocumentBuilder().parse(requireNotNull(input))
                }
                val questions = document.getElementsByTagName("qa_pair")
                assertEquals(10, questions.length)
                repeat(questions.length) { index ->
                    val question = questions.item(index) as Element
                    assertEquals(question.getElementsByTagName("answer").item(0).textContent.trim(), answers[question.getAttribute("id")])
                }
                process.finish()
            }
            fixture.assertEncrypted("Storage migration", "Delivery status", "val sample = 1")
        } finally { fixture.close() }
    }
}

/** 仅测试中使用的 MCP 资料读取客户端；每次调用都经过实际 stdio、API、权限和加密审计。 */
private class McpReadWorkflowClient(
    /** 真实安装分发的 MCP 子进程。 */
    private val process: AutomationTestProcess,
) {
    /** initialize 之后递增的 JSON-RPC 请求 ID。 */
    private var requestId = 2

    /**
     * 读取一个业务工具结果，失败和未完成状态都使验证失败。
     * @param tool 已发现的只读工具名称。
     * @param arguments 严格工具参数。
     * @return 已经通过加密审计的业务 JSON。
     */
    fun call(tool: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val result = process.rpc(requestId++, "tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) })["result"]!!.jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.boolean ?: false)
        val response = result["structuredContent"]!!.jsonObject
        assertEquals("completed", response["state"]!!.jsonPrimitive.content)
        assertTrue(response["result"]!!.jsonObject["succeeded"]!!.jsonPrimitive.boolean)
        return response["result"]!!.jsonObject["payload"]!!.jsonObject
    }

    /**
     * 用小页面读取整个授权集合，以覆盖 Agent 的分页资料探索。
     * @param tool 可分页的只读工具。
     * @param arguments 查询条件，不包含分页字段。
     * @return 各页按照协议顺序拼接的业务条目。
     */
    fun page(tool: String, arguments: JsonObject = JsonObject(emptyMap())): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var offset = 0
        do {
            val result = call(tool, JsonObject(arguments + mapOf("limit" to JsonPrimitive(2), "offset" to JsonPrimitive(offset))))
            items += result["items"]!!.jsonArray.map { it.jsonObject }
            offset = result["nextOffset"]?.jsonPrimitive?.intOrNull ?: -1
        } while (offset >= 0)
        return items
    }

    /**
     * 从 MCP 资源读取固定资料。
     * @param uri 当前身份可读的资源 URI。
     * @return 资源中的业务 JSON。
     */
    fun resource(uri: String): JsonObject {
        val result = process.rpc(requestId++, "resources/read", buildJsonObject { put("uri", uri) })["result"]!!.jsonObject
        return Json.parseToJsonElement(result["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    /**
     * 从协议结果解码权威笔记，不能通过测试进程直接读取服务器内存。
     * @param id 从查询、链接或目录发现的不透明 ID。
     * @return MCP 返回的已确认领域 payload。
     */
    fun note(id: String): Note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(),
        call("get_note", buildJsonObject { put("noteId", id) })["note"]!!)
}
