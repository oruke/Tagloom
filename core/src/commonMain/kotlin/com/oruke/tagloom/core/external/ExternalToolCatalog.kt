package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentPermissionScope
import com.oruke.tagloom.core.agent.AgentPrincipal
import com.oruke.tagloom.core.agent.AgentToolName

/** 定义同一知识工具协议暴露到的外部入口。 */
enum class ExternalSurfaceKind {
    /** 本机命令行。 */
    CLI,

    /** 本机子进程 stdio MCP。 */
    MCP_STDIO,

    /** 带独立 Token 与 scope 的 Streamable HTTP MCP。 */
    MCP_HTTP,
}

/** 描述一个外部入口可发现但仍需逐次授权的工具。 */
data class ExternalToolDescriptor(
    /** 共享核心工具名称。 */
    val tool: AgentToolName,
    /** CLI 使用的短命令；无 CLI 入口时为空。 */
    val cliCommand: String?,
    /** MCP tools/list 使用的 snake_case 名称；无 MCP 入口时为空。 */
    val mcpName: String?,
    /** 工具要求的最小 scope。 */
    val requiredScope: AgentPermissionScope,
    /** 是否属于删除类高风险工具且默认不进入发现列表。 */
    val dangerous: Boolean,
)

/** 提供 CLI 与 MCP 共享、稳定且默认只读的工具发现目录。 */
object ExternalToolCatalog {
    /**
     * 返回当前 principal 和入口可以发现的工具。
     *
     * 实际调用仍必须经过 AgentAuthorizationService；工具发现不是执行授权。
     *
     * @param surface 外部入口类型。
     * @param principal 当前 token 或本机会话 principal。
     * @param includeDangerous 是否由管理员显式暴露删除工具。
     * @return 按外部名称稳定排序的工具描述。
     */
    fun visibleTools(
        surface: ExternalSurfaceKind,
        principal: AgentPrincipal,
        includeDangerous: Boolean = false,
    ): List<ExternalToolDescriptor> = ALL_TOOLS
        .filter { descriptor -> descriptor.requiredScope in principal.scopes }
        .filter { descriptor -> includeDangerous || !descriptor.dangerous }
        .filter { descriptor ->
            when (surface) {
                ExternalSurfaceKind.CLI -> descriptor.cliCommand != null
                ExternalSurfaceKind.MCP_STDIO,
                ExternalSurfaceKind.MCP_HTTP,
                -> descriptor.mcpName != null
            }
        }
        .sortedBy { descriptor ->
            when (surface) {
                ExternalSurfaceKind.CLI -> descriptor.cliCommand
                ExternalSurfaceKind.MCP_STDIO,
                ExternalSurfaceKind.MCP_HTTP,
                -> descriptor.mcpName
            }
        }

    /** CLI 与 MCP 共享的完整工具目录；删除工具仍由 [visibleTools] 默认隐藏。 */
    private val ALL_TOOLS: List<ExternalToolDescriptor> = listOf(
        tool(AgentToolName.SEARCH_NOTES, "search", "search_notes", AgentPermissionScope.SEARCH_NOTES),
        tool(AgentToolName.QUERY_NOTES, "query", "query_notes", AgentPermissionScope.SEARCH_NOTES),
        tool(AgentToolName.GET_NOTE, "get", "get_note", AgentPermissionScope.READ_NOTES),
        tool(AgentToolName.GET_BLOCK, null, "get_block", AgentPermissionScope.READ_NOTES),
        tool(AgentToolName.GET_BACKLINKS, "backlinks", "get_backlinks", AgentPermissionScope.SEARCH_NOTES),
        tool(AgentToolName.GET_TAGS, "tags", "get_tags", AgentPermissionScope.READ_NOTES),
        tool(AgentToolName.GET_TAG_SCHEMA, "tag-schema", "get_tag_schema", AgentPermissionScope.READ_NOTES),
        tool(AgentToolName.SUGGEST_TAGS, null, "suggest_tags", AgentPermissionScope.SUGGEST_CHANGES),
        tool(AgentToolName.SUGGEST_PROPERTIES, null, "suggest_properties", AgentPermissionScope.SUGGEST_CHANGES),
        tool(AgentToolName.CREATE_NOTE, "create", "create_note", AgentPermissionScope.WRITE_NOTES),
        tool(AgentToolName.UPDATE_NOTE, "update", "update_note", AgentPermissionScope.WRITE_NOTES),
        tool(AgentToolName.ADD_TAG_TO_NOTE, "add-tag", "add_tag_to_note", AgentPermissionScope.WRITE_NOTES),
        tool(AgentToolName.EXPORT_NOTE, "export", "export_note", AgentPermissionScope.EXPORT_NOTES),
        tool(AgentToolName.DELETE_NOTE, null, "delete_note", AgentPermissionScope.DELETE_NOTES, dangerous = true),
    )

    /**
     * 简化稳定工具描述的构造。
     *
     * @param tool 共享工具名称。
     * @param cli CLI 命令。
     * @param mcp MCP 名称。
     * @param scope 最小权限。
     * @param dangerous 是否默认隐藏。
     * @return 工具描述。
     */
    private fun tool(
        tool: AgentToolName,
        cli: String?,
        mcp: String?,
        scope: AgentPermissionScope,
        dangerous: Boolean = false,
    ): ExternalToolDescriptor = ExternalToolDescriptor(tool, cli, mcp, scope, dangerous)
}
