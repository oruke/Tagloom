package com.oruke.tagloom.automation

import com.oruke.tagloom.core.external.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** 严格的外部传输 JSON，不默默忽略客户端的未知权限或确认字段。 */
internal val localApiJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

/**
 * 限制本机 API 请求的 Host 和 Origin，防止浏览器跨源调用或 DNS 重绑定。
 * @param call 当前 HTTP 请求。
 */
internal fun validateLocalApiOrigin(call: ApplicationCall) {
    val port = call.request.local.serverPort
    val hosts = setOf("localhost:$port", "127.0.0.1:$port")
    if (call.request.headers[HttpHeaders.Host] !in hosts) throw LocalApiException(403, "invalid_host")
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null && origin !in hosts.map { "http://$it" }) throw LocalApiException(403, "invalid_origin")
}

/** @return 当前请求 Bearer 凭据；缺失或格式无效返回稳定 401。 */
internal fun ApplicationCall.localBearer(): String {
    val value = request.headers[HttpHeaders.Authorization].orEmpty()
    if (!value.startsWith("Bearer ") || value.length !in 39..1031) throw LocalApiException(401, "unauthorized")
    return value.removePrefix("Bearer ")
}

/** 本机协议进程绑定解锁会话的请求头，不作为认证凭据。 */
internal const val LOCAL_SESSION_HEADER: String = "X-Tagloom-Session-Id"

/** @return 可选的严格会话身份；绑定检查由控制器与实际业务调用一起执行。 */
internal fun ApplicationCall.localSessionId(): String? = request.headers[LOCAL_SESSION_HEADER]?.let(ExternalToolArguments::opaque)

/**
 * 有界读取 UTF-8 JSON，请求分块时也不能绕过大小限制。
 * @param serializer 目标输入 DTO serializer。
 * @return 已严格解析的输入。
 */
internal suspend fun <T> ApplicationCall.receiveLocalJson(serializer: KSerializer<T>): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) throw LocalApiException(415, "json_required")
    val bytes = ByteArray(MAX_LOCAL_REQUEST_BYTES + 1)
    var count = 0
    try {
        val channel = receiveChannel()
        while (count < bytes.size) {
            val read = channel.readAvailable(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        if (count > MAX_LOCAL_REQUEST_BYTES) throw LocalApiException(413, "request_too_large")
        return localApiJson.decodeFromString(serializer, bytes.decodeToString(0, count, throwOnInvalidSequence = true))
    } finally { bytes.fill(0) }
}

/**
 * 读取严格整数分页值。
 * @param name query 参数名称。
 * @param default 缺省值。
 * @param range 允许范围。
 * @return 已校验整数。
 */
internal fun ApplicationCall.pageInteger(name: String, default: Int, range: IntRange): Int {
    val value = request.queryParameters[name] ?: return default
    val parsed = value.toIntOrNull()?.takeIf { it in range } ?: throw LocalApiException(400, "invalid_arguments")
    return parsed
}

/**
 * 取得一个不含路径的资源 ID。
 * @param name path 参数名称。
 * @return 已校验随机 ID。
 */
internal fun ApplicationCall.localId(name: String): String = ExternalToolArguments.opaque(
    parameters[name] ?: throw LocalApiException(400, "invalid_arguments"),
)

/**
 * 将 MCP URI 映射成相同授权工具，资源读取也不能绕过审计。
 * @param uri 支持的严格资源 URI。
 * @return 对应只读工具调用。
 */
internal fun resourceCall(uri: String): LocalCallInput {
    val identity = McpResourceParser.parse(uri) ?: throw LocalApiException(400, "invalid_resource_uri")
    return when (identity.kind) {
        McpResourceKind.NOTE -> LocalCallInput("get_note", buildJsonObject { put("noteId", identity.resourceId) })
        McpResourceKind.TAG -> LocalCallInput("get_tag_schema", buildJsonObject { put("tagId", identity.resourceId) })
        McpResourceKind.WORKSPACE_SCHEMA -> LocalCallInput("get_workspace_schema")
        McpResourceKind.SAVED_QUERY -> LocalCallInput("get_saved_query", buildJsonObject { put("queryId", identity.resourceId) })
    }
}

/** API 对单条明文 JSON 请求的传输上限；不创建明文临时文件。 */
private const val MAX_LOCAL_REQUEST_BYTES: Int = 1_048_576
