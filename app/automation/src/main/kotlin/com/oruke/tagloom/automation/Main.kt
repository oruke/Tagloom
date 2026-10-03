package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentConfirmationLevel
import com.oruke.tagloom.core.external.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 启动可执行 CLI 或 stdio MCP；凭据与 API 地址通过环境传入，不进入进程参数或日志。
 * @param arguments 快捷工具命令、审批管理命令或 `mcp`。
 */
fun main(arguments: Array<String>) = runBlocking {
    // SDK 日志库默认向 stdout 打印初始化提示，必须在加载 SDK 前关闭，以保护 stdio JSON-RPC。
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val exitCode = try {
        if (arguments.isEmpty() || arguments[0] in setOf("help", "--help", "-h")) {
            println(CLI_USAGE); 0
        } else {
            val url = System.getenv("TAGLOOM_API_URL") ?: throw LocalApiException(400, "api_url_required")
            val token = System.getenv("TAGLOOM_API_TOKEN") ?: throw LocalApiException(401, "api_token_required")
            LocalApiClient(url, token).use { client ->
                if (arguments.singleOrNull() == "mcp") { TagloomMcpServer(client).run(); 0 }
                else runCliCommand(client, arguments.toList())
            }
        }
    } catch (error: CliCommandException) { System.err.println(error.code); 2 }
    catch (error: Exception) { System.err.println(localApiFailure(error).code); 1 }
    if (exitCode != 0) exitProcess(exitCode)
}

/**
 * 执行 JSON CLI 命令；创建、修改、导出、删除只生成待审批项。
 * @param client 当前会话授权的回环客户端。
 * @param arguments 不含可执行名称的参数。
 * @return 成功为 0，已完成工具失败为 1。
 */
internal suspend fun runCliCommand(client: LocalApiClient, arguments: List<String>): Int {
    val discovery = client.tools()
    client.bindSession(discovery.sessionId)
    val output: JsonElement = when (arguments[0]) {
        "tools" -> {
            if (arguments.size != 1) throw CliCommandException("invalid_arguments")
            localApiJson.encodeToJsonElement(LocalToolList.serializer(), discovery)
        }
        "approvals" -> {
            if (arguments.size != 1) throw CliCommandException("invalid_arguments")
            localApiJson.encodeToJsonElement(LocalJsonPage.serializer(), client.approvals())
        }
        "approve" -> approveFromConsole(client, arguments)
        "result", "cancel" -> {
            if (arguments.size != 2) throw CliCommandException("call_id_required")
            val result = if (arguments[0] == "result") client.result(arguments[1]) else client.cancel(arguments[1])
            localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), result)
        }
        "grant" -> {
            if (arguments.size != 3 || arguments[1] != "--json") throw CliCommandException("json_arguments_required")
            val input = localApiJson.decodeFromString(LocalGrantInput.serializer(), arguments[2])
            localApiJson.encodeToJsonElement(LocalGrantResponse.serializer(), client.grant(input))
        }
        "revoke" -> {
            if (arguments.size != 2) throw CliCommandException("principal_id_required")
            client.revoke(arguments[1])
            buildJsonObject { put("revoked", true) }
        }
        else -> {
            val request = CliCommandParser.parse(arguments, UUID.randomUUID().toString())
            val response = client.call(LocalCallInput(ExternalToolDefinitions.definition(request.tool).name, request.arguments as JsonObject, request.requestId))
            localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), response)
        }
    }
    println(output)
    return if ((output as? JsonObject)?.get("result")?.jsonObject?.get("succeeded")?.jsonPrimitive?.booleanOrNull == false) 1 else 0
}

/**
 * 由持有者审阅具体预览后在终端明确确认，Agent Token 无法读取审批或执行批准。
 * @param client owner 回环客户端。
 * @param arguments `approve <callId>`，不接受绕过确认的标志。
 * @return 审批后的结构化调用结果。
 */
private suspend fun approveFromConsole(client: LocalApiClient, arguments: List<String>): JsonElement {
    if (arguments.size != 2) throw CliCommandException("call_id_required")
    var offset = 0
    var preview: JsonObject? = null
    do {
        val page = client.approvals(offset)
        preview = page.items.singleOrNull { it["callId"]?.jsonPrimitive?.content == arguments[1] }
        offset = page.nextOffset ?: -1
    } while (preview == null && offset >= 0)
    if (preview == null) throw LocalApiException(404, "call_not_found")
    System.err.println(preview)
    val reader = System.`in`.bufferedReader(Charsets.UTF_8)
    System.err.println("Review the preview above. Type confirm to approve:")
    if (reader.readLine() != "confirm") return localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), client.cancel(arguments[1]))
    val second = preview["confirmationLevel"]?.jsonPrimitive?.content == AgentConfirmationLevel.SECOND.name
    if (second) {
        System.err.println("This action deletes or exports content. Type confirm-risk to confirm again:")
        if (reader.readLine() != "confirm-risk") return localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), client.cancel(arguments[1]))
    }
    return localApiJson.encodeToJsonElement(LocalCallResponse.serializer(), client.approve(arguments[1], LocalApprovalInput(true, second)))
}

/** 可在未配置环境或未打开工作区时查看的稳定命令说明。 */
private const val CLI_USAGE = """
tagloom: local workspace API client
Configure TAGLOOM_API_URL and TAGLOOM_API_TOKEN, then unlock the desktop workspace.
  tools | list | search <text> | get <noteId> | block <noteId> <blockId>
  query --tag <typeId> --where '<propertyId> = <JSON value>'
  tags | tag-schema <typeId> | schema | queries | saved-query <queryId> | run-query <queryId>
  create --title <title> --content <markdown> [--tag <typeId>]
  update <noteId> --revision <revision> --title <title> [--content <markdown>]
  add-tag <noteId> --revision <revision> --tagId <typeId>
  export <noteId> --revision <revision> [--mode extended]
  delete <noteId> --revision <revision>
  call <tool_name> --json <object>
  result <callId> | cancel <callId>
Owner only: approvals | approve <callId> | grant --json <object> | revoke <principalId>
MCP client entry: tagloom mcp
All writes return a pending call. Owner approval is a separate interactive command.
"""
