package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentToolName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 描述 API、CLI 与 MCP 共同使用的工具输入协议和风险提示。 */
@Serializable
data class ExternalToolDefinition(
    /** 共享领域工具。 */
    val tool: AgentToolName,
    /** 对外稳定的 snake_case 名称。 */
    val name: String,
    /** 说明职责、确认流程和边界的工具描述。 */
    val description: String,
    /** JSON Schema 对象属性。 */
    val properties: JsonObject,
    /** 必填参数名称。 */
    val required: List<String>,
    /** 是否只读取或提出建议，不修改笔记。 */
    val readOnly: Boolean,
    /** 是否包含删除语义。 */
    val destructive: Boolean = false,
)

/** 维护可执行知识工具的输入 Schema；发现和执行分别验证权限。 */
object ExternalToolDefinitions {
    /** 有界集合查询通用参数。 */
    private val pagination = mapOf(
        "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100); put("default", 20) },
        "offset" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 1_000_000); put("default", 0) },
    )
    /** 单个不透明标识参数的 Schema。 */
    private val id = buildJsonObject { put("type", "string"); put("pattern", "^[A-Za-z0-9_-]{1,128}$") }
    /** 结构化对象参数由领域 serializer 与校验器再次校验。 */
    private val objectValue = buildJsonObject { put("type", "object") }
    /** 笔记类型 ID 列表，不接受名称作为身份。 */
    private val tagIds = buildJsonObject {
        put("type", "array"); put("items", id); put("maxItems", 10); put("uniqueItems", true)
    }
    /** 全部已实现工具的稳定协议定义。 */
    val all: List<ExternalToolDefinition> = listOf(
        entry(AgentToolName.LIST_NOTES, "list_notes", "分页列出授权笔记摘要，不返回正文。", pagination),
        entry(AgentToolName.SEARCH_NOTES, "search_notes", "搜索已确认的全文索引；授权范围在分页前应用。",
            pagination + mapOf("query" to text()), listOf("query")),
        entry(AgentToolName.QUERY_NOTES, "query_notes", "按类型 ID、文本和属性规范 JSON 精确匹配查询，筛选条件取交集。",
            pagination + mapOf("text" to text(), "tagIds" to tagIds, "properties" to objectValue)),
        entry(AgentToolName.GET_NOTE, "get_note", "读取授权笔记、Block、RichText 与 revision；revision 用于后续更新。",
            mapOf("noteId" to id), listOf("noteId")),
        entry(AgentToolName.GET_BLOCK, "get_block", "读取授权笔记中的一个 Block 与笔记 revision。",
            mapOf("noteId" to id, "blockId" to id), listOf("noteId", "blockId")),
        entry(AgentToolName.GET_BACKLINKS, "get_backlinks", "分页读取可访问来源笔记的反向链接。",
            pagination + mapOf("noteId" to id, "blockId" to id), listOf("noteId")),
        entry(AgentToolName.GET_TAGS, "get_tags", "分页列出笔记类型定义；受限身份只看到授权笔记相关类型及祖先。", pagination),
        entry(AgentToolName.GET_TAG_SCHEMA, "get_tag_schema", "读取笔记类型定义、继承属性、默认值、select 选项和显式冲突。",
            mapOf("tagId" to id), listOf("tagId")),
        entry(AgentToolName.GET_WORKSPACE_SCHEMA, "get_workspace_schema", "读取当前授权范围的类型及属性定义。", emptyMap()),
        entry(AgentToolName.GET_SAVED_QUERIES, "get_saved_queries", "分页列出保存查询；受限身份不发现工作区级查询。", pagination),
        entry(AgentToolName.GET_SAVED_QUERY, "get_saved_query", "读取保存查询定义；需要全工作区读取范围。",
            mapOf("queryId" to id), listOf("queryId")),
        entry(AgentToolName.RUN_SAVED_QUERY, "run_saved_query", "执行保存查询并只返回授权范围内的分页结果。",
            pagination + mapOf("queryId" to id), listOf("queryId")),
        entry(AgentToolName.SUGGEST_TAGS, "suggest_tags", "按文本匹配本地笔记类型名称，仅返回候选，不使用远程 AI。",
            pagination + mapOf("text" to text(), "noteId" to id)),
        entry(AgentToolName.SUGGEST_PROPERTIES, "suggest_properties", "根据笔记类型继承计算当前笔记的有效默认值，不直接写入。",
            mapOf("noteId" to id), listOf("noteId")),
        entry(AgentToolName.CREATE_NOTE, "create_note", "从 Markdown 创建笔记；先返回预览，由持有者确认后写入。",
            mapOf("title" to text(), "markdown" to text(), "tagIds" to tagIds), listOf("title"), readOnly = false),
        entry(AgentToolName.UPDATE_NOTE, "update_note", "凭 expectedRevision 更新标题、Markdown 或完整 Note（含 Block 和属性）；先预览再确认。",
            mapOf("noteId" to id, "expectedRevision" to text(), "title" to text(), "markdown" to text(), "note" to objectValue),
            listOf("noteId", "expectedRevision"), readOnly = false),
        entry(AgentToolName.ADD_TAG_TO_NOTE, "add_tag_to_note", "凭 expectedRevision 设置笔记类型，保留内置 Note；需要持有者确认。",
            mapOf("noteId" to id, "expectedRevision" to text(), "tagId" to id),
            listOf("noteId", "expectedRevision", "tagId"), readOnly = false),
        entry(AgentToolName.EXPORT_NOTE, "export_note", "导出标准或 Tagloom 扩展 Markdown，预览有损能力后由持有者二次确认。",
            mapOf("noteId" to id, "expectedRevision" to text(), "mode" to buildJsonObject {
                put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("standard"), JsonPrimitive("extended"))))
            }), listOf("noteId", "expectedRevision")),
        entry(AgentToolName.DELETE_NOTE, "delete_note", "凭 expectedRevision 创建 tombstone；默认隐藏，持有者二次确认。",
            mapOf("noteId" to id, "expectedRevision" to text()), listOf("noteId", "expectedRevision"),
            readOnly = false, destructive = true),
    )

    /**
     * 获取支持工具的定义。
     * @param tool 共享工具。
     * @return 协议定义；未实现的远程 AI 工具拒绝发现和执行。
     */
    fun definition(tool: AgentToolName): ExternalToolDefinition = all.singleOrNull { it.tool == tool }
        ?: throw ExternalCallException("tool_unavailable")

    /**
     * 构造一个业务工具定义。
     * @param tool 领域工具。
     * @param name 协议名称。
     * @param description 工具用途与边界。
     * @param properties 输入属性 Schema。
     * @param required 必填属性。
     * @param readOnly 是否不修改笔记。
     * @param destructive 是否删除。
     * @return 完整工具定义。
     */
    private fun entry(tool: AgentToolName, name: String, description: String, properties: Map<String, JsonElement>,
        required: List<String> = emptyList(), readOnly: Boolean = true, destructive: Boolean = false,
    ): ExternalToolDefinition = ExternalToolDefinition(tool, name, description, JsonObject(properties), required, readOnly, destructive)

    /** @return 有界字符串参数 Schema；正文大小另由传输层与业务层限制。 */
    private fun text(): JsonObject = buildJsonObject { put("type", "string"); put("maxLength", 1_000_000) }
}
