package com.oruke.tagloom.automation

import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/** 将官方 MCP stdio 协议代理到本机 API，不解锁工作区，不向 stdout 写协议之外的内容。 */
class TagloomMcpServer(
    /** 当前 Agent 的回环 API 客户端和独立凭据。 */
    private val client: LocalApiClient,
) {
    /**
     * 以当前授权工具启动 stdio 会话，并在输入关闭时释放协议与网络资源。
     * @param input MCP 客户端的标准输入。
     * @param output MCP 客户端的标准输出。
     * @return stdin 关闭且协议资源释放后返回。
     */
    suspend fun run(input: java.io.InputStream = System.`in`, output: java.io.OutputStream = System.out) {
        val discovery = client.tools()
        client.bindSession(discovery.sessionId)
        val tools = TagloomMcpToolService(client, discovery.tools)
        val session = ServerSession(Implementation("tagloom", "1.0.0"), ServerOptions(capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
            resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
        )), instructions = null)
        // 使用官方 SDK 会话完成握手和传输，在启动读取前安装处理器。
        // SDK 高层 Server 把未知工具吞成 isError；这里显式保留协议错误与执行错误的边界。
        session.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ -> ListToolsResult(tools.tools) }
        session.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ -> tools.invoke(request) }
        session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, _ ->
            ListResourcesResult(listOf(Resource("workspace://schema", "Workspace schema",
                description = "授权范围内的类型和属性定义。", mimeType = "application/json")))
        }
        session.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { _, _ ->
            ListResourceTemplatesResult(listOf("note://{noteId}" to "Note", "tag://{tagId}" to "Note type", "query://saved/{queryId}" to "Saved query")
                .map { (template, name) -> ResourceTemplate(template, name, "按独立 Token 的权限读取已确认资源。", "application/json") })
        }
        session.setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, _ ->
            readResource(request.uri)
        }
        val closed = CompletableDeferred<Unit>()
        val transport = StdioServerTransport(input.asSource().buffered(), output.asSink().buffered()) {}
        transport.onClose { closed.complete(Unit) }
        try { session.connect(transport); closed.await() }
        finally { session.close() }
    }

    /**
     * 把严格 URI 交给同一权限与审计入口。
     * @param uri MCP 请求的 URI。
     * @return 只包含已授权资源的 JSON 内容。
     */
    private suspend fun readResource(uri: String): ReadResourceResult {
        try {
            val response = client.resource(uri)
            val data = response.result?.payload ?: throw LocalApiException(404, "not_found")
            return ReadResourceResult(listOf(TextResourceContents(data.toString(), uri, "application/json")))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw McpException(RPCError.ErrorCode.RESOURCE_NOT_FOUND, localApiFailure(error).code) }
    }
}
