package com.oruke.tagloom.automation

import com.oruke.tagloom.core.external.ExternalToolArguments
import com.oruke.tagloom.core.external.ExternalToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 维护当前授权的 MCP 工具发现与调用分发，执行始终经过同一回环 API 和会话校验。 */
internal class TagloomMcpToolService(
    /** 当前 stdio 会话绑定的独立授权客户端。 */
    private val client: LocalApiClient,
    /** 当前身份发现的业务定义，不添加未获授权的工具。 */
    definitions: List<ExternalToolDefinition>,
) {
    /** 当前可发现的工具与两项调用状态管理能力。 */
    val tools: List<Tool> = definitions.map { definition ->
        Tool(name = definition.name, description = definition.description,
            inputSchema = ToolSchema(properties = definition.properties, required = definition.required),
            annotations = ToolAnnotations(readOnlyHint = definition.readOnly, destructiveHint = definition.destructive,
                idempotentHint = definition.readOnly, openWorldHint = false))
    } + listOf(
        Tool(name = "tagloom_get_call_result", description = "读取本身份调用的审批状态和确认后的结果。",
            inputSchema = McpCallArguments.schema,
            annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)),
        Tool(name = "tagloom_cancel_call", description = "取消本身份尚未确认的调用，不执行笔记变更。",
            inputSchema = McpCallArguments.schema,
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false)),
    )

    /** 当前身份在初始化时冻结的工具名称；协议错误不执行任何业务调用。 */
    private val knownNames = tools.map { it.name }.toSet()

    /**
     * 先核对发现范围，再执行并统一序列化；未知工具是协议错误，执行失败使用 isError。
     * @param request 官方 SDK 解析后的工具请求。
     * @return 兼容文字与结构化内容一致的 MCP 结果，不泄露底层异常或未经授权的工具名。
     */
    suspend fun invoke(request: CallToolRequest): CallToolResult {
        if (request.name !in knownNames) throw McpException(RPCError.ErrorCode.INVALID_PARAMS, "tool_unavailable")
        val payload: JsonObject
        val failed: Boolean
        try {
            val response = when (request.name) {
                "tagloom_get_call_result" -> client.result(McpCallArguments.callId(request.arguments))
                "tagloom_cancel_call" -> client.cancel(McpCallArguments.callId(request.arguments))
                else -> client.call(LocalCallInput(request.name, request.arguments ?: JsonObject(emptyMap())))
            }
            payload = localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), response) as JsonObject
            failed = response.result?.succeeded == false
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = buildJsonObject { put("errorCode", localApiFailure(error).code) }
            return CallToolResult(listOf(TextContent(failure.toString())), isError = true, structuredContent = failure)
        }
        return CallToolResult(listOf(TextContent(payload.toString())), isError = failed, structuredContent = payload)
    }
}

/** 提供调用状态工具的有界 ID 协议，拒绝未知参数和路径字符。 */
internal object McpCallArguments {
    /** 本身份调用查询和取消所需的严格参数说明；执行仍独立进行实际校验。 */
    val schema = ToolSchema(properties = buildJsonObject {
        put("callId", buildJsonObject { put("type", "string"); put("pattern", "^[A-Za-z0-9_-]{1,128}$") })
    }, required = listOf("callId"))

    /**
     * 从管理工具参数读取一个已校验的随机调用 ID。
     * @param arguments MCP 结构化参数。
     * @return 不包含任意路径或附加参数的调用 ID。
     */
    fun callId(arguments: JsonObject?): String {
        if (arguments?.keys != setOf("callId")) throw LocalApiException(400, "invalid_arguments")
        return ExternalToolArguments.opaque(ExternalToolArguments.string(arguments, "callId", true)!!)
    }
}
