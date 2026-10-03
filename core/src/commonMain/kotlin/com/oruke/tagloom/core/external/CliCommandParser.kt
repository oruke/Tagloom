package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.*
import kotlinx.serialization.json.*

/** 描述不包含命令行原文的稳定 CLI 参数错误。 */
class CliCommandException(
    /** 可安全显示的稳定错误码。 */
    val code: String,
) : IllegalArgumentException(code)

/** 将 CLI 参数转成与 API/MCP 相同的工具输入，不能通过命令行标志绕过持有者确认。 */
object CliCommandParser {
    /**
     * 解析快捷命令或 `call <tool> --json <object>`。
     * @param arguments 不含可执行文件名的参数。
     * @param requestId 随机审计和幂等请求身份。
     * @return 仍必须进入权限、预览、确认和审计的工具请求。
     */
    fun parse(arguments: List<String>, requestId: String): AgentToolRequest {
        try {
            val command = arguments.firstOrNull() ?: throw CliCommandException("command_required")
            if (command == "call") {
                if (arguments.size != 4 || arguments[2] != "--json") throw CliCommandException("json_arguments_required")
                val tool = ExternalToolNames.resolve(arguments[1])
                val args = Json.parseToJsonElement(arguments[3]) as? JsonObject ?: throw CliCommandException("invalid_arguments")
                return ExternalToolRequestFactory.create(tool, args, requestId)
            }
            val descriptor = ExternalToolCatalog.visibleTools(ExternalSurfaceKind.CLI,
                AgentPrincipal("cli-parser", AgentPermissionScope.entries.toSet()), includeDangerous = true,
            ).singleOrNull { it.cliCommand == command } ?: throw CliCommandException("unknown_command")
            return ExternalToolRequestFactory.create(descriptor.tool, parseArguments(descriptor.tool, arguments.drop(1)), requestId)
        } catch (error: ExternalCallException) { throw CliCommandException(error.code) }
        catch (_: kotlinx.serialization.SerializationException) { throw CliCommandException("invalid_arguments") }
    }

    /**
     * 将位置参数和严格成对选项映射到工具参数。
     * @param tool 共享工具身份。
     * @param arguments 命令名称之后的参数。
     * @return 同一个工具 JSON 协议对象。
     */
    private fun parseArguments(tool: AgentToolName, arguments: List<String>): JsonObject {
        val result = mutableMapOf<String, JsonElement>()
        val optionsStart = arguments.indexOfFirst { it.startsWith("--") }.let { if (it < 0) arguments.size else it }
        val positional = arguments.take(optionsStart)
        val positionalNames = when (tool) {
            AgentToolName.GET_NOTE, AgentToolName.GET_BACKLINKS, AgentToolName.UPDATE_NOTE,
            AgentToolName.ADD_TAG_TO_NOTE, AgentToolName.DELETE_NOTE, AgentToolName.EXPORT_NOTE,
            AgentToolName.SUGGEST_PROPERTIES -> listOf("noteId")
            AgentToolName.GET_BLOCK -> listOf("noteId", "blockId")
            AgentToolName.GET_TAG_SCHEMA -> listOf("tagId")
            AgentToolName.GET_SAVED_QUERY, AgentToolName.RUN_SAVED_QUERY -> listOf("queryId")
            else -> emptyList()
        }
        if (tool == AgentToolName.SEARCH_NOTES) {
            if (positional.isEmpty()) throw CliCommandException("search_query_required")
            result["query"] = JsonPrimitive(positional.joinToString(" "))
        } else {
            if (positional.size != positionalNames.size) throw CliCommandException("resource_id_required")
            positionalNames.zip(positional).forEach { (name, value) -> result[name] = JsonPrimitive(value) }
        }
        val options = arguments.drop(optionsStart)
        if (options.size % 2 != 0) throw CliCommandException("option_value_required")
        options.chunked(2).forEach { pair ->
            if (!pair[0].startsWith("--") || pair[1].isBlank()) throw CliCommandException("option_value_required")
            val name = when (pair[0].removePrefix("--")) {
                "content" -> "markdown"
                "revision" -> "expectedRevision"
                "tag" -> "tagIds"
                "where" -> "properties"
                else -> pair[0].removePrefix("--")
            }
            if (name !in ExternalToolDefinitions.definition(tool).properties) throw CliCommandException("unknown_option")
            if (name in result && name !in setOf("tagIds", "properties")) throw CliCommandException("duplicate_option")
            result[name] = when (name) {
                "limit", "offset" -> JsonPrimitive(pair[1].toIntOrNull() ?: throw CliCommandException("invalid_arguments"))
                "tagIds" -> JsonArray((result[name] as? JsonArray).orEmpty() + JsonPrimitive(pair[1]))
                "note", "properties" -> if (pair[0] == "--where") {
                    val components = pair[1].split('=', limit = 2)
                    if (components.size != 2) throw CliCommandException("invalid_arguments")
                    val value = Json.parseToJsonElement(components[1].trim())
                    val propertyId = ExternalToolArguments.opaque(components[0].trim())
                    JsonObject((result[name] as? JsonObject).orEmpty() + (propertyId to value))
                } else Json.parseToJsonElement(pair[1]).takeIf { it is JsonObject } ?: throw CliCommandException("invalid_arguments")
                else -> JsonPrimitive(pair[1])
            }
        }
        return JsonObject(result)
    }
}
