package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.agent.AgentToolRequest
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 描述不包含命令行原文的稳定 CLI 参数错误。 */
class CliCommandException(
    /** 可安全显示的稳定错误码。 */
    val code: String,
) : IllegalArgumentException(code)

/** 把 CLI 参数解析成必须继续经过统一 Agent 权限和确认层的工具请求。 */
object CliCommandParser {
    /**
     * 解析只读与受控写入命令，不接受绕过预览或确认的参数。
     *
     * @param arguments 不含可执行文件名的命令行参数。
     * @param requestId 当前调用随机审计 ID。
     * @return 可交给 AgentToolCoordinator.prepare 的请求。
     */
    fun parse(
        arguments: List<String>,
        requestId: String,
    ): AgentToolRequest {
        val command = arguments.firstOrNull() ?: throw CliCommandException("command_required")
        val tail = arguments.drop(1)
        return when (command) {
            "search" -> search(tail, requestId)
            "query" -> query(tail, requestId)
            "get" -> noteTarget(AgentToolName.GET_NOTE, tail, requestId)
            "backlinks" -> noteTarget(AgentToolName.GET_BACKLINKS, tail, requestId)
            "create" -> create(tail, requestId)
            "export" -> noteTarget(AgentToolName.EXPORT_NOTE, tail, requestId)
            else -> throw CliCommandException("unknown_command")
        }
    }

    /**
     * 解析全文搜索命令。
     *
     * @param arguments search 后参数。
     * @param requestId 审计请求 ID。
     * @return 搜索工具请求。
     */
    private fun search(
        arguments: List<String>,
        requestId: String,
    ): AgentToolRequest {
        val query = arguments.joinToString(" ").trim().takeIf(String::isNotEmpty)
            ?: throw CliCommandException("search_query_required")
        return AgentToolRequest(
            requestId = requestId,
            tool = AgentToolName.SEARCH_NOTES,
            arguments = JsonObject(mapOf("query" to JsonPrimitive(query))),
        )
    }

    /**
     * 解析标签与 where 表达式查询。
     *
     * @param arguments query 后参数。
     * @param requestId 审计请求 ID。
     * @return 结构化查询工具请求。
     */
    private fun query(
        arguments: List<String>,
        requestId: String,
    ): AgentToolRequest {
        val options = parseOptions(arguments, setOf("tag", "where"))
        if (options.isEmpty()) throw CliCommandException("query_filter_required")
        return AgentToolRequest(
            requestId = requestId,
            tool = AgentToolName.QUERY_NOTES,
            arguments = JsonObject(options.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }),
        )
    }

    /**
     * 解析只需要一个 Note ID 的命令。
     *
     * @param tool 目标工具。
     * @param arguments 命令参数。
     * @param requestId 审计请求 ID。
     * @return 带 Note allow-list 目标的工具请求。
     */
    private fun noteTarget(
        tool: AgentToolName,
        arguments: List<String>,
        requestId: String,
    ): AgentToolRequest {
        if (arguments.size != 1) throw CliCommandException("note_id_required")
        val noteId = runCatching { ObjectId(arguments.single()) }
            .getOrElse { throw CliCommandException("invalid_note_id") }
        return AgentToolRequest(
            requestId = requestId,
            tool = tool,
            targetNoteIds = setOf(noteId),
            arguments = JsonObject(mapOf("noteId" to JsonPrimitive(noteId.value))),
        )
    }

    /**
     * 解析创建 Note 命令；实际写入仍需要预览与一次确认。
     *
     * @param arguments create 后选项。
     * @param requestId 审计请求 ID。
     * @return 创建工具请求。
     */
    private fun create(
        arguments: List<String>,
        requestId: String,
    ): AgentToolRequest {
        val options = parseOptions(arguments, setOf("title", "tag", "content"))
        if (options["title"].isNullOrEmpty()) throw CliCommandException("title_required")
        return AgentToolRequest(
            requestId = requestId,
            tool = AgentToolName.CREATE_NOTE,
            arguments = JsonObject(options.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }),
        )
    }

    /**
     * 解析 `--name value` 选项并允许 tag 重复。
     *
     * @param arguments 待解析参数。
     * @param allowedOptions 允许选项名。
     * @return 选项到一个或多个值的映射。
     */
    private fun parseOptions(
        arguments: List<String>,
        allowedOptions: Set<String>,
    ): Map<String, List<String>> {
        if (arguments.size % 2 != 0) throw CliCommandException("option_value_required")
        val result = mutableMapOf<String, MutableList<String>>()
        arguments.chunked(2).forEach { pair ->
            val name = pair[0].takeIf { value -> value.startsWith("--") }
                ?.removePrefix("--")
                ?.takeIf(allowedOptions::contains)
                ?: throw CliCommandException("unknown_option")
            val value = pair[1].takeIf(String::isNotBlank) ?: throw CliCommandException("option_value_required")
            result.getOrPut(name) { mutableListOf() } += value
        }
        return result
    }
}
