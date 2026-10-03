package com.oruke.tagloom.automation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import io.ktor.utils.io.readAvailable
import java.net.URI

/** 让 CLI 与 MCP 只调用回环 API，不另开工作区写入者或读取磁盘密钥。 */
class LocalApiClient(
    /** 显式指定的回环 API 根 URL；不从工作区文件推断。 */
    private val baseUrl: String,
    /** 本进程使用的独立 Bearer 凭据。 */
    private val token: String,
) : AutoCloseable {
    /** 只在当前进程内存在的回环客户端。 */
    private val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 40_000 } }
    /** 长驻协议进程绑定的解锁会话；绑定后禁止跨工作区或重新解锁复用。 */
    private var boundSessionId: String? = null

    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1") && uri.port in 1..65535 &&
            uri.rawPath in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) { "api_url_invalid" }
        require(token.length in 32..1024) { "api_token_invalid" }
    }

    /** @return 当前 Token 授权的工具、会话身份与 scope。 */
    suspend fun tools(): LocalToolList = request("/v1/tools", HttpMethod.Get, null, LocalToolList.serializer())

    /**
     * 把后续请求绑定到已发现的会话，由 API 在同一调用锁中验证身份。
     * @param sessionId 启动时发现的随机解锁会话 ID。
     */
    fun bindSession(sessionId: String) {
        safeId(sessionId)
        check(boundSessionId == null || boundSessionId == sessionId) { "session_changed" }
        boundSessionId = sessionId
    }

    /**
     * 提交统一业务调用。
     * @param input 工具参数与幂等身份。
     * @return 即时结果或待确认状态。
     */
    suspend fun call(input: LocalCallInput): LocalCallResponse = request("/v1/calls", HttpMethod.Post,
        localApiJson.encodeToString(LocalCallInput.serializer(), input), LocalCallResponse.serializer())

    /**
     * 查询调用状态。
     * @param callId 当前会话调用 ID。
     * @return 当前状态和执行结果。
     */
    suspend fun result(callId: String): LocalCallResponse = request("/v1/calls/${safeId(callId)}", HttpMethod.Get, null, LocalCallResponse.serializer())

    /**
     * 分页读取 owner 审批队列。
     * @param offset 队列偏移。
     * @return 最多 100 个当前待审批项。
     */
    suspend fun approvals(offset: Int = 0): LocalJsonPage = request("/v1/approvals?limit=100&offset=$offset", HttpMethod.Get, null, LocalJsonPage.serializer())

    /**
     * 提交持有者的明确确认。
     * @param callId 已预览调用。
     * @param evidence 首次和二次确认事实。
     * @return 唯一执行结果。
     */
    suspend fun approve(callId: String, evidence: LocalApprovalInput): LocalCallResponse = request("/v1/approvals/${safeId(callId)}", HttpMethod.Put,
        localApiJson.encodeToString(LocalApprovalInput.serializer(), evidence), LocalCallResponse.serializer())

    /**
     * 取消自己的待审批调用。
     * @param callId 当前调用。
     * @return 已取消或已完成状态。
     */
    suspend fun cancel(callId: String): LocalCallResponse = request("/v1/calls/${safeId(callId)}", HttpMethod.Delete, null, LocalCallResponse.serializer())

    /**
     * 创建一个独立、当前会话有效的授权。
     * @param input owner 明确指定的范围。
     * @return 一次性 Token 响应，禁止写入日志。
     */
    suspend fun grant(input: LocalGrantInput): LocalGrantResponse = request("/v1/grants", HttpMethod.Post,
        localApiJson.encodeToString(LocalGrantInput.serializer(), input), LocalGrantResponse.serializer())

    /**
     * 撤销当前会话的独立 Agent 授权，同时清除其待确认请求与结果缓存。
     * @param principalId 持有者授予时返回的随机身份。
     */
    suspend fun revoke(principalId: String): Unit = request("/v1/grants/${safeId(principalId)}", HttpMethod.Delete, null, Unit.serializer())

    /**
     * 读取严格资源 URI，复用统一工具授权和审计。
     * @param uri 工作区资源 URI。
     * @return 资源对应的已确认结构化结果。
     */
    suspend fun resource(uri: String): LocalCallResponse = call(resourceCall(uri))

    /** 关闭本进程回环连接，不影响宿主工作区会话。 */
    override fun close() { client.close() }

    /**
     * 发送一个有限时请求并只返回结构化稳定失败。
     * @param path API 相对路径。
     * @param method HTTP 方法。
     * @param body 可选 JSON 内容。
     * @param serializer 响应 serializer。
     * @return 已解码的响应 DTO。
     */
    private suspend fun <T> request(path: String, method: HttpMethod, body: String?, serializer: KSerializer<T>): T {
        try {
            val response = client.request(baseUrl.trimEnd('/') + path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                boundSessionId?.let { header(LOCAL_SESSION_HEADER, it) }
                accept(ContentType.Application.Json)
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
            val text = if (response.status == HttpStatusCode.NoContent) "{}" else response.readLocalResponse()
            if (!response.status.isSuccess()) {
                val problem = runCatching { localApiJson.decodeFromString(LocalApiProblem.serializer(), text) }.getOrNull()
                throw LocalApiException(response.status.value, problem?.code ?: "api_request_failed")
            }
            return localApiJson.decodeFromString(serializer, text)
        } catch (error: CancellationException) { throw error }
        catch (error: LocalApiException) { throw error }
        catch (_: Exception) { throw LocalApiException(503, "api_unavailable") }
    }

    /**
     * 验证路径中的随机 ID，禁止将任意路径传给 API。
     * @param value 调用 ID。
     * @return 校验后的 ID。
     */
    private fun safeId(value: String): String = com.oruke.tagloom.core.external.ExternalToolArguments.opaque(value)
}

/**
 * 有界读取本机响应，分块或没有 Content-Length 的服务也不能无限占用客户端内存。
 * @return 严格 UTF-8 JSON 文本；超过响应预算返回稳定错误。
 */
private suspend fun HttpResponse.readLocalResponse(): String {
    val bytes = ByteArray(MAX_LOCAL_RESPONSE_BYTES + 1)
    var count = 0
    try {
        val channel = bodyAsChannel()
        while (count < bytes.size) {
            val read = channel.readAvailable(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        if (count > MAX_LOCAL_RESPONSE_BYTES) throw LocalApiException(413, "response_too_large")
        return bytes.decodeToString(0, count, throwOnInvalidSequence = true)
    } finally { bytes.fill(0) }
}

/** 响应容纳有界笔记正文和审批预览；CLI/MCP 不读取无限长本机数据。 */
private const val MAX_LOCAL_RESPONSE_BYTES: Int = 16 * 1_048_576
