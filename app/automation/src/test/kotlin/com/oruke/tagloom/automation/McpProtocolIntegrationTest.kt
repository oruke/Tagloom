package com.oruke.tagloom.automation

import com.oruke.tagloom.workspace.loadNotes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** 从实际安装分发的 stdio 入口验证协议错误与业务错误边界，不依赖 Node 或 Mock。 */
class McpProtocolIntegrationTest {
    /** 未知、未获授权工具和非法协议参数不能执行写入，失败后仍可合法读取和正常退出。 */
    @Test
    fun errorsRemainBoundedAndSessionCanContinue() = runBlocking {
        val fixture = AutomationTestWorkspace.create()
        try {
            val grant = fixture.owner.grant(LocalGrantInput())
            AutomationTestProcess(fixture, grant.token, "mcp").use { mcp ->
                assertNotNull(mcp.rpc(1, "initialize", buildJsonObject {
                    put("protocolVersion", "2025-11-25"); put("capabilities", JsonObject(emptyMap()))
                    put("clientInfo", buildJsonObject { put("name", "protocol-regression"); put("version", "1.0.0") })
                })["result"])
                mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")

                // 协议无法分发的工具与已分发的非法业务参数使用不同的错误机制。
                val unknown = mcp.rpc(2, "tools/call", buildJsonObject {
                    put("name", "unknown_tool"); put("arguments", JsonObject(emptyMap()))
                })["error"]!!.jsonObject
                assertEquals(-32602, unknown.getValue("code").jsonPrimitive.int)
                assertEquals("tool_unavailable", unknown.getValue("message").jsonPrimitive.content)
                val hidden = mcp.rpc(3, "tools/call", buildJsonObject {
                    put("name", "create_note"); put("arguments", buildJsonObject { put("title", "Unapproved sample") })
                })["error"]!!.jsonObject
                assertEquals(unknown, hidden)
                val malformed = mcp.rpc(4, "tools/call", buildJsonObject { put("arguments", JsonObject(emptyMap())) })["error"]!!.jsonObject
                assertEquals(-32602, malformed.getValue("code").jsonPrimitive.int)
                val invalid = mcp.rpc(5, "tools/call", buildJsonObject {
                    put("name", "get_note"); put("arguments", buildJsonObject { put("noteId", 123) })
                })["result"]!!.jsonObject
                assertTrue(invalid.getValue("isError").jsonPrimitive.boolean)
                assertEquals("invalid_arguments", invalid.getValue("structuredContent").jsonObject.getValue("errorCode").jsonPrimitive.content)
                assertEquals(invalid["structuredContent"], Json.parseToJsonElement(invalid.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content))
                assertEquals(-32601, mcp.rpc(6, "unknown/method")["error"]!!.jsonObject.getValue("code").jsonPrimitive.int)
                assertEquals(-32002, mcp.rpc(7, "resources/read", buildJsonObject { put("uri", "note://sample?unexpected=1") })["error"]!!.jsonObject.getValue("code").jsonPrimitive.int)

                // 合法调用能继续访问真实宿主；非法输入没有生成笔记或待确认计划。
                val valid = mcp.rpc(8, "tools/call", buildJsonObject { put("name", "list_notes") })["result"]!!.jsonObject
                assertFalse(valid.getValue("isError").jsonPrimitive.boolean)
                assertTrue(valid.getValue("structuredContent").jsonObject.getValue("result").jsonObject.getValue("payload").jsonObject.getValue("items").jsonArray.isEmpty())
                assertTrue(fixture.session().loadNotes().isEmpty())
                assertTrue(fixture.owner.approvals().items.isEmpty())
                mcp.finish()
            }
        } finally { fixture.close() }
    }
}
