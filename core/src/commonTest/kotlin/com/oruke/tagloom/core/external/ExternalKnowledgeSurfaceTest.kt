package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentPermissionScope
import com.oruke.tagloom.core.agent.AgentPrincipal
import com.oruke.tagloom.core.agent.AgentToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 验证 CLI/MCP 共享工具目录默认只读、解析结果仍需统一授权且资源 URI 不可穿越。 */
class ExternalKnowledgeSurfaceTest {
    /** 验证默认只读 principal 只发现读取工具，删除即使有 scope 也要管理员显式暴露。 */
    @Test
    fun toolDiscoveryUsesScopesAndHidesDeletionByDefault() {
        val reader = AgentPrincipal(
            principalId = "mcp-reader",
            scopes = setOf(AgentPermissionScope.READ_NOTES, AgentPermissionScope.SEARCH_NOTES),
        )
        val readTools = ExternalToolCatalog.visibleTools(ExternalSurfaceKind.MCP_STDIO, reader)

        assertEquals(
            setOf("search_notes", "query_notes", "get_note", "get_block", "get_backlinks", "get_tags", "get_tag_schema",
                "list_notes", "get_workspace_schema", "get_saved_queries", "get_saved_query", "run_saved_query"),
            readTools.mapNotNull(ExternalToolDescriptor::mcpName).toSet(),
        )

        val deleter = AgentPrincipal("mcp-deleter", setOf(AgentPermissionScope.DELETE_NOTES))
        assertFalse(ExternalToolCatalog.visibleTools(ExternalSurfaceKind.MCP_HTTP, deleter).any { it.dangerous })
        assertEquals(
            listOf("delete_note"),
            ExternalToolCatalog.visibleTools(ExternalSurfaceKind.MCP_HTTP, deleter, includeDangerous = true)
                .mapNotNull(ExternalToolDescriptor::mcpName),
        )
    }

    /** 验证 CLI 写入只生成待预览请求，且不存在命令行参数绕过确认。 */
    @Test
    fun cliCreateProducesControlledWriteRequestAndRejectsBypassFlag() {
        val request = CliCommandParser.parse(
            listOf("create", "--title", "New idea", "--tag", "idea", "--content", "body"),
            requestId = "request-create",
        )

        assertEquals(AgentToolName.CREATE_NOTE, request.tool)
        assertEquals(1, request.itemCount)
        assertFailsWith<CliCommandException> {
            CliCommandParser.parse(
                listOf("create", "--title", "New idea", "--yes", "true"),
                requestId = "request-bypass",
            )
        }
    }

    /** 验证 MCP resource URI 只接受固定协议和不透明 ID。 */
    @Test
    fun mcpResourceParserRejectsTraversalAndQueryLeakage() {
        assertEquals(
            McpResourceIdentity(McpResourceKind.NOTE, "note-a"),
            McpResourceParser.parse("note://note-a"),
        )
        assertEquals(
            McpResourceIdentity(McpResourceKind.WORKSPACE_SCHEMA, "schema"),
            McpResourceParser.parse("workspace://schema"),
        )
        assertNull(McpResourceParser.parse("note://../secret"))
        assertNull(McpResourceParser.parse("note://note-a?token=value"))
    }
}
