package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.agent.AgentToolRequest
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.json.*

/** 从工具参数推导授权目标和条目数，禁止客户端伪造低风险授权元数据。 */
object ExternalToolRequestFactory {
    /**
     * 校验统一 JSON 参数并生成待授权请求。
     * @param tool 待执行的共享工具。
     * @param arguments 原始参数，未知字段一律拒绝。
     * @param requestId 用于去重和审计的随机请求身份。
     * @return 已推导目标、数量和风险的请求。
     */
    fun create(tool: AgentToolName, arguments: JsonObject, requestId: String): AgentToolRequest {
        val definition = ExternalToolDefinitions.definition(tool)
        if (!arguments.keys.all { it in definition.properties } || !arguments.keys.containsAll(definition.required)) {
            throw ExternalCallException("invalid_arguments")
        }
        ExternalToolArguments.opaque(requestId)
        val noteId = ExternalToolArguments.string(arguments, "noteId")?.let(ExternalToolArguments::opaque)
        listOf("blockId", "tagId", "queryId").forEach { key ->
            ExternalToolArguments.string(arguments, key)?.let(ExternalToolArguments::opaque)
        }
        ExternalToolArguments.strings(arguments, "tagIds", 10).forEach(ExternalToolArguments::opaque)
        arguments["customTagId"]?.let { element ->
            if (element != JsonNull) {
                val primitive = element as? JsonPrimitive ?: throw ExternalCallException("invalid_arguments")
                if (!primitive.isString) throw ExternalCallException("invalid_arguments")
                if (primitive.content.isNotEmpty()) ExternalToolArguments.opaque(primitive.content)
            }
        }
        arguments["propertyUpdates"]?.let { element ->
            val updates = element as? JsonObject ?: throw ExternalCallException("invalid_arguments")
            if (updates.size > 100) throw ExternalCallException("invalid_arguments")
            updates.keys.forEach(ExternalToolArguments::opaque)
        }
        arguments["operations"]?.let { element ->
            val ops = element as? JsonArray ?: throw ExternalCallException("invalid_arguments")
            if (ops.isEmpty() || ops.size > 50) throw ExternalCallException("invalid_arguments")
            ops.forEach { item ->
                val obj = item as? JsonObject ?: throw ExternalCallException("invalid_arguments")
                ExternalToolArguments.string(obj, "blockId")?.let(ExternalToolArguments::opaque)
            }
        }
        val paginated = tool in setOf(
            AgentToolName.SEARCH_NOTES, AgentToolName.QUERY_NOTES, AgentToolName.LIST_NOTES,
            AgentToolName.GET_TAGS, AgentToolName.GET_BACKLINKS, AgentToolName.GET_SAVED_QUERIES,
            AgentToolName.RUN_SAVED_QUERY, AgentToolName.SUGGEST_TAGS,
        )
        val count = if (paginated) ExternalToolArguments.integer(arguments, "limit", 20, 1..100) else 1
        ExternalToolArguments.integer(arguments, "offset", 0, 0..1_000_000)
        return AgentToolRequest(
            requestId = requestId,
            tool = tool,
            targetNoteIds = noteId?.let { setOf(ObjectId(it)) }.orEmpty(),
            itemCount = count,
            arguments = arguments,
        )
    }
}

/** 根据目录名称定位协议工具，统一处理 CLI 别名与 MCP 名称。 */
object ExternalToolNames {
    /**
     * 查找稳定工具名称。
     * @param name snake_case MCP 名称、CLI 别名或枚举名称。
     * @return 支持的工具；未知名称返回稳定错误。
     */
    fun resolve(name: String): AgentToolName = ExternalToolDefinitions.all.firstOrNull { definition ->
        definition.name == name || definition.tool.name == name
    }?.tool ?: throw ExternalCallException("unknown_tool")
}
